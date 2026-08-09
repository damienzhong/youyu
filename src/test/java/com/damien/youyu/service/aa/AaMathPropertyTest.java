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
 * {@link AaMath} 纯核心的属性测试，覆盖 design.md Correctness Properties：
 * <ul>
 *   <li>Property 1（分摊守恒）：均分各份之和 = 总额，且每份 ∈ {base, base+1}。</li>
 *   <li>Property 2（净额闭合）：任意支出 + 结算下，Σ(net) = 0。</li>
 *   <li>Property 3（清算可清零）：按最小化方案执行后所有净额归零，笔数 ≤ n−1，金额恒 &gt; 0。</li>
 * </ul>
 * 纯 long「分」运算，无 Spring / DB 依赖。
 */
class AaMathPropertyTest {

    // ---------- Property 1：均分守恒 ----------
    @Property(tries = 500)
    void splitEven_sumsToTotal_andEachWithinBasePlusOne(
            @ForAll @LongRange(min = 0, max = 100_000_000L) long total,
            @ForAll @IntRange(min = 1, max = 50) int n) {
        long[] parts = AaMath.splitEven(total, n);

        assertThat(parts).hasSize(n);
        long sum = 0;
        long base = total / n;
        for (long p : parts) {
            assertThat(p).isBetween(base, base + 1);
            sum += p;
        }
        assertThat(sum).isEqualTo(total);
    }

    // ---------- Property 2 & 3：净额闭合 + 清算可清零 ----------
    @Property(tries = 500)
    void netClosesToZero_andMinimalSettlementsZeroEveryone(
            @ForAll @Size(min = 2, max = 8) List<@LongRange(min = 1, max = 5000) Long> rawAmounts,
            @ForAll @IntRange(min = 2, max = 8) int members) {
        // 构造一组「均分」支出：每笔随机付款人、随机总额，参与全体成员均分 → 分摊天然守恒
        List<AaMath.Expense> expenses = new ArrayList<>();
        for (int k = 0; k < rawAmounts.size(); k++) {
            long total = rawAmounts.get(k) * members; // 保证能整除，便于构造；均分守恒对任意值都成立，这里简化
            long payer = k % members;
            long[] shares = AaMath.splitEven(total, members);
            Map<Long, Long> shareMap = new LinkedHashMap<>();
            for (int m = 0; m < members; m++) {
                shareMap.put((long) m, shares[m]);
            }
            expenses.add(new AaMath.Expense(payer, total, shareMap));
        }

        Map<Long, Long> net = AaMath.netAmounts(expenses, List.of());

        // Property 2：净额之和为 0
        long netSum = net.values().stream().mapToLong(Long::longValue).sum();
        assertThat(netSum).isZero();

        // Property 3：执行最小化清算后，所有净额归零
        List<AaMath.Transfer> plan = AaMath.minimalSettlements(net);

        long nonZero = net.values().stream().filter(v -> v != 0).count();
        assertThat(plan.size()).isLessThanOrEqualTo((int) Math.max(0, nonZero - 1));

        Map<Long, Long> after = new HashMap<>(net);
        for (AaMath.Transfer t : plan) {
            assertThat(t.amountCents()).isPositive();
            after.merge(t.fromUserId(), t.amountCents(), Long::sum); // 付款人应付减少 → net 上升
            after.merge(t.toUserId(), -t.amountCents(), Long::sum);  // 收款人应收减少 → net 下降
        }
        assertThat(after.values().stream().allMatch(v -> v == 0)).isTrue();
    }

    // ---------- 净额闭合：含结算转账仍守恒 ----------
    @Property(tries = 300)
    void netStaysZero_withArbitrarySettlements(
            @ForAll @Size(min = 1, max = 6) List<@LongRange(min = 1, max = 9999) Long> amounts,
            @ForAll @IntRange(min = 2, max = 6) int members) {
        List<AaMath.Expense> expenses = new ArrayList<>();
        for (int k = 0; k < amounts.size(); k++) {
            long total = amounts.get(k) * members;
            long[] shares = AaMath.splitEven(total, members);
            Map<Long, Long> shareMap = new LinkedHashMap<>();
            for (int m = 0; m < members; m++) {
                shareMap.put((long) m, shares[m]);
            }
            expenses.add(new AaMath.Expense(k % members, total, shareMap));
        }
        Map<Long, Long> net = AaMath.netAmounts(expenses, List.of());
        // 用最小化方案作为「结算」再回代，净额应恰好全部归零
        List<AaMath.Transfer> settlements = AaMath.minimalSettlements(net);
        Map<Long, Long> finalNet = AaMath.netAmounts(expenses, settlements);
        assertThat(finalNet.values().stream().allMatch(v -> v == 0)).isTrue();
    }
}
