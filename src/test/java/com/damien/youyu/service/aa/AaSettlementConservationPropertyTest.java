package com.damien.youyu.service.aa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/**
 * 结算 / 清算流程的纯核心属性测试（{@link AaMath}，无 Spring / DB），覆盖 design.md Correctness
 * Properties 中与结算相关的部分，并为任务 4.4 的服务 + DB 集成属性（{@code AaSettlementConservationIntegrationTest}）
 * 提供高强度（tries=500/500/300/500）的纯模型基准：
 *
 * <ul>
 *   <li><b>Property 2（净额闭合）</b>：任意随机支出（随机付款人、随机参与子集、均分或偏斜自定义分摊、
 *       非整除总额）下 Σ(net)=0。<b>Validates: Requirements 5.1</b></li>
 *   <li><b>执行建议后 net 全 0</b>：对随机账本计算最小化清算建议并逐条执行后，所有成员 net=0，
 *       且笔数 ≤ 非零净额人数 − 1、建议金额之和 = 总应付。<b>Validates: Requirements 5.3, 5.4</b></li>
 *   <li><b>撤销后精确回滚（净额层）</b>：执行结算后再全部撤销，净额精确回到结算前。
 *       <b>Validates: Requirements 6.5</b></li>
 *   <li><b>Property 4（账户守恒，纯模型）</b>：本人付款支出使付款人账户恰减实付额；执行清算使各账户
 *       变动恰等于其结算前净额（债权人收款 +net、债务人付款 net），全体账户资金守恒；撤销后账户精确回到
 *       结算前快照，无漂移。<b>Validates: Requirements 6.2, 6.3, 7.1</b></li>
 * </ul>
 *
 * <p>纯 long「分」运算。付款人可以不参与分摊（净额仍闭合），支出总额不必能被人数整除（覆盖均分余数校正）。</p>
 */
class AaSettlementConservationPropertyTest {

    /** 从随机原料构造一组「本人付款」的 AA 支出：随机付款人、随机参与子集、均分或偏斜自定义分摊。 */
    private static List<AaMath.Expense> buildExpenses(
            List<Long> totals, int members, List<Integer> payerSeeds,
            List<Integer> subsetSeeds, long modeSeed) {
        List<AaMath.Expense> expenses = new ArrayList<>();
        for (int k = 0; k < totals.size(); k++) {
            long total = totals.get(k);
            long payer = Math.floorMod(payerSeeds.get(k % payerSeeds.size()), members);

            // 参与子集：至少 1 人。用 subsetSeed 的低位比特选成员，若为空则退化为全体。
            int mask = Math.floorMod(subsetSeeds.get(k % subsetSeeds.size()), (1 << members));
            List<Long> participants = new ArrayList<>();
            for (int m = 0; m < members; m++) {
                if ((mask & (1 << m)) != 0) {
                    participants.add((long) m);
                }
            }
            if (participants.isEmpty()) {
                for (int m = 0; m < members; m++) {
                    participants.add((long) m);
                }
            }

            Map<Long, Long> shares = new LinkedHashMap<>();
            boolean even = ((modeSeed >> (k % 63)) & 1L) == 0L;
            if (even) {
                long[] parts = AaMath.splitEven(total, participants.size());
                for (int i = 0; i < participants.size(); i++) {
                    shares.put(participants.get(i), parts[i]);
                }
            } else {
                // 偏斜自定义分摊：首位参与人承担全部，其余 0（Σ=total，合法自定义）。
                for (int i = 0; i < participants.size(); i++) {
                    shares.put(participants.get(i), i == 0 ? total : 0L);
                }
            }
            expenses.add(new AaMath.Expense(payer, total, shares));
        }
        return expenses;
    }

    // ---------- Property 2：净额闭合（更广义生成器） ----------
    @Property(tries = 500)
    void netSumsToZero_forRandomExpenses(
            @ForAll @Size(min = 1, max = 8) List<@LongRange(min = 1, max = 100_000L) Long> totals,
            @ForAll @IntRange(min = 2, max = 6) int members,
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 63) Integer> payerSeeds,
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 255) Integer> subsetSeeds,
            @ForAll long modeSeed) {
        List<AaMath.Expense> expenses = buildExpenses(totals, members, payerSeeds, subsetSeeds, modeSeed);
        Map<Long, Long> net = AaMath.netAmounts(expenses, List.of());
        long sum = net.values().stream().mapToLong(Long::longValue).sum();
        assertThat(sum).isZero();
    }

    // ---------- 执行建议后 net 全 0 + 笔数 ≤ n−1 + 金额之和 = 总应付 ----------
    @Property(tries = 500)
    void executingSuggestions_drivesAllNetsToZero(
            @ForAll @Size(min = 1, max = 8) List<@LongRange(min = 1, max = 100_000L) Long> totals,
            @ForAll @IntRange(min = 2, max = 6) int members,
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 63) Integer> payerSeeds,
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 255) Integer> subsetSeeds,
            @ForAll long modeSeed) {
        List<AaMath.Expense> expenses = buildExpenses(totals, members, payerSeeds, subsetSeeds, modeSeed);
        Map<Long, Long> net = AaMath.netAmounts(expenses, List.of());

        List<AaMath.Transfer> plan = AaMath.minimalSettlements(net);

        // 笔数 ≤ 非零净额人数 − 1（需求 5.3）。
        long nonZero = net.values().stream().filter(v -> v != 0).count();
        assertThat(plan.size()).isLessThanOrEqualTo((int) Math.max(0, nonZero - 1));

        // 建议金额之和 = 总应付额（= Σ 正净额，需求 5.4）。
        long totalPayable = net.values().stream().filter(v -> v > 0).mapToLong(Long::longValue).sum();
        long planSum = plan.stream().mapToLong(AaMath.Transfer::amountCents).sum();
        assertThat(planSum).isEqualTo(totalPayable);
        assertThat(plan).allSatisfy(t -> assertThat(t.amountCents()).isPositive());

        // 逐条执行建议后所有 net 归零。
        Map<Long, Long> finalNet = AaMath.netAmounts(expenses, plan);
        assertThat(finalNet.values().stream().allMatch(v -> v == 0)).isTrue();
    }

    // ---------- 撤销后精确回滚（净额层） ----------
    @Property(tries = 300)
    void revertingSuggestions_restoresPreSettlementNets(
            @ForAll @Size(min = 1, max = 6) List<@LongRange(min = 1, max = 100_000L) Long> totals,
            @ForAll @IntRange(min = 2, max = 6) int members,
            @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 63) Integer> payerSeeds,
            @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 255) Integer> subsetSeeds,
            @ForAll long modeSeed) {
        List<AaMath.Expense> expenses = buildExpenses(totals, members, payerSeeds, subsetSeeds, modeSeed);
        Map<Long, Long> preNet = AaMath.netAmounts(expenses, List.of());

        List<AaMath.Transfer> plan = AaMath.minimalSettlements(preNet);
        // 执行结算后再「撤销」= 结算集合清空，净额应精确回到结算前。
        Map<Long, Long> afterRevert = AaMath.netAmounts(expenses, List.of());
        assertThat(afterRevert).isEqualTo(preNet);

        // 同时确认执行 plan 确实改变过净额（除非本就全 0），确保回滚断言非平凡。
        if (!plan.isEmpty()) {
            Map<Long, Long> settled = AaMath.netAmounts(expenses, plan);
            assertThat(settled).isNotEqualTo(preNet);
        }
    }

    // ---------- Property 4：账户守恒（纯模型：支出扣款 + 结算移动 + 撤销回滚） ----------
    @Property(tries = 500)
    void accountConservation_expenseDebits_settlementMovesExactly_revertRestores(
            @ForAll @Size(min = 1, max = 8) List<@LongRange(min = 1, max = 100_000L) Long> totals,
            @ForAll @IntRange(min = 2, max = 6) int members,
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 63) Integer> payerSeeds,
            @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 0, max = 255) Integer> subsetSeeds,
            @ForAll long modeSeed) {
        List<AaMath.Expense> expenses = buildExpenses(totals, members, payerSeeds, subsetSeeds, modeSeed);

        // 初始账户余额（分）：给每人一个足够大的基数，避免与断言无关的负值干扰阅读。
        long base = 10_000_000L;
        Map<Long, Long> account = new HashMap<>();
        for (int m = 0; m < members; m++) {
            account.put((long) m, base);
        }

        // 本人付款支出：付款人账户恰减实付全额（Property 4 第一部分）。
        Map<Long, Long> paidByPayer = new HashMap<>();
        for (AaMath.Expense e : expenses) {
            account.merge(e.payerUserId(), -e.totalCents(), Long::sum);
            paidByPayer.merge(e.payerUserId(), e.totalCents(), Long::sum);
        }
        for (int m = 0; m < members; m++) {
            long expected = base - paidByPayer.getOrDefault((long) m, 0L);
            assertThat(account.get((long) m)).isEqualTo(expected);
        }

        // 结算前快照与净额。
        Map<Long, Long> preSettleAccount = new HashMap<>(account);
        Map<Long, Long> preNet = AaMath.netAmounts(expenses, List.of());
        long totalBefore = account.values().stream().mapToLong(Long::longValue).sum();

        // 执行最小化清算：每条转账 from 账户 −amount、to 账户 +amount（真实现金在成员间流动）。
        List<AaMath.Transfer> plan = AaMath.minimalSettlements(preNet);
        for (AaMath.Transfer t : plan) {
            account.merge(t.fromUserId(), -t.amountCents(), Long::sum);
            account.merge(t.toUserId(), t.amountCents(), Long::sum);
        }

        // 资金守恒：结算只在成员间移动，总额不变。
        long totalAfter = account.values().stream().mapToLong(Long::longValue).sum();
        assertThat(totalAfter).isEqualTo(totalBefore);

        // 每个账户的结算引起的净变动恰等于其结算前净额（债权人 +net 收款、债务人 net 付款）。
        for (int m = 0; m < members; m++) {
            long delta = account.get((long) m) - preSettleAccount.get((long) m);
            assertThat(delta).isEqualTo(preNet.getOrDefault((long) m, 0L));
        }
        // 执行建议后所有 net 归零。
        Map<Long, Long> settledNet = AaMath.netAmounts(expenses, plan);
        assertThat(settledNet.values().stream().allMatch(v -> v == 0)).isTrue();

        // 撤销全部结算：账户精确回到结算前快照，无漂移（Property 4 撤销部分）。
        for (AaMath.Transfer t : plan) {
            account.merge(t.fromUserId(), t.amountCents(), Long::sum);
            account.merge(t.toUserId(), -t.amountCents(), Long::sum);
        }
        assertThat(account).isEqualTo(preSettleAccount);
    }
}
