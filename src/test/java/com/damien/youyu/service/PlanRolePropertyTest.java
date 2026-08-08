package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import org.mockito.Mockito;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.support.InMemoryUserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * plan/role 字段的属性测试（jqwik），覆盖设计文档 Correctness Properties 中的
 * Property 25（套餐到期时刻计算）与 Property 26（plan/role 非枚举值写入零副作用）。
 *
 * <p>每个属性至少运行 100 次随机迭代。使用真实的 {@link InMemoryUserRepository} 存储实现与真实的
 * {@link AuthService}/{@link UserService} 业务逻辑，不使用任何 mock。时间以固定/可调 {@link Clock}
 * 注入以获得确定性。</p>
 *
 * <p>关联需求：9.2、9.3。</p>
 */
class PlanRolePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration ONE_YEAR = Duration.ofHours(365L * 24L);

    // ---------------- 可调时钟：用于让不同用户在不同时刻注册 ----------------

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
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

    // ---------------- 生成器 ----------------

    /** 合法邮箱本地部分：小写字母 1-32（拼上 @example.com 作为登录邮箱）。 */
    @Provide
    Arbitrary<String> usernames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(32);
    }

    /**
     * 注册时刻（epoch 秒）：限制在 1970-01-01 至约 2200 年之间，
     * 确保 +365 天不会触及 LocalDateTime 的可表示上界，从而做确定性时间断言。
     */
    @Provide
    Arbitrary<Long> registrationEpochSeconds() {
        return Arbitraries.longs().between(0L, 7_258_000_000L);
    }

    /** 任意字符串（含空串），用于覆盖枚举合法与非法两个分支。 */
    @Provide
    Arbitrary<String> anyCodes() {
        return Arbitraries.strings().ofMinLength(0).ofMaxLength(12);
    }

    // ---------------- Property 25 ----------------

    /**
     * Feature: youyu-ledger, Property 25: 对任意用户注册时刻，系统持久化的 {@code plan_expires_at}
     * 应等于该用户 {@code plan_started_at} 之后精确 365×24 小时的时刻，且该计算按各用户独立进行、互不影响。
     *
     * <p>用两个不同的注册时刻在同一存储中先后注册两名用户，分别断言各自到期时刻的正确性，
     * 并校验第二名用户注册后第一名用户的时间字段未被改变（互不影响）。</p>
     *
     * <p>Validates: Requirements 9.2</p>
     */
    @Property(tries = 25)
    void property25_planExpiryComputedPerUserIndependently(
            @ForAll("usernames") String username1,
            @ForAll("usernames") String username2,
            @ForAll("registrationEpochSeconds") long epoch1,
            @ForAll("registrationEpochSeconds") long epoch2) {
        Assume.that(!username1.equals(username2));

        InMemoryUserRepository repository = new InMemoryUserRepository();
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(epoch1), ZONE);
        // 无密码模型下"注册"经由邮箱验证码登录/注册合一；验证码校验以测试替身恒真隔离。
        VerificationCodeService verificationCodeService = Mockito.mock(VerificationCodeService.class);
        Mockito.when(verificationCodeService.verifyConsume(
                Mockito.anyString(), Mockito.eq(EmailCodePurpose.LOGIN), Mockito.anyString()))
                .thenReturn(true);
        InviteBindingService inviteBindingService = Mockito.mock(InviteBindingService.class);
        Mockito.when(inviteBindingService.bindOnRegister(Mockito.any(), Mockito.anyBoolean(),
                Mockito.any(), Mockito.any()))
                .thenReturn(InviteBindResult.ofUnbound(UnboundReason.NO_CODE));
        AuthService service = new AuthService(repository, clock, null, verificationCodeService,
                new InviteCodeGenerator(), inviteBindingService);

        String email1 = username1 + "@example.com";
        String email2 = username2 + "@example.com";

        // 用户 1 在 epoch1 首次邮箱登录（建号）
        clock.setInstant(Instant.ofEpochSecond(epoch1));
        User user1 = service.emailLogin(email1, "123456", null).user();
        LocalDateTime expectedStart1 = LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch1), ZONE);

        // 用户 2 在 epoch2 首次邮箱登录（可能早于或晚于 epoch1）
        clock.setInstant(Instant.ofEpochSecond(epoch2));
        User user2 = service.emailLogin(email2, "123456", null).user();
        LocalDateTime expectedStart2 = LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch2), ZONE);

        // 各自到期 = 各自起始 + 精确 365×24 小时
        assertThat(user1.getPlanStartedAt()).isEqualTo(expectedStart1);
        assertThat(user1.getPlanExpiresAt()).isEqualTo(expectedStart1.plusDays(365));
        assertThat(Duration.between(user1.getPlanStartedAt(), user1.getPlanExpiresAt()))
                .isEqualTo(ONE_YEAR);

        assertThat(user2.getPlanStartedAt()).isEqualTo(expectedStart2);
        assertThat(user2.getPlanExpiresAt()).isEqualTo(expectedStart2.plusDays(365));
        assertThat(Duration.between(user2.getPlanStartedAt(), user2.getPlanExpiresAt()))
                .isEqualTo(ONE_YEAR);

        // 互不影响：注册用户 2 之后，重新读出的用户 1 时间字段保持不变
        User reloaded1 = repository.findById(user1.getId()).orElseThrow();
        assertThat(reloaded1.getPlanStartedAt()).isEqualTo(expectedStart1);
        assertThat(reloaded1.getPlanExpiresAt()).isEqualTo(expectedStart1.plusDays(365));
    }

    // ---------------- Property 26 ----------------

    private static final Set<String> VALID_PLANS = Set.of("free", "pro", "lifetime");
    private static final Set<String> VALID_ROLES = Set.of("user", "admin");

    /**
     * Feature: youyu-ledger, Property 26: 对任意对 {@code plan} 字段写入 free/pro/lifetime 之外的值，
     * 或对 {@code role} 字段写入 user/admin 之外的值的操作，系统都应拒绝该次写入、保留字段原有值不变，
     * 并返回取值非法的错误（ENUM_VALUE_INVALID）。
     *
     * <p>Validates: Requirements 9.3</p>
     */
    @Property(tries = 25)
    void property26_nonEnumPlanOrRoleWriteHasNoSideEffect(
            @ForAll("anyCodes") String planCode,
            @ForAll("anyCodes") String roleCode) {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        Clock clock = Clock.fixed(Instant.parse("2025-06-01T04:30:00Z"), ZONE);
        UserService userService = new UserService(repository, clock);

        // 预置一名初始用户（plan=free, role=user），并记录原始快照。
        User seed = new User();
        seed.setEmail("seed@example.com");
        seed.setNickname("seed");
        seed.setPlan(Plan.FREE);
        seed.setRole(Role.USER);
        LocalDateTime t0 = LocalDateTime.ofInstant(Instant.parse("2020-01-01T00:00:00Z"), ZONE);
        seed.setPlanStartedAt(t0);
        seed.setPlanExpiresAt(t0.plusDays(365));
        seed.setCreatedAt(t0);
        seed.setUpdatedAt(t0);
        User saved = repository.save(seed);
        Long userId = saved.getId();

        // ---- plan 写入 ----
        if (VALID_PLANS.contains(planCode)) {
            User updated = userService.updatePlan(userId, planCode);
            assertThat(updated.getPlan()).isEqualTo(Plan.fromCode(planCode));
        } else {
            ApiException ex = catchThrowableOfType(
                    () -> userService.updatePlan(userId, planCode), ApiException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("ENUM_VALUE_INVALID");
            assertThat(ex.getField()).isEqualTo("plan");
            // 零副作用：原有 plan 保持不变，且其它字段未被改动
            User after = repository.findById(userId).orElseThrow();
            assertThat(after.getPlan()).isEqualTo(Plan.FREE);
            assertThat(after.getRole()).isEqualTo(Role.USER);
            assertThat(after.getUpdatedAt()).isEqualTo(t0);
        }

        // ---- role 写入（以当前存储状态为基线）----
        User baseline = repository.findById(userId).orElseThrow();
        Plan planBefore = baseline.getPlan();
        Role roleBefore = baseline.getRole();
        LocalDateTime updatedAtBefore = baseline.getUpdatedAt();

        if (VALID_ROLES.contains(roleCode)) {
            User updated = userService.updateRole(userId, roleCode);
            assertThat(updated.getRole()).isEqualTo(Role.fromCode(roleCode));
            // plan 不受 role 写入影响
            assertThat(updated.getPlan()).isEqualTo(planBefore);
        } else {
            ApiException ex = catchThrowableOfType(
                    () -> userService.updateRole(userId, roleCode), ApiException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("ENUM_VALUE_INVALID");
            assertThat(ex.getField()).isEqualTo("role");
            // 零副作用：role/plan/updatedAt 全部保持基线不变
            User after = repository.findById(userId).orElseThrow();
            assertThat(after.getRole()).isEqualTo(roleBefore);
            assertThat(after.getPlan()).isEqualTo(planBefore);
            assertThat(after.getUpdatedAt()).isEqualTo(updatedAtBefore);
        }
    }
}
