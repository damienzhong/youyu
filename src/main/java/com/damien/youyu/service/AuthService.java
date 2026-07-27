package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

/**
 * 鉴权服务：用户注册、登录、账号锁定与令牌签发所依赖的业务逻辑。
 *
 * <p>关联需求：1.1-1.10、9.2。</p>
 * <ul>
 *   <li>注册：去空白后校验账号标识(1-64)与口令(8-64)，必填/占用校验；口令以 BCrypt 加盐哈希存储；
 *       初始化 plan=free、role=user、plan_started_at=注册时刻、plan_expires_at=+365 天(365×24h)。</li>
 *   <li>登录：校验凭证；连续失败计数，达到 {@code maxFailedAttempts} 次后锁定 {@code lockDurationMinutes} 分钟；
 *       锁定期内即使凭证正确亦拒绝；登录成功清零计数与锁定。</li>
 * </ul>
 */
@Service
public class AuthService {

    /** 账号标识去空白后允许的最大长度。 */
    static final int USERNAME_MAX = 64;
    /** 口令允许的长度区间。 */
    static final int PASSWORD_MIN = 8;
    static final int PASSWORD_MAX = 64;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final WeChatClient weChatClient;
    private final int maxFailedAttempts;
    private final int lockDurationMinutes;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            WeChatClient weChatClient,
            @Value("${app.auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${app.auth.lock-duration-minutes:15}") int lockDurationMinutes) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.weChatClient = weChatClient;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDurationMinutes = lockDurationMinutes;
    }

    /**
     * 注册一名新用户。
     *
     * @throws ApiException FIELD_REQUIRED(必填缺失) / USERNAME_INVALID(账号长度非法) /
     *                      PASSWORD_WEAK(口令长度非法) / USERNAME_TAKEN(账号占用)
     */
    @Transactional
    public User register(String rawUsername, String rawPassword) {
        // 1) 必填校验（需求 1.4）：账号标识去空白后为空、或口令为空即拒绝。
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (username.isEmpty()) {
            throw ApiException.fieldRequired("username");
        }
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw ApiException.fieldRequired("password");
        }
        // 2) 账号标识长度（需求 1.1：去空白后 1-64）。
        if (username.length() > USERNAME_MAX) {
            throw ApiException.usernameInvalid();
        }
        // 3) 口令长度（需求 1.3：8-64）。
        if (rawPassword.length() < PASSWORD_MIN || rawPassword.length() > PASSWORD_MAX) {
            throw ApiException.passwordWeak();
        }
        // 4) 账号占用（需求 1.2）。
        if (userRepository.existsByUsername(username)) {
            throw ApiException.usernameTaken();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        User user = new User();
        user.setUsername(username);
        // 需求 1.8：口令以 BCrypt 加盐哈希存储（盐内嵌于哈希）。
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        // 需求 1.9/1.10/9.2：初始化套餐与角色，套餐到期为注册时刻 + 精确 365×24h。
        user.setPlan(Plan.FREE);
        user.setRole(Role.USER);
        user.setPlanStartedAt(now);
        user.setPlanExpiresAt(now.plusDays(365));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user);
    }

    /**
     * 校验登录凭证并处理失败计数/锁定。校验成功返回对应用户；调用方据此签发令牌。
     *
     * @throws ApiException ACCOUNT_LOCKED(锁定期内) / BAD_CREDENTIALS(账号或口令错误)
     */
    @Transactional
    public User login(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        // 不区分"用户不存在"与"口令错误"，统一返回凭证错误，避免账号枚举（需求 1.6）。
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            throw ApiException.badCredentials();
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 锁定期内：即使凭证正确也拒绝（需求 1.7）。
        if (isLocked(user, now)) {
            throw ApiException.accountLocked();
        }

        // 锁定窗口已过：重置计数与锁定，允许本次重新计数。
        if (user.getLockedUntil() != null) {
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
        }

        boolean matches = rawPassword != null
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());
        if (!matches) {
            int failures = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(failures);
            if (failures >= maxFailedAttempts) {
                // 达到阈值：锁定其后一段窗口，期间任何尝试都将被拒绝。
                user.setLockedUntil(now.plusMinutes(lockDurationMinutes));
            }
            user.setUpdatedAt(now);
            userRepository.save(user);
            throw ApiException.badCredentials();
        }

        // 成功：清零失败计数与锁定（需求 1.5/1.7）。
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(now);
        return userRepository.save(user);
    }

    /**
     * 微信小程序授权登录：用一次性 {@code code} 换取 openid，按 openid 找到或创建用户。
     *
     * <p>首次登录（openid 未知）自动创建一名"纯微信"用户：无登录名与口令，套餐/角色初始化
     * 与账号密码注册一致。已存在则在有新 unionid 时补写。返回的用户由调用方签发 JWT，
     * 之后所有业务接口的鉴权与账号密码用户完全一致。</p>
     *
     * @throws ApiException WX_CODE_REQUIRED(code 缺失) / WX_LOGIN_FAILED(换取 openid 失败)
     */
    @Transactional
    public User wxLogin(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw ApiException.wxCodeRequired();
        }

        WxSession session = weChatClient.jscode2session(code);
        String openid = session.openid();
        LocalDateTime now = LocalDateTime.now(clock);

        User existing = userRepository.findByWxOpenid(openid).orElse(null);
        if (existing != null) {
            // 补写首次未下发、后续才获得的 unionid。
            if (existing.getWxUnionid() == null && session.unionid() != null) {
                existing.setWxUnionid(session.unionid());
                existing.setUpdatedAt(now);
                return userRepository.save(existing);
            }
            return existing;
        }

        User user = new User();
        user.setUsername(null);
        user.setPasswordHash(null);
        user.setWxOpenid(openid);
        user.setWxUnionid(session.unionid());
        user.setPlan(Plan.FREE);
        user.setRole(Role.USER);
        user.setPlanStartedAt(now);
        user.setPlanExpiresAt(now.plusDays(365));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user);
    }

    private boolean isLocked(User user, LocalDateTime now) {
        return user.getLockedUntil() != null && now.isBefore(user.getLockedUntil());
    }
}
