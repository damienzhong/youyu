package com.damien.youyu.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.damien.youyu.domain.TransactionType;

/**
 * 记账推荐的只读投影（record-suggestion 需求 2.1、2.4）。
 *
 * <p>接口投影，仅取排序去重所需的七项字段，避免整实体加载。由
 * {@link TransactionRepository#findSuggestionWindowRows} 返回，供
 * {@code RecordSuggestionRanker} 在内存中按形态分组、排序、截断。</p>
 *
 * <p>纯读，不参与任何写入；不含 {@code deleted_at}——软删记录已由
 * {@link com.damien.youyu.domain.Transaction} 的 {@code @SQLRestriction("deleted_at is null")}
 * 从查询中排除。</p>
 */
public interface SuggestionRow {

    /**
     * 交易类型（仅 {@code EXPENSE}/{@code INCOME}，转账已被查询排除）。
     *
     * <p>返回枚举而非 {@code String}：{@code type} 经 {@link com.damien.youyu.domain.TransactionTypeConverter}
     * 以小写编码入库，若投影声明为 {@code String}，Spring Data 会以 {@code Enum.name()} 转换得到大写
     * {@code "EXPENSE"}，与库内小写 {@code "expense"} 及下游预填契约不符。故此处返回枚举，由服务层
     * 以 {@link TransactionType#getCode()} 输出小写编码。</p>
     */
    TransactionType getType();

    /** 金额，恒为正。 */
    BigDecimal getAmount();

    /** 支出/收入分类 id（可能已被删除，仅作预填与形态标识用）。 */
    Long getCategoryId();

    /** 账户 id（可能已被删除，仅作预填与形态标识用）。 */
    Long getAccountId();

    /** 备注（可空，规整由排序器负责）。 */
    String getNote();

    /** 交易时间（近因与代表流水选取用）。 */
    LocalDateTime getOccurredAt();

    /** 交易主键（代表流水的最终决胜键）。 */
    Long getId();
}
