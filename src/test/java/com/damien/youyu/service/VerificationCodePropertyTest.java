package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.mockito.Mockito;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.VerificationCode;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.VerificationCodeRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * {@link VerificationCodeService} 的属性测试（jqwik），覆盖设计文档 Correctness Properties 中的
 * Property 1、Property 2、Property 3。每个属性运行 200 次随机迭代（与仓库既有 {@code *PropertyTest}
 * 约定一致，如 {@code PlanRolePropertyTest}）。
 *
 * <p>不接真实 DB、不发真实邮件：验证码仓库以有状态的内存存储驱动（{@code save} 分配自增 id 并入表，
 * 查询/计数/存在性均基于内存真实计算），发送器用记录型测试替身，时钟可推进。被测的发码/校验业务逻辑
 * （邮箱校验、冷却、IP 限流、单次消费、过期、失败次数上限）全部真实执行。</p>
 *
 * <ul>
 *   <li>Property 1（验证码单次消费）：Validates Requirements 2.1, 5.4</li>
 *   <li>Property 2（验证码过期与次数上限）：Validates Requirements 1.2, 2.2, 2.3</li>
 *   <li>Property 3（冷却与限流）：Validates Requirements 1.3, 1.4</li>
 * </ul>
 */
class VerificationCodePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static final int TTL = 600;         // 10 分钟
    private static final int COOLDOWN = 60;      // 60 秒
    private static final int MAX_ATTEMPTS = 5;   // 失败次数上限

    // ---------------- 生成器 ----------------

    /** 合法邮箱：本地部分 [a-z0-9]{1,15} + @example.com（满足服务端邮箱正则）。 */
    @Provide
    Arbitrary<String> emails() {
        return Arbitraries.strings().withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(15)
                .map(local -> local + "@example.com");
    }

    /** 验证码用途：LOGIN / BIND / DELETE。 */
    @Provide
    Arbitrary<EmailCodePurpose> purposes() {
        return Arbitraries.of(EmailCodePurpose.values());
    }

    /** 来源 IP（简单的四段格式即可，仅作为限流/审计维度键使用）。 */
    @Provide
    Arbitrary<String> ips() {
        return Arbitraries.integers().between(1, 254)
                .map(n -> "203.0.113." + n);
    }

    // ---------------- Property 1: 验证码单次消费 ----------------

    /**
     * Property 1（验证码单次消费）：任意成功校验的验证码在校验通过后立即失效，其任意二次校验必失败。
     *
     * <p>对随机 (email, purpose, ip) 发码后，读取存表生成的真实验证码：首次 {@code verifyConsume}
     * 必返回 {@code true} 并将记录标记为已消费；对同一验证码的任意后续 {@code verifyConsume} 必返回
     * {@code false}（防重放）。</p>
     *
     * <p>Validates: Requirements 2.1, 5.4</p>
     */
    @Property(tries = 25)
    void property1_successfulCodeIsSingleUse(
            @ForAll("emails") String email,
            @ForAll("purposes") EmailCodePurpose purpose,
            @ForAll("ips") String ip) {
        Ctx c = build(TTL, COOLDOWN, 10_000, 100_000, MAX_ATTEMPTS);
        c.service.sendCode(email, purpose, ip);
        String code = c.store.get(0).getCode();

        // 首次校验成功并单次消费。
        assertThat(c.service.verifyConsume(email, purpose, code)).isTrue();
        assertThat(c.store.get(0).isConsumed()).isTrue();

        // 任意二次校验必失败（防重放）。
        assertThat(c.service.verifyConsume(email, purpose, code)).isFalse();
        assertThat(c.service.verifyConsume(email, purpose, code)).isFalse();
    }

    // ---------------- Property 2: 验证码过期与次数上限 ----------------

    /**
     * Property 2（验证码过期与次数上限）：一枚验证码在超过 TTL（10 分钟）后，或累计失败达到上限（5 次）后，
     * 一律校验失败——即便给出的是正确的码。
     *
     * <p>随机生成推进秒数 {@code advanceSeconds ∈ [0, 2·TTL]} 与错误尝试次数
     * {@code wrongAttempts ∈ [0, 2·MAX_ATTEMPTS]}。发码后先做 {@code wrongAttempts} 次错误校验，
     * 再推进时钟 {@code advanceSeconds}，最后用<strong>正确</strong>的码校验。当且仅当「尚未超次
     * 且尚未过期」时才应成功，否则一律失败。</p>
     *
     * <p>Validates: Requirements 1.2, 2.2, 2.3</p>
     */
    @Property(tries = 25)
    void property2_expiredOrOverAttemptAlwaysFails(
            @ForAll("emails") String email,
            @ForAll("purposes") EmailCodePurpose purpose,
            @ForAll @IntRange(min = 0, max = 1200) int advanceSeconds,
            @ForAll @IntRange(min = 0, max = 10) int wrongAttempts) {
        Ctx c = build(TTL, COOLDOWN, 10_000, 100_000, MAX_ATTEMPTS);
        c.service.sendCode(email, purpose, "203.0.113.7");
        String correct = c.store.get(0).getCode();
        String wrong = "000000".equals(correct) ? "111111" : "000000";

        // 1) 先做 wrongAttempts 次错误校验（此时尚未推进时钟）。
        for (int i = 0; i < wrongAttempts; i++) {
            assertThat(c.service.verifyConsume(email, purpose, wrong)).isFalse();
        }

        // 2) 推进时钟。
        c.clock.advance(Duration.ofSeconds(advanceSeconds));

        // 3) 用正确码校验：仅当既未超次又未过期时才应通过。
        boolean expectedSuccess = wrongAttempts < MAX_ATTEMPTS && advanceSeconds < TTL;
        assertThat(c.service.verifyConsume(email, purpose, correct)).isEqualTo(expectedSuccess);
    }

    // ---------------- Property 3: 冷却与限流 ----------------

    /**
     * Property 3（冷却与限流）：同一 (email, purpose) 在冷却窗口内的二次发码被拒（CODE_COOLDOWN）；
     * 同一来源 IP 的发码超过每分钟上限后被拒（CODE_RATE_LIMITED）。
     *
     * <p>子命题 A（冷却，需求 1.3）：发码后推进 {@code gapSeconds ∈ [0, 180]}，对同一 (email, purpose)
     * 用<strong>不同 IP</strong>再次发码（隔离出冷却维度）。当且仅当 {@code gapSeconds < COOLDOWN} 时被
     * {@code CODE_COOLDOWN} 拒绝，否则应成功入表。</p>
     *
     * <p>子命题 B（IP 限流，需求 1.4）：以随机每分钟上限 {@code ipPerMinute ∈ [1, 5]} 建服务，用同一 IP、
     * 各不相同的邮箱（绕开冷却）连续发满 {@code ipPerMinute} 次后，下一次发码必被 {@code CODE_RATE_LIMITED}
     * 拒绝。</p>
     *
     * <p>Validates: Requirements 1.3, 1.4</p>
     */
    @Property(tries = 25)
    void property3_cooldownAndRateLimit(
            @ForAll("emails") String email,
            @ForAll("purposes") EmailCodePurpose purpose,
            @ForAll("ips") String ip,
            @ForAll @IntRange(min = 0, max = 180) int gapSeconds,
            @ForAll @IntRange(min = 1, max = 5) int ipPerMinute) {

        // ---- 子命题 A：同 (email, purpose) 冷却 ----
        Ctx a = build(TTL, COOLDOWN, 10_000, 100_000, MAX_ATTEMPTS);
        a.service.sendCode(email, purpose, ip);
        a.clock.advance(Duration.ofSeconds(gapSeconds));

        String otherIp = ip.equals("203.0.113.1") ? "203.0.113.2" : "203.0.113.1";
        if (gapSeconds < COOLDOWN) {
            assertThatThrownBy(() -> a.service.sendCode(email, purpose, otherIp))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CODE_COOLDOWN"));
            // 冷却期内零副作用：不新增记录。
            assertThat(a.store).hasSize(1);
        } else {
            a.service.sendCode(email, purpose, otherIp);
            assertThat(a.store).hasSize(2);
        }

        // ---- 子命题 B：同 IP 每分钟限流 ----
        Ctx b = build(TTL, COOLDOWN, ipPerMinute, 100_000, MAX_ATTEMPTS);
        for (int i = 0; i < ipPerMinute; i++) {
            // 各不相同的邮箱以绕开 (email, purpose) 冷却，专测 IP 分钟窗口上限。
            b.service.sendCode("rl" + i + "@example.com", purpose, ip);
        }
        assertThat(b.store).hasSize(ipPerMinute);
        assertThatThrownBy(() -> b.service.sendCode("rlx@example.com", purpose, ip))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CODE_RATE_LIMITED"));
        // 被限流请求零副作用：记录数不变。
        assertThat(b.store).hasSize(ipPerMinute);
    }

    // ---------------- 测试基础设施 ----------------

    /** 一次属性迭代的被测上下文：服务 + 有状态存储 + 可推进时钟。 */
    private static final class Ctx {
        final VerificationCodeService service;
        final List<VerificationCode> store;
        final MutableClock clock;

        Ctx(VerificationCodeService service, List<VerificationCode> store, MutableClock clock) {
            this.service = service;
            this.store = store;
            this.clock = clock;
        }
    }

    /** 构造一套全新的（服务 + 内存仓库 + 记录型发送器 + 可推进时钟）。 */
    private Ctx build(int ttl, int cooldown, int ipPerMinute, int ipPerDay, int maxAttempts) {
        List<VerificationCode> store = new ArrayList<>();
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZONE);
        VerificationCodeRepository repository = inMemoryRepository(store);
        VerificationCodeSender sender = (e, code, purpose) -> { /* 记录型：不发真实邮件 */ };
        VerificationCodeService service = new VerificationCodeService(
                repository, sender, clock, ttl, cooldown, ipPerMinute, ipPerDay, maxAttempts, "");
        return new Ctx(service, store, clock);
    }

    /** 内存态验证码仓库替身：真实基于给定 {@code store} 计算，非预置桩返回值。 */
    private VerificationCodeRepository inMemoryRepository(List<VerificationCode> store) {
        AtomicLong idSeq = new AtomicLong(0);
        VerificationCodeRepository repo = Mockito.mock(VerificationCodeRepository.class);

        when(repo.save(any(VerificationCode.class))).thenAnswer(inv -> {
            VerificationCode vc = inv.getArgument(0);
            if (vc.getId() == null) {
                vc.setId(idSeq.incrementAndGet());
                store.add(vc);
            }
            return vc;
        });

        when(repo.findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(any(), any()))
                .thenAnswer(inv -> {
                    String email = inv.getArgument(0);
                    EmailCodePurpose purpose = inv.getArgument(1);
                    return store.stream()
                            .filter(v -> v.getEmail().equals(email)
                                    && v.getPurpose() == purpose
                                    && !v.isConsumed())
                            .max((x, y) -> Long.compare(x.getId(), y.getId()));
                });

        when(repo.existsByEmailAndPurposeAndCreatedAtAfter(any(), any(), any()))
                .thenAnswer(inv -> {
                    String email = inv.getArgument(0);
                    EmailCodePurpose purpose = inv.getArgument(1);
                    LocalDateTime since = inv.getArgument(2);
                    return store.stream().anyMatch(v -> v.getEmail().equals(email)
                            && v.getPurpose() == purpose
                            && v.getCreatedAt().isAfter(since));
                });

        when(repo.countByIpAndCreatedAtAfter(any(), any()))
                .thenAnswer(inv -> {
                    String ip = inv.getArgument(0);
                    LocalDateTime since = inv.getArgument(1);
                    return store.stream().filter(v -> ip.equals(v.getIp())
                            && v.getCreatedAt().isAfter(since)).count();
                });

        return repo;
    }

    /** 可推进的时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
