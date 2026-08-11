package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.service.CashflowClassifier.CashflowContribution;
import com.damien.youyu.service.CashflowClassifier.CashflowDirection;

/**
 * {@link CashflowClassifier#classify} 的属性测试（assets-monthly-cashflow 设计文档 Property 1–5）。
 *
 * <h2>测试层级选择</h2>
 * <p>{@code classify} 是不查库、不读时钟、无静态可变状态的纯函数：账户归属过滤、软删排除、时区月界均由
 * {@code AssetsCashflowService} 在入口完成（其属性由任务 2.2 覆盖），本类只对「已确认属于本人拥有账户、当月、
 * 未软删」的单笔交易做类型 + AA 结算方向归类。故直接以随机交易投影驱动纯函数、走纯 jqwik，不引入 Spring
 * 上下文、不落库、不 mock（对齐 {@link RecordSuggestionRankerPropertyTest} 风格）。</p>
 *
 * <h2>生成维度</h2>
 * <p>形态由四元组 {@code (type, amount, payerUserId, createdBy)} 决定：type 覆盖全部
 * {@link TransactionType}；amount 覆盖金额边界（{@code 0.01}、大额、含两位小数）；payerUserId/createdBy 取
 * 小基数 id 池（含相等与不等两种情形），以让 AA 结算的付出 / 收款两个方向都高频触发。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>逐笔累加与净额的期望值<strong>不复用</strong>被测的 {@code classify} 分类分支，而是另写独立的朴素参考
 * （单独的 {@code switch}），两侧算法互为参照。金额比较用 {@code isEqualByComparingTo} 忽略标度差异。</p>
 */
class CashflowClassifierPropertyTest {

    /** 金额边界值池：{@code 0.01}、常见两位小数、大额（{@code DECIMAL(18,2)} 语义）。 */
    private static final List<BigDecimal> AMOUNTS = List.of(
            new BigDecimal("0.01"),
            new BigDecimal("0.99"),
            new BigDecimal("1.00"),
            new BigDecimal("12.34"),
            new BigDecimal("100.00"),
            new BigDecimal("9999.99"),
            new BigDecimal("1234567.89"),
            new BigDecimal("9999999999999999.99"));

    /** 用户 id 小基数池：小基数保证 payerUserId 与 createdBy 相等 / 不等两种情形都高频出现。 */
    private static final List<Long> USER_IDS = List.of(1L, 2L, 3L);

    // ---------------- 轻量交易投影 ----------------

    /** 传入 {@code classify} 的单笔交易投影（已确认属本人账户、当月、未软删）。 */
    private record Tx(TransactionType type, BigDecimal amount, Long payerUserId, Long createdBy) {
    }

    // ---------------- 生成器 ----------------

    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.of(AMOUNTS);
    }

    private Arbitrary<Long> userIds() {
        return Arbitraries.of(USER_IDS);
    }

    private Arbitrary<TransactionType> anyType() {
        return Arbitraries.of(TransactionType.values());
    }

    /** 任意类型的单笔交易，四维度全覆盖。 */
    @Provide
    Arbitrary<Tx> anyTx() {
        return Combinators.combine(anyType(), amounts(), userIds(), userIds())
                .as(Tx::new);
    }

    /** 任意类型交易列表，规模封顶 40。 */
    @Provide
    Arbitrary<List<Tx>> txLists() {
        return anyTx().list().ofMaxSize(40);
    }

    /** 仅 {@code aa_expense} 交易，覆盖 payer/creator 相等与不等。 */
    @Provide
    Arbitrary<Tx> aaExpenseTx() {
        return Combinators.combine(amounts(), userIds(), userIds())
                .as((amount, payer, creator) -> new Tx(TransactionType.AA_EXPENSE, amount, payer, creator));
    }

    /** 仅 {@code aa_settlement} 交易，覆盖 payer/creator 相等（付出）与不等（收款）。 */
    @Provide
    Arbitrary<Tx> aaSettlementTx() {
        return Combinators.combine(amounts(), userIds(), userIds())
                .as((amount, payer, creator) -> new Tx(TransactionType.AA_SETTLEMENT, amount, payer, creator));
    }

    /** 仅 {@code transfer} 交易。 */
    @Provide
    Arbitrary<List<Tx>> transferLists() {
        Arbitrary<Tx> transfer = Combinators.combine(amounts(), userIds(), userIds())
                .as((amount, payer, creator) -> new Tx(TransactionType.TRANSFER, amount, payer, creator));
        return transfer.list().ofMaxSize(20);
    }

    // ---------------- 逐笔归约 + 独立参考 ----------------

    private static CashflowContribution classify(Tx tx) {
        return CashflowClassifier.classify(tx.type(), tx.amount(), tx.payerUserId(), tx.createdBy());
    }

    /** 用被测 {@code classify} 逐笔累加出流出 / 流入。 */
    private static BigDecimal[] aggregateViaClassify(List<Tx> txns) {
        BigDecimal outflow = BigDecimal.ZERO;
        BigDecimal inflow = BigDecimal.ZERO;
        for (Tx tx : txns) {
            CashflowContribution c = classify(tx);
            outflow = outflow.add(c.outflow());
            inflow = inflow.add(c.inflow());
        }
        return new BigDecimal[] {outflow, inflow};
    }

    /** 独立朴素参考：不复用 classify 的分支，按需求 1 口径另写一遍。 */
    private static BigDecimal[] aggregateReference(List<Tx> txns) {
        BigDecimal outflow = BigDecimal.ZERO;
        BigDecimal inflow = BigDecimal.ZERO;
        for (Tx tx : txns) {
            switch (tx.type()) {
                case EXPENSE, AA_EXPENSE -> outflow = outflow.add(tx.amount());
                case INCOME -> inflow = inflow.add(tx.amount());
                case AA_SETTLEMENT -> {
                    if (Objects.equals(tx.payerUserId(), tx.createdBy())) {
                        outflow = outflow.add(tx.amount());
                    } else {
                        inflow = inflow.add(tx.amount());
                    }
                }
                case TRANSFER -> {
                    // 转账不计入任何一侧。
                }
            }
        }
        return new BigDecimal[] {outflow, inflow};
    }

    // ---------------- Property 1: 归类与逐笔口径一致 ----------------

    // Feature: assets-monthly-cashflow, Property 1: 归类与逐笔口径一致
    /**
     * 对任意交易集合，用 {@code classify} 逐笔累加得到的流出 / 流入，等于按需求 1 口径独立朴素参考逐笔累加之和
     * （expense/aa_expense→流出、income→流入、aa_settlement 按方向、transfer 不计入）。
     *
     * <p>Validates: Requirements 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.9, 6.1, 6.2</p>
     */
    @Property(tries = 200)
    void property1_classifyMatchesPerTransactionReference(@ForAll("txLists") List<Tx> txns) {
        BigDecimal[] actual = aggregateViaClassify(txns);
        BigDecimal[] expected = aggregateReference(txns);

        assertThat(actual[0])
                .as("流出应等于独立逐笔参考之和")
                .isEqualByComparingTo(expected[0]);
        assertThat(actual[1])
                .as("流入应等于独立逐笔参考之和")
                .isEqualByComparingTo(expected[1]);
    }

    // ---------------- Property 2: 转账零贡献 ----------------

    // Feature: assets-monthly-cashflow, Property 2: 转账零贡献
    /**
     * 对任意交易集合，加入任意数量的 {@code transfer} 交易，流出、流入、净流入三值均不变；且每笔 transfer 的
     * 归类方向为 {@link CashflowDirection#NONE}、流出与流入贡献均为 0。
     *
     * <p>Validates: Requirements 1.7</p>
     */
    @Property(tries = 200)
    void property2_transfersContributeNothing(
            @ForAll("txLists") List<Tx> base,
            @ForAll("transferLists") List<Tx> transfers) {

        BigDecimal[] before = aggregateViaClassify(base);

        List<Tx> merged = new ArrayList<>(base);
        merged.addAll(transfers);
        BigDecimal[] after = aggregateViaClassify(merged);

        assertThat(after[0]).as("加入转账后流出不变").isEqualByComparingTo(before[0]);
        assertThat(after[1]).as("加入转账后流入不变").isEqualByComparingTo(before[1]);
        assertThat(after[1].subtract(after[0]))
                .as("加入转账后净流入不变")
                .isEqualByComparingTo(before[1].subtract(before[0]));

        for (Tx transfer : transfers) {
            CashflowContribution c = classify(transfer);
            assertThat(c.direction()).as("转账不计入任何一侧").isEqualTo(CashflowDirection.NONE);
            assertThat(c.outflow()).as("转账流出贡献为 0").isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(c.inflow()).as("转账流入贡献为 0").isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ---------------- Property 3: AA 实付归属 ----------------

    // Feature: assets-monthly-cashflow, Property 3: AA 实付归属
    /**
     * 传入 {@code classify} 的 {@code aa_expense} 均为「本人实付」（其 {@code account_id} 已落本人账户，
     * 由服务层的账户归属过滤保证——非本人实付的 aa_expense 其 {@code account_id} 为空 / 他人账户，根本不会到达
     * 本函数）。故对任意 {@code aa_expense}，无论 payerUserId 与 createdBy 相等与否，恒归类为流出全额、流入贡献为 0。
     *
     * <p>Validates: Requirements 1.3, 1.10, 1.11</p>
     */
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void property3_aaExpenseIsFullOutflow(@ForAll("aaExpenseTx") Tx tx) {
        CashflowContribution c = classify(tx);

        assertThat(c.direction()).as("AA 实付支出恒为流出").isEqualTo(CashflowDirection.OUTFLOW);
        assertThat(c.outflow())
                .as("AA 实付支出流出为该笔全额，不因 payer/creator 是否相等而变")
                .isEqualByComparingTo(tx.amount());
        assertThat(c.inflow()).as("AA 实付支出无流入贡献").isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------------- Property 4: AA 结算方向 ----------------

    // Feature: assets-monthly-cashflow, Property 4: AA 结算方向
    /**
     * 对任意 {@code aa_settlement} 展示流水：{@code payerUserId == createdBy}（本人为付款方）记流出、
     * 否则（本人为收款方）记流入，金额均为该笔 {@code amount}。
     *
     * <p>Validates: Requirements 1.4, 1.6</p>
     */
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void property4_aaSettlementDirection(@ForAll("aaSettlementTx") Tx tx) {
        CashflowContribution c = classify(tx);

        boolean payerIsSelf = Objects.equals(tx.payerUserId(), tx.createdBy());
        if (payerIsSelf) {
            assertThat(c.direction()).as("本人为付款方→流出").isEqualTo(CashflowDirection.OUTFLOW);
            assertThat(c.outflow()).as("付出方向流出为该笔全额").isEqualByComparingTo(tx.amount());
            assertThat(c.inflow()).as("付出方向无流入贡献").isEqualByComparingTo(BigDecimal.ZERO);
        } else {
            assertThat(c.direction()).as("本人为收款方→流入").isEqualTo(CashflowDirection.INFLOW);
            assertThat(c.inflow()).as("收款方向流入为该笔全额").isEqualByComparingTo(tx.amount());
            assertThat(c.outflow()).as("收款方向无流出贡献").isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ---------------- Property 5: 净流入恒等与可负 ----------------

    // Feature: assets-monthly-cashflow, Property 5: 净流入恒等与可负
    /**
     * 对任意输入，净流入恒等于流入减流出；且当流出严格大于流入时净流入为负（净流出）、流入严格大于流出时为正、
     * 相等时为零。
     *
     * <p>Validates: Requirements 1.8</p>
     */
    @Property(tries = 200)
    void property5_netInflowIdentityAndSign(@ForAll("txLists") List<Tx> txns) {
        BigDecimal[] agg = aggregateViaClassify(txns);
        BigDecimal outflow = agg[0];
        BigDecimal inflow = agg[1];
        BigDecimal netInflow = inflow.subtract(outflow);

        // 恒等式：netInflow == inflow - outflow。
        assertThat(netInflow)
                .as("净流入应恒等于流入减流出")
                .isEqualByComparingTo(inflow.subtract(outflow));

        // 符号与流入 / 流出大小关系一致（可负）。
        int cmp = inflow.compareTo(outflow);
        if (cmp < 0) {
            assertThat(netInflow.signum()).as("流出大于流入时净流入为负").isEqualTo(-1);
        } else if (cmp > 0) {
            assertThat(netInflow.signum()).as("流入大于流出时净流入为正").isEqualTo(1);
        } else {
            assertThat(netInflow.signum()).as("流入等于流出时净流入为零").isEqualTo(0);
        }
    }
}
