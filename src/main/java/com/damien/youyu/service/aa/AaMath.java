package com.damien.youyu.service.aa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AA 账本纯计算核心：分摊分配、净额计算、最小化清算。全部以「分」（long cents）为单位精确运算，
 * 不依赖 Spring / 数据库，便于单元与属性测试（对应 design.md Correctness Properties 1–3）。
 *
 * <p>约定：金额恒为非负分值；用户 id 用 long 标识；净额 net &gt; 0 表示应收（别人欠他），
 * net &lt; 0 表示应付（他欠别人），全体净额之和恒为 0。</p>
 */
public final class AaMath {

    private AaMath() {
    }

    /** 一笔 AA 支出：付款人、总额（分）、各参与人分摊额（分，Σ 必须等于总额）。 */
    public record Expense(long payerUserId, long totalCents, Map<Long, Long> shares) {
    }

    /** 一次转账：付款人 → 收款人，金额（分，恒 &gt; 0）。用于结算方案与已结算记录。 */
    public record Transfer(long fromUserId, long toUserId, long amountCents) {
    }

    /**
     * 均分：把 totalCents 平均分成 n 份，以「分」守恒——base = ⌊total/n⌋，前 (total − base·n) 份各 +1，
     * 保证各份之和恰等于 totalCents，且每份 ∈ {base, base+1}。
     *
     * @throws IllegalArgumentException n ≤ 0 或 totalCents &lt; 0
     */
    public static long[] splitEven(long totalCents, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("参与人数必须为正");
        }
        if (totalCents < 0) {
            throw new IllegalArgumentException("金额不能为负");
        }
        long base = totalCents / n;
        long rem = totalCents - base * n; // 0 ≤ rem < n
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = base + (i < rem ? 1 : 0);
        }
        return out;
    }

    /**
     * 校验自定义分摊：各份之和必须等于总额。
     *
     * @return true 表示合法（Σ = total）
     */
    public static boolean isValidCustomSplit(long totalCents, Iterable<Long> shares) {
        long sum = 0;
        for (Long s : shares) {
            if (s == null || s < 0) {
                return false;
            }
            sum += s;
        }
        return sum == totalCents;
    }

    /**
     * 计算每个成员的净额（分）。
     * net = Σ(其作为付款人的总额) − Σ(其分摊额) − Σ(其收到的结算) + Σ(其付出的结算)。
     * 只要每笔支出的分摊之和等于总额、每条结算对双方各计一次，Σ(net) 恒为 0（Property 2）。
     */
    public static Map<Long, Long> netAmounts(List<Expense> expenses, List<Transfer> settlements) {
        Map<Long, Long> net = new LinkedHashMap<>();
        for (Expense e : expenses) {
            net.merge(e.payerUserId(), e.totalCents(), Long::sum);
            for (Map.Entry<Long, Long> en : e.shares().entrySet()) {
                net.merge(en.getKey(), -en.getValue(), Long::sum);
            }
        }
        for (Transfer s : settlements) {
            net.merge(s.toUserId(), -s.amountCents(), Long::sum);   // 收款减少应收 → net 下降
            net.merge(s.fromUserId(), s.amountCents(), Long::sum);  // 付款减少应付 → net 上升
        }
        return net;
    }

    /**
     * 依净额产出最少转账笔数的清算方案（债权最大 ↔ 债务最大 贪心配对）。
     * 输入的 net 之和必须为 0（否则无法完全清零，视为调用方错误）。
     *
     * <p>性质（Property 3）：所有转账金额 &gt; 0；转账笔数 ≤ (非零净额人数 − 1) ≤ (n − 1)；
     * 按方案执行后所有成员净额归零。</p>
     */
    public static List<Transfer> minimalSettlements(Map<Long, Long> netCents) {
        List<long[]> creditors = new ArrayList<>(); // [userId, amount>0]
        List<long[]> debtors = new ArrayList<>();    // [userId, amount>0]（欠款绝对值）
        for (Map.Entry<Long, Long> e : netCents.entrySet()) {
            long v = e.getValue();
            if (v > 0) {
                creditors.add(new long[] { e.getKey(), v });
            } else if (v < 0) {
                debtors.add(new long[] { e.getKey(), -v });
            }
        }
        // 金额降序，保证配对稳定且倾向于用大额相抵、减少笔数
        creditors.sort(Comparator.comparingLong((long[] a) -> a[1]).reversed());
        debtors.sort(Comparator.comparingLong((long[] a) -> a[1]).reversed());

        List<Transfer> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < debtors.size() && j < creditors.size()) {
            long[] d = debtors.get(i);
            long[] c = creditors.get(j);
            long pay = Math.min(d[1], c[1]);
            if (pay > 0) {
                out.add(new Transfer(d[0], c[0], pay));
            }
            d[1] -= pay;
            c[1] -= pay;
            if (d[1] == 0) {
                i++;
            }
            if (c[1] == 0) {
                j++;
            }
        }
        return out;
    }
}
