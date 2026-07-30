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

    /** 该操作仅适用于协作账本。 */
    public static ApiException ledgerNotCollaborative() {
        return new ApiException("LEDGER_NOT_COLLABORATIVE", HttpStatus.BAD_REQUEST,
                "只有协作账本可以邀请成员", null);
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
}
