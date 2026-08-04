package com.damien.youyu.service;

import java.util.Arrays;

import org.springframework.stereotype.Component;

/**
 * 「等级怎么算」的唯一定义处（需求 2.1 至 2.6、2.11）。
 *
 * <p>升到等级 L 所需的累计经验为 {@code threshold(L) = 2 × (L − 1)² + 8 × (L − 1)}，
 * 定义域 L ∈ [1, {@link #MAX_LEVEL}]，故 {@code threshold(1) = 0}、{@code threshold(2) = 10}
 * （第一笔记账 +10 EXP 当场升 Lv2）、{@code threshold(100) = 20394}。</p>
 *
 * <p><b>曲线是公式而非手工常量表</b>（需求 2.11）：全部 100 个阈值在类初始化时由上式派生，
 * 迁移脚本与数据库刻意不落任何阈值表。手写一份常量表会制造「脚本里的表和代码里的表对不上」
 * 这类最难查的缺陷。</p>
 *
 * <p><b>本组件无状态、只做整数比较</b>：不使用浮点开方或浮点除法参与换算（需求 2.4），
 * 避免阈值边界因浮点误差错级。派生数组为私有 final，不对外暴露引用，因此对调用方不可变。</p>
 *
 * <h2>调整曲线的唯一允许方向</h2>
 *
 * <p><b>曲线只能向更平缓的方向调整，绝不能改陡。</b>把任一 {@code threshold(L)} 调高会让当前经验
 * 恰在该阈值附近的存量用户当场掉级，直接破坏需求 1.4「经验只增不减、等级不降」——而等级是用户看得见的
 * 长期积累，掉级是不可接受的体验，且无法用数据修复（经验本身没变，只是换算规则变了）。</p>
 *
 * <p><b>要拉长成长曲线，应新增等级段（把 {@link #MAX_LEVEL} 往上扩，为 Lv101 起的新等级定义阈值），
 * 而不是修改 Lv1–Lv100 的既有阈值。</b>新增等级段只会让满级用户重新有目标可追，不动任何既有用户的
 * 当前等级；修改既有阈值则会让全体用户的等级重新洗牌。</p>
 */
@Component
public class GrowthLevelCurve {

    /** 最高等级；满级后经验继续累计、等级恒为该值（需求 2.6）。 */
    public static final int MAX_LEVEL = 100;

    /** {@code THRESHOLDS[L-1] == threshold(L)}；由公式派生，不是手写常量表（需求 2.11）。 */
    private static final long[] THRESHOLDS = buildThresholds();

    /** 由公式派生全部 100 个阈值，是等级曲线的唯一事实源（需求 2.1、2.11）。 */
    private static long[] buildThresholds() {
        long[] thresholds = new long[MAX_LEVEL];
        for (int level = 1; level <= MAX_LEVEL; level++) {
            long n = level - 1L;                             // 用 long 参与乘法（此处虽不会溢出，但意图明确）
            thresholds[level - 1] = 2L * n * n + 8L * n;      // threshold(L) = 2(L-1)^2 + 8(L-1)
        }
        return thresholds;
    }

    /**
     * 升到等级 {@code level} 所需的累计经验（需求 2.1、2.2）。
     *
     * <p>在定义域上严格单调递增，由公式本身保证：
     * {@code threshold(L+1) − threshold(L) = 4L + 6 > 0} 对 L ≥ 1 恒成立，
     * 且步长随等级线性增大（Lv1→Lv2 需 10 点，Lv99→Lv100 需 402 点）。</p>
     *
     * @param level 等级，取值范围 [1, {@link #MAX_LEVEL}]
     * @return 该等级的经验阈值，非负
     * @throws IllegalArgumentException {@code level} 不在 [1, {@link #MAX_LEVEL}] 内
     */
    public long threshold(int level) {
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("等级越界: " + level + "，合法范围 [1, " + MAX_LEVEL + "]");
        }
        return THRESHOLDS[level - 1];
    }

    /**
     * 由经验值换算等级：满足 {@code threshold(L) <= exp} 的最大 L，上限 {@link #MAX_LEVEL}
     * （需求 2.3、2.5、2.6）。全程整数比较，不使用浮点开方或浮点除法（需求 2.4）。
     *
     * <p><b>{@code Arrays.binarySearch} 负返回值的处理</b>是本方法唯一的陷阱：未命中时返回
     * {@code -(插入点) - 1}，插入点是「第一个大于 exp 的元素下标」。由于下标 {@code i} 对应等级
     * {@code i + 1}，插入点 {@code p} 恰好等于「最后一个 ≤ exp 的元素下标 {@code p - 1}」所对应的
     * 等级 {@code p}。当 {@code exp > threshold(100)} 时插入点为 100，返回值自然就是 100，
     * 无需额外的上限截断（需求 2.6：exp ≥ 20394 恒返回 100）。</p>
     *
     * @param exp 经验值；非负（需求 1.3 与 CHECK 约束已排除负值，此处仍按 Lv1 兜底而非抛错，
     *            以免读取路径因一条脏数据整体失败）
     * @return 等级，落在 [1, {@link #MAX_LEVEL}] 闭区间内
     */
    public int levelOf(long exp) {
        if (exp <= 0) {
            return 1;                                        // exp 为 0（或异常负值）一律 Lv1
        }
        int idx = Arrays.binarySearch(THRESHOLDS, exp);
        if (idx >= 0) {
            return idx + 1;                                  // 恰好命中阈值：取等号即升级（需求 2.5）
        }
        int insertionPoint = -(idx + 1);                      // THRESHOLDS[p-1] < exp < THRESHOLDS[p]
        // 数组长度与 MAX_LEVEL 的耦合：插入点上界即数组长度，改了长度却忘了这层耦合时在此断言暴露。
        assert insertionPoint <= MAX_LEVEL : "插入点越界: " + insertionPoint;
        return insertionPoint;                                // 即满足 threshold(L) <= exp 的最大 L
    }
}
