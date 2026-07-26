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
                "口令长度需为 8 到 64 个字符", "password");
    }

    /** 账号或口令错误。 */
    public static ApiException badCredentials() {
        return new ApiException("BAD_CREDENTIALS", HttpStatus.UNAUTHORIZED, "账号或口令错误", null);
    }

    /** 账号被临时锁定。 */
    public static ApiException accountLocked() {
        return new ApiException("ACCOUNT_LOCKED", HttpStatus.LOCKED,
                "账号已被临时锁定，请稍后再试", null);
    }

    /** 未认证（缺少/无效令牌）。 */
    public static ApiException unauthenticated() {
        return new ApiException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "未认证", null);
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
