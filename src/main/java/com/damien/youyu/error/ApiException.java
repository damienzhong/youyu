package com.damien.youyu.error;

import org.springframework.http.HttpStatus;

/**
 * 领域异常：携带统一错误码、HTTP 状态与可选的出错字段。
 *
 * <p>统一错误体格式 {@code {code, message, field}} 见设计文档「错误处理策略」。
 * 本类在鉴权任务(3.1)中引入，配合 {@link GlobalExceptionHandler} 提供最小可用的错误映射；
 * 完整的全局异常处理与错误码表在任务 12.1 中集中完善，本类保持向前兼容。</p>
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final String field;

    public ApiException(String code, HttpStatus status, String message, String field) {
        super(message);
        this.code = code;
        this.status = status;
        this.field = field;
    }

    public ApiException(String code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getField() {
        return field;
    }

    // ---- 常用工厂方法（Auth 域） ----

    /** 必填项缺失（账号标识或口令为空）。 */
    public static ApiException fieldRequired(String field) {
        return new ApiException("FIELD_REQUIRED", HttpStatus.BAD_REQUEST, "必填项缺失", field);
    }

    /** 账号标识非法（去空白后长度不在 1-64）。 */
    public static ApiException usernameInvalid() {
        return new ApiException("USERNAME_INVALID", HttpStatus.BAD_REQUEST,
                "账号标识长度需为 1 到 64 个字符", "username");
    }

    /** 账号已被占用。 */
    public static ApiException usernameTaken() {
        return new ApiException("USERNAME_TAKEN", HttpStatus.CONFLICT, "账号已存在", "username");
    }

    /** 口令强度不足（长度不在 8-64）。 */
    public static ApiException passwordWeak() {
        return new ApiException("PASSWORD_WEAK", HttpStatus.BAD_REQUEST,
                "密码长度需为 8 到 64 个字符", "password");
    }

    /** 账号或口令错误。 */
    public static ApiException badCredentials() {
        return new ApiException("BAD_CREDENTIALS", HttpStatus.UNAUTHORIZED, "账号或密码错误", null);
    }

    /** 账号被临时锁定。 */
    public static ApiException accountLocked() {
        return new ApiException("ACCOUNT_LOCKED", HttpStatus.LOCKED,
                "账号已被临时锁定，请稍后再试", null);
    }

    /** 账本名称非法（去空白后为空或长度超过 50）。 */
    public static ApiException ledgerNameInvalid() {
        return new ApiException("LEDGER_NAME_INVALID", HttpStatus.BAD_REQUEST,
                "账本名称长度需为 1 到 50 个字符", "name");
    }

    /** 不可删除最后一个账本（每个用户至少保留一个账本）。 */
    public static ApiException ledgerLastOne() {
        return new ApiException("LEDGER_LAST_ONE", HttpStatus.CONFLICT,
                "至少保留一个账本，不能删除最后一个", null);
    }

    /** 仅账本 OWNER 可执行该操作（改名/删除/邀请/移除成员）。 */
    public static ApiException ledgerForbidden() {
        return new ApiException("LEDGER_FORBIDDEN", HttpStatus.FORBIDDEN,
                "只有账本创建者可执行该操作", null);
    }

    /**
     * 指定的当前账本不可访问（不存在或当前用户非其成员）。用于 {@code X-Ledger-Id} 头解析失败时，
     * 让客户端据此清除本地过期账本并回退默认账本（例如账本被删、退出协作账本、或换环境后 id 失效）。
     */
    public static ApiException ledgerNotAccessible() {
        return new ApiException("LEDGER_NOT_ACCESSIBLE", HttpStatus.NOT_FOUND,
                "当前账本不存在或无权访问", null);
    }

    /** 该操作仅适用于协作账本或 AA 账本（个人账本无成员语义）。 */
    public static ApiException ledgerNotCollaborative() {
        return new ApiException("LEDGER_NOT_COLLABORATIVE", HttpStatus.BAD_REQUEST,
                "只有协作账本或 AA 账本可以邀请成员", null);
    }

    /** 邀请码无效或已过期。 */
    public static ApiException inviteInvalid() {
        return new ApiException("INVITE_INVALID", HttpStatus.BAD_REQUEST,
                "邀请码无效或已过期", "code");
    }

    /** 不能移除账本创建者（OWNER）。 */
    public static ApiException memberOwnerImmutable() {
        return new ApiException("MEMBER_OWNER_IMMUTABLE", HttpStatus.CONFLICT,
                "不能移除账本创建者", null);
    }

    /** 微信登录 code 缺失或为空。 */
    public static ApiException wxCodeRequired() {
        return new ApiException("WX_CODE_REQUIRED", HttpStatus.BAD_REQUEST,
                "缺少微信登录凭证 code", "code");
    }

    /** 微信登录失败：code 无效/过期，或换取 openid 时被微信拒绝。 */
    public static ApiException wxLoginFailed(String message) {
        return new ApiException("WX_LOGIN_FAILED", HttpStatus.UNAUTHORIZED,
                message == null ? "微信登录失败，请重试" : message, null);
    }

    /** 未认证（缺少/无效令牌）。 */
    public static ApiException unauthenticated() {
        return new ApiException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "未认证", null);
    }

    /** 性别取值非法（仅接受 MALE/FEMALE/空）。 */
    public static ApiException genderInvalid() {
        return new ApiException("GENDER_INVALID", HttpStatus.BAD_REQUEST,
                "性别取值仅支持 MALE / FEMALE 或留空", "gender");
    }

    /** 头像颜色非法（需为 #RRGGBB 十六进制或空）。 */
    public static ApiException avatarColorInvalid() {
        return new ApiException("AVATAR_COLOR_INVALID", HttpStatus.BAD_REQUEST,
                "头像颜色需为 #RRGGBB 格式", "avatarColor");
    }

    /** 昵称非法（去空白后为空或长度超过 64）。 */
    public static ApiException nicknameInvalid() {
        return new ApiException("NICKNAME_INVALID", HttpStatus.BAD_REQUEST,
                "昵称长度需为 1 到 64 个字符", "nickname");
    }

    // ---- 常用工厂方法（邮箱验证码 / 身份 / 注销域） ----
    // 无密码鉴权（邮箱验证码 + 身份绑定/解绑 + 账号注销）相关的统一错误码，
    // 对应设计文档「错误处理策略」。失败一律零副作用（不改账号、不签发令牌）。

    /** 邮箱格式非法（正则校验未通过），不发送邮件（需求 1.1）。 */
    public static ApiException emailInvalid() {
        return new ApiException("EMAIL_INVALID", HttpStatus.BAD_REQUEST,
                "邮箱格式不正确", "email");
    }

    /** 同一 (email, purpose) 仍处于发码冷却期，拒绝再次发送（需求 1.3）。 */
    public static ApiException codeCooldown() {
        return new ApiException("CODE_COOLDOWN", HttpStatus.TOO_MANY_REQUESTS,
                "验证码发送过于频繁，请稍后再试", null);
    }

    /** 同一来源 IP 的发码请求超过每分钟/每日上限（需求 1.4）。 */
    public static ApiException codeRateLimited() {
        return new ApiException("CODE_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                "请求过于频繁，请稍后再试", null);
    }

    /** 邮件发送失败（SMTP 异常），不得以成功状态返回（需求 1.5）。 */
    public static ApiException emailSendFailed() {
        return new ApiException("EMAIL_SEND_FAILED", HttpStatus.BAD_GATEWAY,
                "验证码邮件发送失败，请稍后重试", null);
    }

    /** 验证码错误/过期/已使用/超次失效（需求 2.2、2.3）。 */
    public static ApiException codeInvalid() {
        return new ApiException("CODE_INVALID", HttpStatus.BAD_REQUEST,
                "验证码错误或已失效", "code");
    }

    /** 目标身份（email 或 wx_openid）已被其它账号占用，绑定被拒且零副作用（需求 5.2、6.2）。 */
    public static ApiException identityTaken() {
        return new ApiException("IDENTITY_TAKEN", HttpStatus.CONFLICT,
                "该身份已被其它账号占用", null);
    }

    /**
     * 当前账号已绑定该类身份（一个账号至多一个 email、至多一个 wx_openid，换绑需先解绑，
     * 需求 5.3、6.3）。
     */
    public static ApiException identityAlreadyBound() {
        return new ApiException("IDENTITY_ALREADY_BOUND", HttpStatus.CONFLICT,
                "当前账号已绑定该类身份，换绑请先解绑", null);
    }

    /**
     * 解绑将使账号失去唯一登录方式（解绑后既无 email 也无 wx_openid，违背「至少一种登录方式」，
     * 需求 7.2）。解绑被拒且账号身份保持不变。
     */
    public static ApiException lastLoginMethod() {
        return new ApiException("LAST_LOGIN_METHOD", HttpStatus.CONFLICT,
                "至少保留一种登录方式，不能解绑最后一种身份", null);
    }

    /**
     * 注销存在协作牵连，需先处理再注销（需求 8.2）：注销者拥有仍有其他成员的协作账本，或其账户被
     * 其它人的流水引用（协作场景，删除会孤立他人数据）。注销被拒且零副作用。
     */
    public static ApiException deleteBlockedCollab() {
        return new ApiException("DELETE_BLOCKED_COLLAB", HttpStatus.CONFLICT,
                "账号存在协作牵连（协作账本仍有其他成员，或账户被他人流水引用），"
                        + "请先转交/删除相关账本或处理引用后再注销", null);
    }

    // ---- 常用工厂方法（Account 域） ----

    /** 账户字段非法（名称/类型/初始余额），携带具体无效字段（需求 3.3）。 */
    public static ApiException accountFieldInvalid(String field, String message) {
        return new ApiException("ACCOUNT_FIELD_INVALID", HttpStatus.BAD_REQUEST, message, field);
    }

    /** 账户仍关联交易，不可删除（需求 3.7）。 */
    public static ApiException accountInUse() {
        return new ApiException("ACCOUNT_IN_USE", HttpStatus.CONFLICT,
                "该账户存在交易记录，无法删除", null);
    }

    /** 资源不存在（含越权访问他人资源时的不泄漏内容返回，需求 2.4、4.9）。 */
    public static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", HttpStatus.NOT_FOUND, message, null);
    }

    // ---- 常用工厂方法（Category 域） ----

    /** 分类名称非法（去空白后为空或长度超过 50，需求 5.7）。 */
    public static ApiException categoryNameInvalid() {
        return new ApiException("CATEGORY_NAME_INVALID", HttpStatus.BAD_REQUEST,
                "分类名称长度需为 1 到 50 个字符", "name");
    }

    /** 分类层级超过两级（在子分类下再建下级，需求 5.3）。 */
    public static ApiException categoryDepthExceeded() {
        return new ApiException("CATEGORY_DEPTH_EXCEEDED", HttpStatus.BAD_REQUEST,
                "分类层级最多两级", "parentId");
    }

    /** 同一 kind、同一父级范围内分类名称重复（需求 5.8）。 */
    public static ApiException categoryNameDuplicate() {
        return new ApiException("CATEGORY_NAME_DUPLICATE", HttpStatus.CONFLICT,
                "该名称已存在", "name");
    }

    /** 分类仍被交易引用，不可删除（需求 5.5）。 */
    public static ApiException categoryInUse() {
        return new ApiException("CATEGORY_IN_USE", HttpStatus.CONFLICT,
                "该分类仍在使用，无法删除", null);
    }

    /** 分类仍含子分类，不可删除（需求 5.9）。 */
    public static ApiException categoryHasChildren() {
        return new ApiException("CATEGORY_HAS_CHILDREN", HttpStatus.CONFLICT,
                "该分类仍含子分类，无法删除", null);
    }

    // ---- 常用工厂方法（Transaction 域） ----

    /** 交易金额非法（&lt;0.01、&gt;上限或小数位超过 2 位，需求 4.4）。 */
    public static ApiException amountInvalid() {
        return new ApiException("AMOUNT_INVALID", HttpStatus.BAD_REQUEST,
                "金额必须在 0.01 到 9,999,999,999,999,999.99 之间且最多两位小数", "amount");
    }

    /** 转账源账户与目标账户相同（需求 4.5）。 */
    public static ApiException transferSameAccount() {
        return new ApiException("TRANSFER_SAME_ACCOUNT", HttpStatus.BAD_REQUEST,
                "转账的源账户与目标账户不可相同", "destinationAccountId");
    }

    /** 转账在更新账户余额过程中失败，整事务已回滚（需求 4.10）。 */
    public static ApiException transferFailed() {
        return new ApiException("TRANSFER_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                "转账失败，未产生任何余额变更", null);
    }

    // ---- 变更流水归属账本 ----
    // 三条约束都是「宁可拒绝也不留跨账本悬空引用」：分类 / 项目 / 商家 / 标签均为账本级实体，
    // 账户经 account_ledger 挂到账本，AA 流水还带 transaction_splits 与净额口径。
    // 分类不属目标账本的情形复用既有 NOT_FOUND（"分类不存在"），不另设码。

    /**
     * 该流水的类型不支持更换账本：仅普通收支（expense/income）可换。
     * 转账与余额调整本就脱离账本（{@code ledger_id} 为空）；AA 流水（aa_expense/aa_settlement）
     * 带分摊明细与结算净额，迁出会让分摊行成孤儿并使 AA 退出 / 归档的净额闸门失效。
     */
    public static ApiException transactionLedgerChangeNotSupported() {
        return new ApiException("TRANSACTION_LEDGER_CHANGE_NOT_SUPPORTED", HttpStatus.CONFLICT,
                "只有普通收支可以更换账本", "ledgerId");
    }

    /**
     * 目标账本不能接收流水：AA 账本只接收 AA 分摊流水（且不设预算），已归档账本为只读。
     */
    public static ApiException ledgerCannotReceiveTransaction() {
        return new ApiException("LEDGER_CANNOT_RECEIVE_TRANSACTION", HttpStatus.BAD_REQUEST,
                "目标账本无法接收这笔流水（AA 账本或已归档账本）", "ledgerId");
    }

    /**
     * 所选账户未加入目标账本。刻意拒绝而非放行：账户经 {@code account_ledger} 与账本关联，
     * 若流水迁到未纳入该账户的账本，此后它的修改 / 删除 / 恢复都会在账户加锁阶段失败，
     * 余额将再也无法回滚。提示用户先把账户加入目标账本，或改选目标账本里的账户。
     */
    public static ApiException accountNotInLedger() {
        return new ApiException("ACCOUNT_NOT_IN_LEDGER", HttpStatus.BAD_REQUEST,
                "所选账户未加入目标账本，请先在账本设置里加入该账户，或改选目标账本中的账户", "accountId");
    }

    /**
     * 只能变更自己记录的流水所属账本。
     *
     * <p>协作账本的成员之间本就可以互相编辑流水，但「换账本」比「编辑」更强：它能把一笔流水
     * 移出协作账本、搬进操作者的私人账本，其余成员从此再也看不到这笔账。因此迁移这一动作
     * 收紧到记账人本人，编辑其余字段的既有权限不变。</p>
     */
    public static ApiException transactionLedgerChangeForbidden() {
        return new ApiException("TRANSACTION_LEDGER_CHANGE_FORBIDDEN", HttpStatus.FORBIDDEN,
                "只能变更自己记录的流水所属账本", "ledgerId");
    }

    // ---- 常用工厂方法（Report 域） ----

    /** 报表月份区间非法（跨度超过 24 个自然月，或起始月份晚于结束月份，需求 7.6）。 */
    public static ApiException reportRangeInvalid() {
        return new ApiException("REPORT_RANGE_INVALID", HttpStatus.BAD_REQUEST,
                "时间范围无效：起始不得晚于结束，且月度趋势区间不得超过 24 个自然月", null);
    }

    /** 报表查询参数格式非法（如 month/from/to 无法解析），携带具体字段。 */
    public static ApiException reportParamInvalid(String field, String message) {
        return new ApiException("REPORT_PARAM_INVALID", HttpStatus.BAD_REQUEST, message, field);
    }

    // ---- 常用工厂方法（Export 域） ----

    /**
     * 导出过程发生失败：不提供任何部分文件，不改动既有数据（需求 8.6）。
     */
    public static ApiException exportFailed() {
        return new ApiException("EXPORT_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                "导出失败，未生成任何文件", null);
    }

    /** 导出格式非法（仅支持 csv/json）。 */
    public static ApiException exportFormatUnsupported() {
        return new ApiException("EXPORT_FORMAT_UNSUPPORTED", HttpStatus.BAD_REQUEST,
                "导出格式仅支持 csv 或 json", "format");
    }

    /**
     * 导入文档结构非法（无法解析为 JSON、缺少必要字段、引用键无法解析或字段值不合法，需求 8.5）。
     */
    public static ApiException importInvalid(String message) {
        return new ApiException("IMPORT_INVALID", HttpStatus.BAD_REQUEST, message, null);
    }

    /** 导入过程发生失败：整事务回滚，不产生任何部分数据（需求 8.5）。 */
    public static ApiException importFailed() {
        return new ApiException("IMPORT_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                "导入失败，未还原任何数据", null);
    }

    // ---- 常用工厂方法（Budget 域） ----

    /** 预算金额非法（&lt;0.01、&gt;上限或小数位超过 2 位）。 */
    public static ApiException budgetAmountInvalid() {
        return new ApiException("BUDGET_AMOUNT_INVALID", HttpStatus.BAD_REQUEST,
                "预算金额必须在 0.01 到 9,999,999,999,999,999.99 之间且最多两位小数", "amount");
    }

    /** 预算月份格式非法（应为 YYYY-MM）。 */
    public static ApiException budgetMonthInvalid() {
        return new ApiException("BUDGET_MONTH_INVALID", HttpStatus.BAD_REQUEST,
                "月份格式应为 YYYY-MM", "month");
    }

    /**
     * 当前账本不支持预算（AA 账本不设月预算，需求 1.3）。AA 账本语义为「多人分摊 + 债务清算」，
     * 支出仅计各人自身份额、应收/应付为债权债务项，不设月预算入口；对 AA 账本调用预算接口一律拒绝。
     */
    public static ApiException budgetNotSupportedForAa() {
        return new ApiException("BUDGET_NOT_SUPPORTED", HttpStatus.BAD_REQUEST,
                "AA 账本不设预算", null);
    }

    // ---- 常用工厂方法（Loan 借贷域） ----

    /** 记账模板字段非法（模板名/类型/金额/备注），携带具体无效字段。 */
    public static ApiException templateFieldInvalid(String field, String message) {
        return new ApiException("TEMPLATE_FIELD_INVALID", HttpStatus.BAD_REQUEST, message, field);
    }

    /** 借贷字段非法（方向/对方/金额/发生时间/备注），携带具体无效字段。 */
    public static ApiException loanFieldInvalid(String field, String message) {
        return new ApiException("LOAN_FIELD_INVALID", HttpStatus.BAD_REQUEST, message, field);
    }

    // ---- 常用工厂方法（User / plan-role 域） ----

    /**
     * plan/role 字段取值非法：写入 free/pro/lifetime 或 user/admin 枚举之外的值时拒绝，
     * 保留原有值不变（需求 9.3）。{@code field} 为出错字段（plan 或 role）。
     */
    public static ApiException enumValueInvalid(String field) {
        return new ApiException("ENUM_VALUE_INVALID", HttpStatus.BAD_REQUEST,
                "字段取值非法", field);
    }

    // ---- 常用工厂方法（Invite 邀请域） ----
    // 失败一律零副作用：不写库、不改状态；邀请码/二维码故障不传导到登录与注销主路径。

    /** 连续 10 次抽取的候选邀请码均被占用（需求 1.7、1.8）。 */
    public static ApiException inviteCodeGenFailed() {
        return new ApiException("INVITE_CODE_GEN_FAILED", HttpStatus.INTERNAL_SERVER_ERROR,
                "邀请码生成失败，请重试", null);
    }

    /**
     * 微信配置缺失、凭证获取失败或小程序码接口失败（需求 3.6、3.7、3.14）。
     * 微信 {@code errcode} 只进服务端日志，不透传给客户端。
     */
    public static ApiException inviteQrCodeFailed(String message) {
        return new ApiException("INVITE_QRCODE_FAILED", HttpStatus.BAD_GATEWAY,
                message == null ? "邀请二维码暂时不可用，请稍后重试" : message, null);
    }

    /** 邀请相关接口触发限流（需求 3.9、8.6）。 */
    public static ApiException inviteRateLimited() {
        return new ApiException("INVITE_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                "请求过于频繁，请稍后再试", null);
    }

    /** 被邀请人列表分页参数非法（需求 7.9）。{@code field} 为 page 或 size。 */
    public static ApiException invitePageParamInvalid(String field) {
        return new ApiException("INVITE_PAGE_PARAM_INVALID", HttpStatus.BAD_REQUEST,
                "分页参数非法：page 取值 0-100000，size 取值 1-50", field);
    }

    // ---- 常用工厂方法（Growth 成长域） ----
    // 成长体系（growth-level-system spec）只新增下面这 1 个错误码，别的失败都不对外暴露错误码：
    //  - 结算失败：结算在 afterCommit 回调里独立事务执行，异常一律在事务边界外吞掉只记日志，
    //    记账/导入接口感知不到；成长概览遇到结算失败时降级返回（等级 1 / 经验 0 / 徽章未点亮 +
    //    真实累计统计），响应字段集与结算成功时相同（需求 9.10、9.11）。
    //  - 结算节流：概览侧 10 秒窗口内跳过结算并返回当前持久化取值，同样不返回错误（需求 10.14）。
    // 刻意**不复用** invitePageParamInvalid：跨域复用会让客户端在成长页收到带 INVITE 前缀的
    // 错误码（INVITE_PAGE_PARAM_INVALID），既误导排查也让前端无法按域分派提示文案。

    /** 经验明细分页参数非法（需求 10.9、10.15）。{@code field} 为 page 或 size。 */
    public static ApiException growthPageParamInvalid(String field) {
        return new ApiException("GROWTH_PAGE_PARAM_INVALID", HttpStatus.BAD_REQUEST,
                "分页参数非法：page 取值 0-100000，size 取值 1-50", field);
    }

    // ---- 常用工厂方法（Achievement 成就域） ----
    // 成就系统（achievement-system spec）只新增下面这 1 个错误码，别的失败都不对外暴露错误码
    //（沿用上面成长域同一先例）：
    //  - 结算失败：成就清单接口内的结算异常在事务边界外吞掉只记 [GROWTH_SETTLE_FAILED]，
    //    照常返回已持久化的解锁状态 + 实时聚合的当前值，字段集与结算成功时相同（需求 6.7）。
    //  - 结算被节流：复用既有 10 秒窗口节流器，跳过结算后同样正常返回，不返回错误。
    //  - 空待播报列表：待播报为空是正常态，返回空列表 + total 0，不是错误。
    // 刻意**不复用** growthPageParamInvalid：入参语义（播报游标 vs 分页）与字段名都不同，
    // 跨域复用会让客户端在成就页收到带 GROWTH 前缀的错误码，既误导排查也让前端无法按域分派文案。

    /**
     * 播报游标推进入参非法：{@code lastEventId} 缺失、为空白、无法解析为整数、小于 0，
     * 或大于该用户当前最大 {@code BADGE} 成长事件 id（需求 5.12）。
     *
     * <p>拒绝时 {@code achievement_notices} 的行数与全部列取值保持不变。
     * {@code message} 为中文、≤100 字符，且不含用户 id / 邮箱 / 令牌。</p>
     */
    public static ApiException achievementAckParamInvalid() {
        return new ApiException("ACHIEVEMENT_ACK_PARAM_INVALID", HttpStatus.BAD_REQUEST,
                "播报游标取值不合法", "lastEventId");
    }

    // ---- 常用工厂方法（Streak 连续记账域） ----
    // 连续记账系统（streak-system spec）只新增下面这 1 个错误码，别的失败都不对外暴露错误码
    //（沿用上面成长域、成就域同一先例）：
    //  - 结算失败：连续记账概览触发的结算异常在事务边界外吞掉只记 [STREAK_SETTLE_FAILED]，
    //    照常返回已持久化的段与成长档案取值，字段集与结算成功时相同（需求 6.7）。
    //  - 结算被节流：复用成长概览侧既有 10 秒窗口节流器，跳过结算后同样正常返回，不返回错误。
    //  - 未认证：走既有 unauthenticated()，不新增错误码。
    // 刻意**不复用** growthPageParamInvalid：跨域复用会让客户端在连续记账页收到带 GROWTH 前缀的
    // 错误码（GROWTH_PAGE_PARAM_INVALID），既误导排查也让前端无法按域分派提示文案。

    /**
     * 历史连续区间分页参数非法（需求 6.12、6.13）。{@code field} 为 page 或 size。
     *
     * <p>拒绝时 {@code streak_segments} 表的行数与全部列取值保持不变。
     * {@code message} 为中文、≤100 字符，且不含用户 id / 邮箱 / 令牌。</p>
     */
    public static ApiException streakPageParamInvalid(String field) {
        return new ApiException("STREAK_PAGE_PARAM_INVALID", HttpStatus.BAD_REQUEST,
                "分页参数非法：page 取值 0-100000，size 取值 1-50", field);
    }

    // ---- 常用工厂方法（Custom Reminder 自定义提醒域） ----
    // 自定义提醒系统（custom-reminder spec）只新增下面这 5 个错误码，均为 400 BAD_REQUEST，
    // 复用既有 UNAUTHENTICATED（令牌无效/过期/用户已注销）与 NOT_FOUND（提醒不存在或不属于本人）
    // 两个错误码，不重命名任何既有码（需求 11.5）。校验优先级由高到低固定为
    // FREQUENCY > TIME > DUPLICATE > LIMIT（需求 1.9）。
    // message 均为中文、≤100 字符，且不含用户 id / 邮箱 / 令牌。

    /** 频率非法：{@code frequency} 缺失、为空、或取值（区分大小写）不属于 DAILY/WEEKDAY/WEEKEND（需求 1.3）。 */
    public static ApiException reminderFrequencyInvalid() {
        return new ApiException("REMINDER_FREQUENCY_INVALID", HttpStatus.BAD_REQUEST,
                "提醒频率非法：仅支持每天、工作日或周末", "frequency");
    }

    /**
     * 提醒时间非法：{@code remindTime} 缺失、为空、不符合 HH:mm 格式，或小时不在 0-23、
     * 分钟不在 0-59 之内（需求 1.4）。
     */
    public static ApiException reminderTimeInvalid() {
        return new ApiException("REMINDER_TIME_INVALID", HttpStatus.BAD_REQUEST,
                "提醒时间非法：需为 HH:mm 格式且在 00:00 到 23:59 之间", "remindTime");
    }

    /** 同一用户已存在频率与时间两项均相同的提醒，不重复创建（需求 1.5、7.8）。 */
    public static ApiException reminderDuplicate() {
        return new ApiException("REMINDER_DUPLICATE", HttpStatus.BAD_REQUEST,
                "已存在相同频率与时间的提醒，请勿重复添加", "frequency");
    }

    // ---- 常用工厂方法（AA 账本域） ----
    // AA 账本（aa-ledger spec）分摊记账 / 债务清算相关错误码，均沿用 ApiException 统一错误体。
    // 越权（非成员）刻意复用 notFound()（对外表现为 NOT_FOUND，不泄漏账本存在性，需求 9.4）。

    /** 自定义分摊各份之和 ≠ 该笔总额（需求 3.4）。 */
    public static ApiException aaSplitMismatch() {
        return new ApiException("AA_SPLIT_MISMATCH", HttpStatus.BAD_REQUEST,
                "各参与人分摊金额之和必须等于该笔总额", "customShares");
    }

    /** 分摊参与人或付款人非本账本成员（需求 3.1、3.5）。{@code field} 为出错字段。 */
    public static ApiException aaParticipantInvalid(String field) {
        return new ApiException("AA_PARTICIPANT_INVALID", HttpStatus.BAD_REQUEST,
                "付款人与分摊参与人必须是本账本成员", field);
    }

    /** 分摊方式非法（仅支持 even / custom，需求 3.3、3.4）。 */
    public static ApiException aaSplitModeInvalid() {
        return new ApiException("AA_SPLIT_MODE_INVALID", HttpStatus.BAD_REQUEST,
                "分摊方式仅支持均分或自定义金额", "splitMode");
    }

    /** 对已归档（只读）AA 账本执行写操作被拒（需求 8.3、9.5）。 */
    public static ApiException aaLedgerArchived() {
        return new ApiException("AA_LEDGER_ARCHIVED", HttpStatus.CONFLICT,
                "账本已归档，为只读状态，无法记账或编辑", null);
    }

    /**
     * 归档时账本仍有未结清净额，需二次确认（{@code ?force=true}）方可归档（需求 8.4）。
     *
     * <p>与 {@link #aaMemberUnsettled()} 语义不同：成员退出 / 移除的未结清是<b>硬阻止</b>（必须先结清），
     * 而归档未结清是<b>软阻止</b>——用户 {@code force=true} 二次确认后仍可归档。故单列一码，前端据此弹出
     * 「仍有未结清金额，确认归档？」确认框，确认后带 {@code force=true} 重试。仅 AA 账本适用。</p>
     */
    public static ApiException aaLedgerUnsettled() {
        return new ApiException("AA_LEDGER_UNSETTLED", HttpStatus.CONFLICT,
                "账本仍有未结清金额，确认归档请再次操作", null);
    }

    /**
     * 归档 / 解档仅适用于 AA 账本（需求 8.3-8.5）。个人 / 家庭账本无归档语义，且其只读判定
     * （{@code AA_LEDGER_ARCHIVED}）只在 AA 写路径生效，对非 AA 账本置 {@code archived_at} 不会真正只读，
     * 故直接拒绝，避免半生效的归档态。
     */
    public static ApiException aaArchiveNotSupported() {
        return new ApiException("AA_ARCHIVE_NOT_SUPPORTED", HttpStatus.BAD_REQUEST,
                "只有 AA 账本支持归档 / 解档", null);
    }

    /**
     * 成员仍有未结清净额（应收或应付非 0），不可退出 / 被移除（需求 2.6）。
     *
     * <p>AA 账本成员退出 / 移除前须先结清其全部债务（净额 = 0）；否则退出会使既有分摊 / 净额失真。
     * 仅对 AA 账本生效，协作账本无此限制。历史流水与分摊在成功移除后保留（需求 2.7）。</p>
     */
    public static ApiException aaMemberUnsettled() {
        return new ApiException("AA_MEMBER_UNSETTLED", HttpStatus.CONFLICT,
                "该成员仍有未结清金额，请先结清后再退出或移除", null);
    }

    /**
     * 已涉及结算的 AA 支出不可直接删除 / 编辑（需求 9.2b）。
     *
     * <p>MVP 的结算为账本级净额清算（{@code aa_settlements} 不绑定具体某笔支出），一旦存在未撤销结算，
     * 删除 / 编辑任一支出都会改变净额、使既有结算所依据的债务失真，故须先撤销相关结算再操作。</p>
     */
    public static ApiException aaExpenseSettled() {
        return new ApiException("AA_EXPENSE_SETTLED", HttpStatus.CONFLICT,
                "该笔已涉及结算，请先撤销相关结算后再删除或编辑", null);
    }

    /**
     * 结算金额或对象非法（需求 6.1、6.6）：结算对手非本账本成员 / 与本人相同、结算方向与派生净额不符
     * （付款方须应付、收款方须应收）、金额非正或小数位超过 2 位、或金额超出可结净额
     * {@code min(应付, 应收)}。校验失败零副作用（不动账户、不落结算与展示流水）。
     */
    public static ApiException aaSettlementInvalid() {
        return new ApiException("AA_SETTLEMENT_INVALID", HttpStatus.BAD_REQUEST,
                "结算金额或对象非法", null);
    }

    /** 提醒数量已达上限 10 条，拒绝创建第 11 条（需求 1.6）。 */
    public static ApiException reminderLimitExceeded() {
        return new ApiException("REMINDER_LIMIT_EXCEEDED", HttpStatus.BAD_REQUEST,
                "提醒数量已达上限，最多可创建 10 条", null);
    }

    /** 上报订阅授权次数非法：{@code grantedCount} 缺失、无法解析为整数、小于 1 或大于 5（需求 5.4）。 */
    public static ApiException reminderGrantInvalid() {
        return new ApiException("REMINDER_GRANT_INVALID", HttpStatus.BAD_REQUEST,
                "授权次数非法：需为 1 到 5 之间的整数", "grantedCount");
    }

    // ---- 常用工厂方法（Budget Reminder 预算提醒域） ----
    // 预算提醒系统（subscribe-message-reminders spec）只新增下面这 2 个错误码，均为 400 BAD_REQUEST，
    // 复用既有 UNAUTHENTICATED（令牌无效/过期/用户已注销），不重命名任何既有码、不与 custom-reminder 的
    // REMINDER_* 混用（两套提醒各自独立，需求 9.3、9.6）。message 均为中文、≤100 字符，
    // 且不含用户 id / 邮箱 / 令牌。

    /** 更新预算提醒偏好时 {@code enabled} 缺失或无法解析为布尔值（需求 1.5）；拒绝更新且偏好不变。 */
    public static ApiException budgetReminderPrefInvalid() {
        return new ApiException("BUDGET_REMINDER_PREF_INVALID", HttpStatus.BAD_REQUEST,
                "预算提醒开关取值非法：需为 true 或 false", "enabled");
    }

    /**
     * 上报预算提醒订阅授权时 {@code grantedCount} 缺失、无法解析为整数、小于 1 或大于 5（需求 6.4）；
     * 拒绝上报且剩余订阅次数不变。
     */
    public static ApiException budgetReminderGrantInvalid() {
        return new ApiException("BUDGET_REMINDER_GRANT_INVALID", HttpStatus.BAD_REQUEST,
                "授权次数非法：需为 1 到 5 之间的整数", "grantedCount");
    }

    // ---- 常用工厂方法（Recurring 周期记账域） ----
    // 周期记账（recurring-transactions spec）规则校验 / 生命周期 / 待确认项相关错误码，沿用统一错误体。
    // 金额越界 / 小数位超限复用 AMOUNT_INVALID、备注超长复用 NOTE_TOO_LONG、越权复用 NOT_FOUND、
    // 未认证复用 UNAUTHENTICATED；仅语义确为周期记账特有时新增下列码（见 design.md「错误处理策略」）。

    /**
     * 记账模板字段非法（类型非 expense/income、分类缺失或不属当前账本、账户缺失或不属当前用户在当前账本
     * 可用的账户），携带具体 {@code field}（需求 1.2、1.4、8）。
     */
    public static ApiException recurringRuleInvalid(String field, String message) {
        return new ApiException("RECURRING_RULE_INVALID", HttpStatus.BAD_REQUEST, message, field);
    }

    /**
     * 频率配置非法（枚举外、{@code WEEKLY} 集合为空或含 1–7 之外取值、{@code MONTHLY} 缺指定日、
     * {@code YEARLY} 缺月与日），{@code field=frequency}（需求 1.8、2.10）。
     */
    public static ApiException recurringFrequencyInvalid() {
        return new ApiException("RECURRING_FREQUENCY_INVALID", HttpStatus.BAD_REQUEST,
                "频率配置非法", "frequency");
    }

    /**
     * 结束条件非法（{@code UNTIL_DATE} 结束日早于开始日期、{@code COUNT} 的 N 不在 1–9999），
     * {@code field=endCondition}（需求 1.6、1.7）。
     */
    public static ApiException recurringEndConditionInvalid() {
        return new ApiException("RECURRING_END_CONDITION_INVALID", HttpStatus.BAD_REQUEST,
                "结束条件非法", "endCondition");
    }

    /**
     * 待确认项已处理：对已处于 {@code CONFIRMED} / {@code SKIPPED} 的待确认项再次确认 / 跳过，
     * 或并发 / 重复确认的落败者（需求 4.5、4.9）。拒绝时不重复生成流水、不重复改动账户余额。
     */
    public static ApiException recurringItemAlreadyProcessed() {
        return new ApiException("RECURRING_ITEM_ALREADY_PROCESSED", HttpStatus.CONFLICT,
                "该待确认项已处理", null);
    }

    /**
     * 待确认项目标缺失：确认时其（快照或修改后的）分类或账户在当前账本已不存在 / 不可用，须重新选择
     * （需求 4.6）。拒绝时待确认项保持 {@code PENDING}、不生成流水、不改动账户余额。{@code field} 为
     * {@code categoryId} 或 {@code accountId}。
     */
    public static ApiException recurringItemTargetMissing(String field) {
        return new ApiException("RECURRING_ITEM_TARGET_MISSING", HttpStatus.CONFLICT,
                "分类或账户在当前账本已不存在，请重新选择", field);
    }

    /**
     * 入账方式非法：创建 / 编辑周期规则时提交的入账方式取值不在 {@code CONFIRM}/{@code AUTO} 内
     * （recurring-auto-post 需求 1.4），{@code field=postMode}。拒绝时不改动任何数据。
     */
    public static ApiException recurringPostModeInvalid() {
        return new ApiException("RECURRING_POST_MODE_INVALID", HttpStatus.BAD_REQUEST,
                "入账方式非法", "postMode");
    }
}
