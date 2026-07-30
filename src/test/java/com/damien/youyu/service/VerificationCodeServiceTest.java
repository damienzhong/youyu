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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.VerificationCode;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * {@link VerificationCodeService} 的示例/边界单元测试（关联需求 1、2）。
 *
 * <p>不接真实 DB：仓库以有状态的内存存储驱动（{@code save} 分配自增 id 并入表，
 * 查询/计数/存在性均基于内存真实计算），发送器用记录型测试替身，时钟可推进。
 * 覆盖：邮箱格式、冷却、IP 分钟/日限流、发送失败翻译、单次消费、过期、失败次数上限。</p>
 */
class VerificationCodeServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final EmailCodePurpose LOGIN = EmailCodePurpose.LOGIN;
    private static final String EMAIL = "user@example.com";
    private static final String IP = "203.0.113.9";

    private final List<VerificationCode> store = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    private MutableClock clock;
    private VerificationCodeRepository repository;
    private RecordingSender sender;
    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        store.clear();
        idSeq.set(0);
        clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZONE);
        repository = inMemoryRepository();
        sender = new RecordingSender();
        // 默认：ttl=600s, cooldown=60s, ip/min=3, ip/day=30, maxAttempts=5
        service = new VerificationCodeService(repository, sender, clock, 600, 60, 3, 30, 5);
    }

    // ---- sendCode：邮箱格式 ----

    @Test
    void rejectsInvalidEmailWithoutSendingOrStoring() {
        assertThatThrownBy(() -> service.sendCode("not-an-email", LOGIN, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_INVALID"));
        assertThat(store).isEmpty();
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void sendGeneratesSixDigitCodeAndTenMinuteTtl() {
        service.sendCode(EMAIL, LOGIN, IP);

        assertThat(store).hasSize(1);
        VerificationCode vc = store.get(0);
        assertThat(vc.getCode()).matches("\\d{6}");
        assertThat(vc.getExpiresAt())
                .isEqualTo(LocalDateTime.now(clock).plusSeconds(600));
        assertThat(vc.isConsumed()).isFalse();
        assertThat(vc.getAttemptCount()).isZero();
        assertThat(sender.sent).singleElement()
                .satisfies(s -> assertThat(s.code).isEqualTo(vc.getCode()));
    }

    // ---- sendCode：冷却 ----

    @Test
    void rejectsSecondSendWithinCooldown() {
        service.sendCode(EMAIL, LOGIN, IP);
        assertThatThrownBy(() -> service.sendCode(EMAIL, LOGIN, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CODE_COOLDOWN"));
        // 冷却期内不新增记录、不再次发送。
        assertThat(store).hasSize(1);
        assertThat(sender.sent).hasSize(1);
    }

    @Test
    void allowsSendAfterCooldownElapses() {
        service.sendCode(EMAIL, LOGIN, IP);
        clock.advance(Duration.ofSeconds(61));
        service.sendCode(EMAIL, LOGIN, IP);
        assertThat(store).hasSize(2);
        assertThat(sender.sent).hasSize(2);
    }

    @Test
    void cooldownIsPerPurpose() {
        service.sendCode(EMAIL, EmailCodePurpose.LOGIN, IP);
        // 不同用途不共享冷却窗口。
        service.sendCode(EMAIL, EmailCodePurpose.BIND, IP);
        assertThat(store).hasSize(2);
    }

    // ---- sendCode：IP 限流 ----

    @Test
    void rejectsWhenIpPerMinuteLimitReached() {
        // 每分钟上限 3：用不同邮箱绕开冷却，逼近同 IP 分钟窗口上限。
        service.sendCode("a@example.com", LOGIN, IP);
        service.sendCode("b@example.com", LOGIN, IP);
        service.sendCode("c@example.com", LOGIN, IP);
        assertThatThrownBy(() -> service.sendCode("d@example.com", LOGIN, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CODE_RATE_LIMITED"));
        assertThat(store).hasSize(3);
    }

    @Test
    void rejectsWhenIpPerDayLimitReached() {
        // 用小额度重建服务，便于验证日窗口独立于分钟窗口生效。
        service = new VerificationCodeService(repository, sender, clock, 600, 60, 100, 2, 5);
        service.sendCode("a@example.com", LOGIN, IP);
        clock.advance(Duration.ofMinutes(2));
        service.sendCode("b@example.com", LOGIN, IP);
        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> service.sendCode("c@example.com", LOGIN, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CODE_RATE_LIMITED"));
    }

    @Test
    void ipLimitSkippedWhenIpMissing() {
        // ip 为空：跳过 IP 维度限流（仍受冷却约束，故用不同邮箱）。
        for (int i = 0; i < 5; i++) {
            service.sendCode("u" + i + "@example.com", LOGIN, null);
        }
        assertThat(store).hasSize(5);
    }

    // ---- sendCode：发送失败翻译 ----

    @Test
    void translatesMailExceptionToEmailSendFailed() {
        sender.failWith(new MailSendException("smtp down"));
        assertThatThrownBy(() -> service.sendCode(EMAIL, LOGIN, IP))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("EMAIL_SEND_FAILED"));
    }

    // ---- verifyConsume：成功与单次消费 ----

    @Test
    void verifyConsumeSucceedsThenSingleUse() {
        service.sendCode(EMAIL, LOGIN, IP);
        String code = store.get(0).getCode();

        assertThat(service.verifyConsume(EMAIL, LOGIN, code)).isTrue();
        // 单次消费：二次校验必失败。
        assertThat(service.verifyConsume(EMAIL, LOGIN, code)).isFalse();
        assertThat(store.get(0).isConsumed()).isTrue();
    }

    @Test
    void verifyConsumeFailsForUnknownEmail() {
        assertThat(service.verifyConsume("nobody@example.com", LOGIN, "123456")).isFalse();
    }

    // ---- verifyConsume：过期 ----

    @Test
    void verifyConsumeFailsWhenExpired() {
        service.sendCode(EMAIL, LOGIN, IP);
        String code = store.get(0).getCode();
        clock.advance(Duration.ofSeconds(601));
        assertThat(service.verifyConsume(EMAIL, LOGIN, code)).isFalse();
    }

    // ---- verifyConsume：失败次数上限 ----

    @Test
    void verifyConsumeInvalidatesAfterMaxAttempts() {
        service.sendCode(EMAIL, LOGIN, IP);
        String correct = store.get(0).getCode();

        // 连续 5 次错误：达到上限后失效。
        for (int i = 0; i < 5; i++) {
            assertThat(service.verifyConsume(EMAIL, LOGIN, "000000".equals(correct) ? "111111" : "000000"))
                    .isFalse();
        }
        assertThat(store.get(0).isConsumed()).isTrue();
        // 超次失效后，即使给出正确码也不再通过。
        assertThat(service.verifyConsume(EMAIL, LOGIN, correct)).isFalse();
    }

    // ---- 内存仓库替身 ----

    private VerificationCodeRepository inMemoryRepository() {
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
                            .max((a, b) -> Long.compare(a.getId(), b.getId()));
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

    // ---- 测试替身：记录型发送器 ----

    private static final class RecordingSender implements VerificationCodeSender {
        private final List<Sent> sent = new ArrayList<>();
        private MailException failure;

        void failWith(MailException e) {
            this.failure = e;
        }

        @Override
        public void send(String email, String code, EmailCodePurpose purpose) {
            if (failure != null) {
                throw failure;
            }
            sent.add(new Sent(email, code, purpose));
        }
    }

    private static final class Sent {
        final String email;
        final String code;
        final EmailCodePurpose purpose;

        Sent(String email, String code, EmailCodePurpose purpose) {
            this.email = email;
            this.code = code;
            this.purpose = purpose;
        }
    }

    // ---- 可推进的时钟 ----

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
