package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.support.InMemoryUserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * AuthService 的属性测试（jqwik），覆盖设计文档 Correctness Properties 中的
 * Property 22（口令长度校验）、Property 23（登录失败锁定）、Property 24（口令加盐哈希）。
 *
 * <p>每个属性至少运行 100 次随机迭代。使用真实的 {@link BCryptPasswordEncoder}（测试用低强度以保证
 * 速度，仍是真实加盐哈希）与真实的 {@link InMemoryUserRepository} 存储实现，被测的鉴权业务逻辑全部
 * 真实执行，不使用 mock。时间以固定 {@link Clock} 注入以获得确定性。</p>
 *
 * <p>关联需求：1.3、1.7、1.8。</p>
 */
class AuthPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // = 2025-06-01T12:30 +08:00；所有登录尝试都发生在该时刻，落在 15 分钟锁定窗口内。
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;

    // 测试用 BCrypt 强度设为 4（默认 10），仅为在数百次迭代下保持速度；加盐语义与真实一致。
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private InMemoryUserRepository repository;

    private AuthService newService() {
        repository = new InMemoryUserRepository();
        return new AuthService(repository, encoder, Clock.fixed(T0, ZONE), null,
                MAX_FAILED_ATTEMPTS, LOCK_MINUTES);
    }

    // ---------------- 生成器 ----------------

    /** 合法账号标识：小写字母 1-64，去空白后仍在 1-64 范围内。 */
    @Provide
    Arbitrary<String> validUsernames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(64);
    }

    /** 合法口令：长度 8-64。 */
    @Provide
    Arbitrary<String> validPasswords() {
        return Arbitraries.strings().withCharRange('!', '~').ofMinLength(8).ofMaxLength(64);
    }

    /** 任意长度口令（0-80），用于覆盖合法与非法两个分支。 */
    @Provide
    Arbitrary<String> anyLengthPasswords() {
        return Arbitraries.strings().withCharRange('!', '~').ofMinLength(0).ofMaxLength(80);
    }

    // ---------------- 属性 ----------------

    /**
     * Feature: youyu-ledger, Property 22: 对任意注册请求，当口令长度小于 8 或大于 64 个字符时应被拒绝、
     * 不创建任何用户；当账号标识与口令均满足长度约束时应被接受。
     */
    @Property(tries = 200)
    void property22_passwordLengthValidation(
            @ForAll("validUsernames") String username,
            @ForAll("anyLengthPasswords") String password) {
        AuthService service = newService();
        int len = password.length();
        boolean shouldAccept = len >= 8 && len <= 64;

        if (shouldAccept) {
            User created = service.register(username, password);
            assertThat(created.getId()).isNotNull();
            assertThat(repository.count()).isEqualTo(1);
        } else {
            // 长度 < 8（含空口令）或 > 64：一律拒绝且不创建任何用户。
            ApiException ex = catchThrowableOfType(
                    () -> service.register(username, password), ApiException.class);
            assertThat(ex).isNotNull();
            assertThat(repository.count()).isZero();
        }
    }

    /**
     * Feature: youyu-ledger, Property 23: 对任意账号，连续登录失败达到 5 次后，在其后 15 分钟内的任何
     * 登录尝试（即使凭证正确）都应被拒绝并提示账号临时锁定；失败次数不足 5 次时不触发锁定。
     */
    @Property(tries = 100)
    void property23_loginFailureLockout(
            @ForAll("validUsernames") String username,
            @ForAll("validPasswords") String password,
            @ForAll @IntRange(min = 0, max = 8) int failures) {
        AuthService service = newService();
        service.register(username, password);
        // 保证错误口令与正确口令不同（登录仅比对哈希，不做长度校验）。
        String wrongPassword = password + "#WRONG";

        for (int i = 0; i < failures; i++) {
            // 前 5 次前抛 BAD_CREDENTIALS，达到阈值后抛 ACCOUNT_LOCKED；两者都是拒绝。
            catchThrowableOfType(() -> service.login(username, wrongPassword), ApiException.class);
        }

        if (failures >= MAX_FAILED_ATTEMPTS) {
            // 已锁定：锁定窗口内即使凭证正确也被拒绝。
            ApiException ex = catchThrowableOfType(
                    () -> service.login(username, password), ApiException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("ACCOUNT_LOCKED");
        } else {
            // 未达阈值：不锁定，正确凭证可成功并清零计数。
            User user = service.login(username, password);
            assertThat(user.getFailedLoginCount()).isZero();
            assertThat(user.getLockedUntil()).isNull();
        }
    }

    /**
     * Feature: youyu-ledger, Property 24: 对任意口令，其持久化存储值都应不等于明文、能通过口令校验通过，
     * 且相同明文口令为不同用户存储时产生不同的哈希值（体现加盐）。
     */
    @Property(tries = 100)
    void property24_passwordSaltedHash(
            @ForAll("validPasswords") String password,
            @ForAll("validUsernames") String username1,
            @ForAll("validUsernames") String username2) {
        Assume.that(!username1.equals(username2));
        AuthService service = newService();

        User user1 = service.register(username1, password);
        User user2 = service.register(username2, password);

        // 存储的不是明文，且能通过校验。
        assertThat(user1.getPasswordHash()).isNotEqualTo(password);
        assertThat(user2.getPasswordHash()).isNotEqualTo(password);
        assertThat(encoder.matches(password, user1.getPasswordHash())).isTrue();
        assertThat(encoder.matches(password, user2.getPasswordHash())).isTrue();
        // 加盐：相同明文不同用户，哈希不同。
        assertThat(user1.getPasswordHash()).isNotEqualTo(user2.getPasswordHash());
    }
}
