package com.damien.youyu.service;

import java.math.BigDecimal;
import java.util.Objects;

import com.damien.youyu.domain.TransactionType;

/**
 * 资产现金流（Assets_Cashflow_System）口径归类纯函数。
 *
 * <p>把一笔「已确认属于当前用户拥有账户、当月、未软删」的交易，按其 {@link TransactionType} 与
 * AA 结算方向归类为：实际流出 / 实际流入 / 不计入。该判定是账户维度现金流的核心口径：</p>
 *
 * <ul>
 *   <li>{@code expense} / {@code aa_expense} → 流出（全额）。AA 支出仅当付款人为本人时其 {@code account_id}
 *       才落本人账户并扣款，故传入本函数的 {@code aa_expense} 均视为本人实付全额流出。</li>
 *   <li>{@code income} → 流入（全额）。</li>
 *   <li>{@code aa_settlement} → {@code payerUserId == createdBy} 记流出（本人为付款方），否则记流入（本人为收款方）。</li>
 *   <li>{@code transfer} → 不计入（本人两账户间互转，净额为零）。</li>
 * </ul>
 *
 * <p>本类为无状态纯函数：无副作用、无 IO、不依赖时钟/时区/数据库；金额一律 {@link BigDecimal}。
 * 便于被 {@code AssetsCashflowService} 逐笔归约复用，并作为属性测试的主要被测对象。</p>
 */
public final class CashflowClassifier {

    private CashflowClassifier() {
    }

    /** 现金流方向：流出 / 流入 / 不计入。 */
    public enum CashflowDirection {
        /** 使本人账户余额减少（实际流出）。 */
        OUTFLOW,
        /** 使本人账户余额增加（实际流入）。 */
        INFLOW,
        /** 不改变本人账户总额（如账户间转账），不计入任何一侧。 */
        NONE
    }

    /**
     * 单笔交易的现金流归类结果。
     *
     * @param direction 归类方向（流出 / 流入 / 不计入）
     * @param amount    该笔计入的金额；{@link CashflowDirection#NONE} 时为 {@link BigDecimal#ZERO}
     */
    public record CashflowContribution(CashflowDirection direction, BigDecimal amount) {

        /** 该笔对「实际流出」的贡献额（非流出为 0）。 */
        public BigDecimal outflow() {
            return direction == CashflowDirection.OUTFLOW ? amount : BigDecimal.ZERO;
        }

        /** 该笔对「实际流入」的贡献额（非流入为 0）。 */
        public BigDecimal inflow() {
            return direction == CashflowDirection.INFLOW ? amount : BigDecimal.ZERO;
        }
    }

    /**
     * 按交易类型与 AA 结算方向归类单笔现金流。
     *
     * @param type        交易类型
     * @param amount      交易金额（{@code null} 视为 0）
     * @param payerUserId AA 结算的付款方用户 id（仅 {@code aa_settlement} 使用）
     * @param createdBy   交易创建者（本人）用户 id（仅 {@code aa_settlement} 使用）
     * @return 归类结果；不计入时方向为 {@link CashflowDirection#NONE}、金额为 0
     */
    public static CashflowContribution classify(
            TransactionType type, BigDecimal amount, Long payerUserId, Long createdBy) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        if (type == null) {
            return new CashflowContribution(CashflowDirection.NONE, BigDecimal.ZERO);
        }
        switch (type) {
            case EXPENSE:
            case AA_EXPENSE:
                return new CashflowContribution(CashflowDirection.OUTFLOW, value);
            case INCOME:
                return new CashflowContribution(CashflowDirection.INFLOW, value);
            case AA_SETTLEMENT:
                CashflowDirection direction = Objects.equals(payerUserId, createdBy)
                        ? CashflowDirection.OUTFLOW
                        : CashflowDirection.INFLOW;
                return new CashflowContribution(direction, value);
            case TRANSFER:
            default:
                return new CashflowContribution(CashflowDirection.NONE, BigDecimal.ZERO);
        }
    }
}
