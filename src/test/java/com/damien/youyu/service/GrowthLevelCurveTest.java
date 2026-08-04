package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link GrowthLevelCurve} 的示例/边界单元测试（关联需求 2.1、2.2、2.3、2.4、2.5、2.6、2.11）。
 *
 * <p>不使用 mock：被测组件无状态、无依赖，直接 new 即可。三组断言各锁一件事——
 * 阈值表逐级等于公式（需求 2.1、2.11）、定义域外抛错、经验换算的 12 个边界取值
 * （含「阈值取等号即升级」与满级钳制）。</p>
 */
class GrowthLevelCurveTest {

    private final GrowthLevelCurve curve = new GrowthLevelCurve();

    // ---- 阈值表：全枚举比对公式（需求 2.1、2.2、2.11） ----

    @Test
    void thresholdMatchesFormulaForEveryLevel() {
        for (int level = 1; level <= GrowthLevelCurve.MAX_LEVEL; level++) {
            long n = level - 1L;
            long expected = 2L * n * n + 8L * n;
            assertThat(curve.threshold(level))
                    .as("threshold(%d) 应等于 2(L-1)^2+8(L-1)", level)
                    .isEqualTo(expected);
        }
    }

    @Test
    void thresholdAnchorValuesAreFixed() {
        // 需求 2.1 明确钉住的三个取值：Lv1 起点为 0、第一笔记账 +10 EXP 当场升 Lv2、满级门槛 20394。
        assertThat(curve.threshold(1)).isZero();
        assertThat(curve.threshold(2)).isEqualTo(10L);
        assertThat(curve.threshold(100)).isEqualTo(20394L);
        assertThat(GrowthLevelCurve.MAX_LEVEL).isEqualTo(100);
    }

    @Test
    void thresholdIsStrictlyIncreasing() {
        List<Long> thresholds = new ArrayList<>();
        for (int level = 1; level <= GrowthLevelCurve.MAX_LEVEL; level++) {
            thresholds.add(curve.threshold(level));
        }
        // 需求 2.2：定义域上严格单调递增（相邻差 4L+6 > 0，故不存在相等项）。
        assertThat(thresholds).isSorted();
        for (int level = 1; level < GrowthLevelCurve.MAX_LEVEL; level++) {
            assertThat(curve.threshold(level + 1))
                    .as("threshold(%d) 应严格大于 threshold(%d)", level + 1, level)
                    .isGreaterThan(curve.threshold(level));
        }
    }

    // ---- 定义域校验（需求 2.1） ----

    @Test
    void thresholdRejectsLevelsOutsideDomain() {
        assertThatThrownBy(() -> curve.threshold(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("等级越界");
        assertThatThrownBy(() -> curve.threshold(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("等级越界");
        assertThatThrownBy(() -> curve.threshold(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("等级越界");
    }

    // ---- 经验换算：边界表 12 行逐条断言（需求 2.3、2.4、2.5、2.6） ----

    @Test
    void levelOfBoundaryTable() {
        // threshold(2)=10、threshold(3)=24、threshold(9)=192、threshold(10)=234、
        // threshold(99)=19992、threshold(100)=20394。
        assertThat(curve.levelOf(0L)).as("exp=0 → Lv1").isEqualTo(1);
        assertThat(curve.levelOf(9L)).as("exp=9 未达 threshold(2)=10 → Lv1").isEqualTo(1);
        assertThat(curve.levelOf(10L)).as("exp=10 恰等于 threshold(2) → 取等号即升级").isEqualTo(2);
        assertThat(curve.levelOf(11L)).as("exp=11 → Lv2").isEqualTo(2);
        assertThat(curve.levelOf(23L)).as("exp=23 未达 threshold(3)=24 → Lv2").isEqualTo(2);
        assertThat(curve.levelOf(24L)).as("exp=24 恰等于 threshold(3) → Lv3").isEqualTo(3);
        assertThat(curve.levelOf(233L)).as("exp=233 未达 threshold(10)=234 → Lv9").isEqualTo(9);
        assertThat(curve.levelOf(234L)).as("exp=234 恰等于 threshold(10) → Lv10").isEqualTo(10);
        assertThat(curve.levelOf(20393L)).as("exp=20393 未达满级门槛 → Lv99").isEqualTo(99);
        assertThat(curve.levelOf(20394L)).as("exp=20394 恰等于 threshold(100) → 满级").isEqualTo(100);
        assertThat(curve.levelOf(20395L)).as("超过满级门槛 → 钳制在 100").isEqualTo(100);
        assertThat(curve.levelOf(Long.MAX_VALUE)).as("经验继续累计，等级恒为 100").isEqualTo(100);
    }

    @Test
    void levelOfReturnsLevelExactlyAtEveryThreshold() {
        // 需求 2.5 的全量形式：每个阈值取等号都应落在对应等级，阈值减一落在前一等级。
        for (int level = 1; level <= GrowthLevelCurve.MAX_LEVEL; level++) {
            long threshold = curve.threshold(level);
            assertThat(curve.levelOf(threshold))
                    .as("exp 恰等于 threshold(%d) 时应为 Lv%d", level, level)
                    .isEqualTo(level);
            if (level > 1) {
                assertThat(curve.levelOf(threshold - 1))
                        .as("exp 为 threshold(%d)-1 时应仍为 Lv%d", level, level - 1)
                        .isEqualTo(level - 1);
            }
        }
    }
}
