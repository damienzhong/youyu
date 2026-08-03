package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatClient;
import com.damien.youyu.wechat.WxSession;

/**
 * 鉴权服务：无密码登录（邮箱验证码 / 微信一键）与令牌签发所依赖的业务逻辑。
 *
 * <p>关联需求：2、3、9.2。系统不存储、不校验任何登录密码，也不保留登录失败计数/锁定字段
 * （需求 4.3）。两种登录方式：</p>
 * <ul>
 *   <li>邮箱验证码（登录/注册合一）：见 {@link #emailLogin(String, String, String)}。</li>
 *   <li>微信一键登录：见 {@link #wxLogin(String, String)}。</li>
 * </ul>
 *
 * <h2>建号即绑定邀请关系（invite-system 需求 1.2、1.7、5.2、5.8）</h2>
 *
 * <p>两个登录方法都接收可选的邀请码入参并返回 {@link LoginOutcome}。三条实现约束：</p>
 * <ol>
 *   <li><b>建号路径只读一次时钟。</b>{@code LocalDateTime now = LocalDateTime.now(clock)} 在方法内
 *       取值一次，同一个 {@code now} 同时用于 {@code user.setCreatedAt(now)} 与邀请关系的
 *       {@code register_time}，从而使两者严格相等（时间差 0 毫秒，需求 5.8）。<b>不得</b>改用
 *       {@code NOW()}、数据库列默认值或第二次 {@code LocalDateTime.now(clock)}——那会让两个时刻在
 *       毫秒级上分叉，需求 5.8 的读库比对断言随即失败。</li>
 *   <li><b>建号与绑定必须在同一个 {@code @Transactional} 边界内</b>（需求 5.2）。
 *       {@link InviteBindingService#bindOnRegister} 声明为 {@code MANDATORY}，正是靠本方法的事务
 *       提供边界：新建用户与其邀请关系要么一起提交、要么一起回滚。</li>
 *   <li><b>邀请码 10 次全被占用即整体失败。</b>{@code generateUnique} 抛
 *       {@code INVITE_CODE_GEN_FAILED} 时异常穿出本方法，事务回滚、不签发令牌，
 *       库里不留 {@code invite_code} 为空的新用户行（需求 1.7）。</li>
 * </ol>
 *
 * <p>未绑定原因的优先级判定（{@code NO_CODE} → {@code NOT_NEW_USER} → …）全部收在
 * {@link InviteBindingService} 内，故非建号路径同样调用 {@code bindOnRegister}（传
 * {@code isNewUser = false}），而不是在这里就地返回 {@code NOT_NEW_USER}——否则老用户不带码登录会
 * 得到 {@code NOT_NEW_USER} 而非需求 5.11 要求的 {@code NO_CODE}。该调用对
 * {@code invite_relations} 不执行任何语句。</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final Clock clock;
    private final WeChatClient weChatClient;
    private final VerificationCodeService verificationCodeService;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final InviteBindingService inviteBindingService;

    public AuthService(
            UserRepository userRepository,
            Clock clock,
            WeChatClient weChatClient,
            VerificationCodeService verificationCodeService,
            InviteCodeGenerator inviteCodeGenerator,
            InviteBindingService inviteBindingService) {
        this.userRepository = userRepository;
        this.clock = clock;
        this.weChatClient = weChatClient;
        this.verificationCodeService = verificationCodeService;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.inviteBindingService = inviteBindingService;
    }

    /**
     * 邮箱验证码登录/注册合一（需求 2）。
     *
     * <p>先以 {@link EmailCodePurpose#LOGIN} 用途单次消费校验验证码：校验不通过直接抛出
     * {@code CODE_INVALID}，不签发任何令牌、不产生任何账号副作用（需求 2.2）。校验通过后按
     * {@code email} 定位账号：已存在则直接登录（需求 2.5）；不存在则创建新用户
     * （{@code email} 置该邮箱、{@code wx_openid} 为空、{@code nickname} 缺省取邮箱 @ 前缀，
     * 初始化 plan=free/role=user/plan_started_at=当前/plan_expires_at=+365 天，需求 2.4）。</p>
     *
     * <p>邮箱以去空白后的原值处理，与 {@link VerificationCodeService} 的规整方式保持一致。
     * 返回的用户由调用方签发 JWT，返回结构与微信登录一致。</p>
     *
     * <p>建号时一并生成全局唯一的 {@code invite_code} 并在同一事务内尝试绑定邀请关系
     * （见类注释「建号即绑定邀请关系」）。</p>
     *
     * @param rawInviteCode 可选的邀请码原始取值，可为 {@code null}
     * @throws ApiException CODE_INVALID(验证码错误/过期/已使用/超次失效)
     *                      / INVITE_CODE_GEN_FAILED(邀请码 10 次全被占用，事务回滚、不签发令牌)
     */
    @Transactional
    public LoginOutcome emailLogin(String rawEmail, String code, String rawInviteCode) {
        String email = rawEmail == null ? "" : rawEmail.trim();
        // 单次消费校验（需求 2.1/2.2）：不通过则零副作用、不签发令牌。
        if (!verificationCodeService.verifyConsume(email, EmailCodePurpose.LOGIN, code)) {
            throw ApiException.codeInvalid();
        }

        // 本次请求的唯一时刻取值：createdAt 与邀请关系 register_time 共用（invite 需求 5.8）。
        LocalDateTime now = LocalDateTime.now(clock);

        // 校验通过：按 email 查账号，有则登录（需求 2.5）。
        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            // 非建号路径：仍走 bindOnRegister 以取得正确的未绑定原因（NO_CODE / NOT_NEW_USER），
            // 该调用对 invite_relations 不执行任何语句（invite 需求 5.3、5.11）。
            return new LoginOutcome(existing,
                    inviteBindingService.bindOnRegister(existing, false, rawInviteCode, now), false);
        }

        // 无账号：登录/注册合一，创建纯邮箱用户（需求 2.4）。
        User user = new User();
        user.setEmail(email);
        user.setWxOpenid(null);
        // 昵称缺省取邮箱 @ 前缀（本地部分），仅用于展示。
        user.setNickname(defaultNickname(email));
        // 个人邀请码：10 次全被占用则抛错，整个登录事务回滚（invite 需求 1.2、1.7）。
        user.setInviteCode(inviteCodeGenerator.generateUnique(userRepository::existsByInviteCode));
        user.setPlan(Plan.FREE);
        user.setRole(Role.USER);
        user.setPlanStartedAt(now);
        user.setPlanExpiresAt(now.plusDays(365));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        User created = userRepository.save(user);

        // 同一事务内建立邀请关系；register_time 复用上面那个 now（invite 需求 5.2、5.8）。
        return new LoginOutcome(created,
                inviteBindingService.bindOnRegister(created, true, rawInviteCode, now), true);
    }

    /** 取邮箱 @ 之前的本地部分作为缺省昵称；无 @ 时回退为原邮箱串。 */
    private static String defaultNickname(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    /**
     * 微信小程序授权登录：用一次性 {@code code} 换取 openid，按 openid 找到或创建用户（需求 3）。
     *
     * <p>首次登录（openid 未知）自动创建一名"纯微信"用户：{@code wx_openid} 置该 openid、
     * {@code email} 为空、无密码，套餐/角色初始化与邮箱注册一致（需求 3.1）。已存在则在获得
     * 新 unionid 时补写（需求 3.2）。返回的用户由调用方签发 JWT，之后所有业务接口的鉴权与
     * 邮箱用户完全一致。</p>
     *
     * @param rawInviteCode 可选的邀请码原始取值，可为 {@code null}
     * @throws ApiException WX_CODE_REQUIRED(code 缺失) / WX_LOGIN_FAILED(换取 openid 失败)
     *                      / INVITE_CODE_GEN_FAILED(邀请码 10 次全被占用，事务回滚、不签发令牌)
     */
    @Transactional
    public LoginOutcome wxLogin(String rawCode, String rawInviteCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw ApiException.wxCodeRequired();
        }

        WxSession session = weChatClient.jscode2session(code);
        String openid = session.openid();
        // 本次请求的唯一时刻取值：createdAt 与邀请关系 register_time 共用（invite 需求 5.8）。
        LocalDateTime now = LocalDateTime.now(clock);

        User existing = userRepository.findByWxOpenid(openid).orElse(null);
        if (existing != null) {
            // 补写首次未下发、后续才获得的 unionid（需求 3.2）。
            User user = existing;
            if (existing.getWxUnionid() == null && session.unionid() != null) {
                existing.setWxUnionid(session.unionid());
                existing.setUpdatedAt(now);
                user = userRepository.save(existing);
            }
            // 非建号路径：见 emailLogin 中同一处注释（invite 需求 5.3、5.11）。
            return new LoginOutcome(user,
                    inviteBindingService.bindOnRegister(user, false, rawInviteCode, now), false);
        }

        User user = new User();
        user.setWxOpenid(openid);
        user.setWxUnionid(session.unionid());
        // 个人邀请码：10 次全被占用则抛错，整个登录事务回滚（invite 需求 1.2、1.7）。
        user.setInviteCode(inviteCodeGenerator.generateUnique(userRepository::existsByInviteCode));
        user.setPlan(Plan.FREE);
        user.setRole(Role.USER);
        user.setPlanStartedAt(now);
        user.setPlanExpiresAt(now.plusDays(365));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        User created = userRepository.save(user);

        // 同一事务内建立邀请关系；register_time 复用上面那个 now（invite 需求 5.2、5.8）。
        return new LoginOutcome(created,
                inviteBindingService.bindOnRegister(created, true, rawInviteCode, now), true);
    }

    /**
     * 将一个邮箱身份绑定到当前已登录账号（需求 5）。
     *
     * <p>执行顺序（刻意如此，兼顾"零副作用"与"不浪费验证码"）：</p>
     * <ol>
     *   <li>按 {@code userId} 定位当前会话用户；不存在按 {@code UNAUTHENTICATED} 处理（会话失效）。</li>
     *   <li>当前账号已绑定邮箱（非空白）→ {@code IDENTITY_ALREADY_BOUND}（需求 5.3）。此检查放在
     *       消费验证码之前，避免在明知会失败的绑定上白白消耗用户的验证码。</li>
     *   <li>以 {@link EmailCodePurpose#BIND} 单次消费校验验证码；不通过 → {@code CODE_INVALID}
     *       （需求 5.1 的反面）。</li>
     *   <li>目标邮箱已被"其它账号"持有 → {@code IDENTITY_TAKEN}，不修改任何账号（需求 5.2）。
     *       这里比较的是持有者 id 与当前 userId：若恰为当前账号自身则不构成冲突（但因步骤 2 已挡下
     *       "本账号已有邮箱"，正常不会走到自绑分支）。</li>
     *   <li>写入 email、更新 {@code updatedAt} 并保存（需求 5.1）。验证码在步骤 3 已单次消费（需求 5.4）。</li>
     * </ol>
     *
     * <p>邮箱以去空白后的原值处理，与 {@link VerificationCodeService} 规整方式一致。</p>
     *
     * @throws ApiException UNAUTHENTICATED(会话用户不存在) / IDENTITY_ALREADY_BOUND(本账号已绑邮箱)
     *                      / CODE_INVALID(验证码无效) / IDENTITY_TAKEN(邮箱被他人占用)
     */
    @Transactional
    public User bindEmail(Long userId, String rawEmail, String code) {
        User user = userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);

        // 1) 当前账号已绑定邮箱 → 拒绝（先于消费验证码，避免白白消耗，需求 5.3）。
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            throw ApiException.identityAlreadyBound();
        }

        String email = rawEmail == null ? "" : rawEmail.trim();

        // 2) 单次消费校验（purpose=BIND，需求 5.1/5.4）：不通过则零账号副作用。
        if (!verificationCodeService.verifyConsume(email, EmailCodePurpose.BIND, code)) {
            throw ApiException.codeInvalid();
        }

        // 3) 目标邮箱被"其它账号"占用 → 拒绝，不修改任何账号（需求 5.2）。
        User holder = userRepository.findByEmail(email).orElse(null);
        if (holder != null && !holder.getId().equals(userId)) {
            throw ApiException.identityTaken();
        }

        // 4) 写入身份（需求 5.1）。
        user.setEmail(email);
        user.setUpdatedAt(LocalDateTime.now(clock));
        return userRepository.save(user);
    }

    /**
     * 将微信身份绑定到当前已登录账号（需求 6）。
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>按 {@code userId} 定位当前会话用户；不存在按 {@code UNAUTHENTICATED} 处理。</li>
     *   <li>当前账号已绑定微信（{@code wx_openid} 非空）→ {@code IDENTITY_ALREADY_BOUND}（需求 6.3）。</li>
     *   <li>用一次性 {@code code} 换取 openid：缺失 → {@code WX_CODE_REQUIRED}，换取失败 →
     *       {@code WX_LOGIN_FAILED}（与 {@link #wxLogin(String)} 一致，需求 6.4）。</li>
     *   <li>该 openid 已被"其它账号"持有 → {@code IDENTITY_TAKEN}，不修改任何账号（需求 6.2）。</li>
     *   <li>写入 {@code wx_openid}（及 unionid）、更新 {@code updatedAt} 并保存（需求 6.1）。</li>
     * </ol>
     *
     * @throws ApiException UNAUTHENTICATED / IDENTITY_ALREADY_BOUND / WX_CODE_REQUIRED
     *                      / WX_LOGIN_FAILED / IDENTITY_TAKEN
     */
    @Transactional
    public User bindWechat(Long userId, String rawCode) {
        User user = userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);

        // 1) 当前账号已绑定微信 → 拒绝（需求 6.3）。
        if (user.getWxOpenid() != null && !user.getWxOpenid().isBlank()) {
            throw ApiException.identityAlreadyBound();
        }

        // 2) 换取 openid（缺失/失败与 wxLogin 行为一致，需求 6.4）。
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw ApiException.wxCodeRequired();
        }
        WxSession session = weChatClient.jscode2session(code);
        String openid = session.openid();

        // 3) 该 openid 被"其它账号"占用 → 拒绝，不修改任何账号（需求 6.2）。
        User holder = userRepository.findByWxOpenid(openid).orElse(null);
        if (holder != null && !holder.getId().equals(userId)) {
            throw ApiException.identityTaken();
        }

        // 4) 写入身份（含 unionid，需求 6.1）。
        user.setWxOpenid(openid);
        if (session.unionid() != null) {
            user.setWxUnionid(session.unionid());
        }
        user.setUpdatedAt(LocalDateTime.now(clock));
        return userRepository.save(user);
    }

    /**
     * 解绑当前已登录账号的某一登录身份（需求 7），并保底「至少一种登录方式」。
     *
     * <p>执行顺序：</p>
     * <ol>
     *   <li>按 {@code userId} 定位当前会话用户；不存在按 {@code UNAUTHENTICATED} 处理（会话失效）。</li>
     *   <li>规整并校验 {@code type}（大小写不敏感，去空白）：为空/空白 → {@code FIELD_REQUIRED(type)}；
     *       取值非 {@code "email"}/{@code "wechat"} → 同样以 {@code FIELD_REQUIRED(type)} 拒绝
     *       （明确的 400，避免暴露账号信息；见下方"类型校验"说明）。</li>
     *   <li>推演清除目标身份后的剩余身份：若解绑后账号将既无 email 也无 wx_openid（失去全部登录身份）
     *       → {@code LAST_LOGIN_METHOD}，不做任何修改（需求 7.2）。</li>
     *   <li>清除目标字段（email 置空；wechat 则 wx_openid 与 wx_unionid 一并置空），更新 {@code updatedAt}
     *       并保存（需求 7.1）。被清除的身份由此立即释放，可被其它账号绑定/注册（需求 7.3）。</li>
     * </ol>
     *
     * <p>类型校验：本方法对 {@code null}/空白与不可识别取值均返回 {@code FIELD_REQUIRED(type)}，
     * 统一映射为 400，语义清晰且实现简单；不使用 NOT_FOUND，因为这属于请求参数问题而非资源缺失。</p>
     *
     * @param userId 当前会话用户 id
     * @param type   待解绑身份类型：{@code "email"} 或 {@code "wechat"}（大小写不敏感）
     * @throws ApiException UNAUTHENTICATED(会话用户不存在) / FIELD_REQUIRED(type 为空或非法)
     *                      / LAST_LOGIN_METHOD(解绑后将失去唯一登录方式)
     */
    @Transactional
    public User unbind(Long userId, String type) {
        User user = userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);

        // 1) 规整并校验 type（大小写不敏感）。
        String normalized = type == null ? "" : type.trim().toLowerCase();
        if (normalized.isEmpty()) {
            throw ApiException.fieldRequired("type");
        }
        boolean unbindEmail;
        if ("email".equals(normalized)) {
            unbindEmail = true;
        } else if ("wechat".equals(normalized)) {
            unbindEmail = false;
        } else {
            // 不可识别的类型：明确的 400（需求 7 未定义其它身份类型）。
            throw ApiException.fieldRequired("type");
        }

        // 2) 推演清除目标身份后的剩余身份，保底「至少一种登录方式」（需求 7.2）。
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        boolean hasWechat = user.getWxOpenid() != null && !user.getWxOpenid().isBlank();
        boolean remainingEmail = unbindEmail ? false : hasEmail;
        boolean remainingWechat = unbindEmail ? hasWechat : false;
        if (!remainingEmail && !remainingWechat) {
            throw ApiException.lastLoginMethod();
        }

        // 3) 清除目标身份（需求 7.1），释放供复用（需求 7.3）。
        if (unbindEmail) {
            user.setEmail(null);
        } else {
            user.setWxOpenid(null);
            user.setWxUnionid(null);
        }
        user.setUpdatedAt(LocalDateTime.now(clock));
        return userRepository.save(user);
    }

    /**
     * 修改当前账号昵称（需求 4.4：昵称仅展示，可改，不用于登录）。
     *
     * <p>去空白后校验长度 1-64；非法则 {@code NICKNAME_INVALID}，不修改。昵称可重复，无唯一性约束。</p>
     *
     * @param userId      当前会话用户 id
     * @param rawNickname 新昵称
     * @throws ApiException UNAUTHENTICATED(会话用户不存在) / NICKNAME_INVALID(去空白后为空或超 64)
     */
    @Transactional
    public User updateNickname(Long userId, String rawNickname) {
        User user = userRepository.findById(userId).orElseThrow(ApiException::unauthenticated);
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        if (nickname.isEmpty() || nickname.length() > 64) {
            throw ApiException.nicknameInvalid();
        }
        user.setNickname(nickname);
        user.setUpdatedAt(LocalDateTime.now(clock));
        return userRepository.save(user);
    }
}
