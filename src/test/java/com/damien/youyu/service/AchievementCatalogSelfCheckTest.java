package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

/**
 * {@link GrowthBadgeCatalog#validate(List)} 的缺陷清单单元测试（关联需求 1.2、1.3、1.8、1.9、1.13）。
 *
 * <p>启动自校验的价值全在「清单写错时应用起不来」这一条上，而正常清单永远走不到那些分支——
 * 只断言 {@code selfCheck()} 不抛，等于什么都没测。因此这里反过来构造六类缺陷清单
 * （项数错 / 重复 code / 重复 name / 描述超长 / 门槛越界 / 分类不连续），逐类断言
 * {@link IllegalStateException} 抛出<b>且消息指明首个违规项</b>（需求 1.13）——
 * 消息里没有编码，运维在启动失败的日志里就只能看到「清单不合法」而不知道是哪一项。</p>
 *
 * <p>缺陷清单一律从真实清单派生（替换其中一项），而不是手写 16 项：这样每个用例里唯一的变量
 * 就是被注入的那处缺陷，不会因为手写清单本身还有别的问题而在预期之外的分支上抛出。
 * {@code validate} 是 {@code static} 且只依赖入参，故无需启动 Spring 容器。</p>
 */
class AchievementCatalogSelfCheckTest {

    private final List<BadgeDef> valid = new GrowthBadgeCatalog().badges();

    // ---- 基准：真实清单通过（需求 1.13 的反面）----

    @Test
    void theRealCatalogPassesSelfCheck() {
        assertThatCode(() -> GrowthBadgeCatalog.validate(valid)).doesNotThrowAnyException();
        assertThatCode(() -> new GrowthBadgeCatalog().selfCheck()).doesNotThrowAnyException();
    }

    // ---- 缺陷 1：项数不是 16（需求 1.3）----

    @Test
    void catalogWithWrongSizeIsRejected() {
        List<BadgeDef> tooFew = new ArrayList<>(valid);
        tooFew.remove(15);
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(tooFew))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("清单项数应为 16 项")
                .hasMessageContaining("实际为 15 项");

        List<BadgeDef> tooMany = new ArrayList<>(valid);
        tooMany.add(new BadgeDef("EXTRA", "多余", "多出来的第 17 项描述",
                AchievementCategory.THEME, 1, BadgeMetric.RECORD_COUNT));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(tooMany))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实际为 17 项");

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实际为 0 项");
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实际为 0 项");
    }

    // ---- 缺陷 2：编码重复（需求 1.3）----

    @Test
    void duplicatedCodeIsRejectedAndMessageNamesTheOffendingItem() {
        // 第 6 项 RECORD_10 的编码被写成与第 1 项相同。
        List<BadgeDef> defective = replace(5, b -> new BadgeDef("FIRST_RECORD", b.name(), b.description(),
                b.category(), b.target(), b.metric()));

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 6 项")
                .hasMessageContaining("FIRST_RECORD")
                .hasMessageContaining("编码重复出现");
    }

    @Test
    void blankCodeIsRejected() {
        List<BadgeDef> defective = replace(2, b -> new BadgeDef("  ", b.name(), b.description(),
                b.category(), b.target(), b.metric()));

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 3 项")
                .hasMessageContaining("编码为空");
    }

    // ---- 缺陷 3：名称重复（需求 1.3）----

    @Test
    void duplicatedNameIsRejectedAndMessageNamesTheOffendingItem() {
        // 第 7 项 RECORD_100 的名称被写成与第 1 项相同的「开张」。
        List<BadgeDef> defective = replace(6, b -> new BadgeDef(b.code(), "开张", b.description(),
                b.category(), b.target(), b.metric()));

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 7 项")
                .hasMessageContaining("RECORD_100")
                .hasMessageContaining("开张")
                .hasMessageContaining("名称");
    }

    @Test
    void nameLengthOutsideTwoToTenCodePointsIsRejected() {
        List<BadgeDef> tooShort = replace(1, b -> new BadgeDef(b.code(), "短", b.description(),
                b.category(), b.target(), b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STREAK_7")
                .hasMessageContaining("名称长度")
                .hasMessageContaining("实际为 1 个");

        List<BadgeDef> tooLong = replace(1, b -> new BadgeDef(b.code(), "七日不辍七日不辍七日不", b.description(),
                b.category(), b.target(), b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(tooLong))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("名称长度")
                .hasMessageContaining("实际为 11 个");
    }

    // ---- 缺陷 4：描述超长（需求 1.2）----

    @Test
    void overlongDescriptionIsRejectedAndMessageNamesTheOffendingItem() {
        String thirtyOne = "满 10 笔" + "余".repeat(25);
        assertThat(thirtyOne.codePointCount(0, thirtyOne.length())).isEqualTo(31);

        List<BadgeDef> defective = replace(5, b -> new BadgeDef(b.code(), b.name(), thirtyOne,
                b.category(), b.target(), b.metric()));

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 6 项")
                .hasMessageContaining("RECORD_10")
                .hasMessageContaining("描述长度")
                .hasMessageContaining("实际为 31 个");
    }

    @Test
    void duplicatedOrTooShortOrThresholdlessDescriptionIsRejected() {
        List<BadgeDef> duplicated = replace(6, b -> new BadgeDef(b.code(), b.name(), "累计记账满 10 笔",
                b.category(), b.target(), b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(duplicated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECORD_100")
                .hasMessageContaining("描述重复出现");

        List<BadgeDef> tooShort = replace(0, b -> new BadgeDef(b.code(), b.name(), "太短了",
                b.category(), b.target(), b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIRST_RECORD")
                .hasMessageContaining("实际为 3 个");

        // 门槛 > 1 的成就，描述里必须出现该门槛数值的十进制写法（需求 1.2）。
        List<BadgeDef> withoutThreshold = replace(1, b -> new BadgeDef(b.code(), b.name(), "连续记账满一周",
                b.category(), b.target(), b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(withoutThreshold))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STREAK_7")
                .hasMessageContaining("未包含门槛数值 7");
    }

    /**
     * 长度按 Unicode 码点计而非 {@code String.length()}：含 emoji 的 30 码点描述必须通过。
     *
     * <p>🎉 落在辅助平面，在 UTF-16 下是一个代理对（2 个 char）。用 {@code length()} 数这条描述
     * 会得到 31 而被误判为超长——同一份文案的合法性会随「有没有 emoji」漂移。</p>
     */
    @Test
    void descriptionLengthIsCountedInCodePointsNotUtf16Chars() {
        String thirtyCodePoints = "🎉累计记账满 10 笔" + "余".repeat(19);
        assertThat(thirtyCodePoints.codePointCount(0, thirtyCodePoints.length())).isEqualTo(30);
        assertThat(thirtyCodePoints.length()).as("UTF-16 下是 31 个 char").isEqualTo(31);

        List<BadgeDef> withEmoji = replace(5, b -> new BadgeDef(b.code(), b.name(), thirtyCodePoints,
                b.category(), b.target(), b.metric()));

        assertThatCode(() -> GrowthBadgeCatalog.validate(withEmoji)).doesNotThrowAnyException();
    }

    // ---- 缺陷 5：门槛越界（需求 1.9）----

    @Test
    void targetOutsideOneToOneThousandIsRejectedAndMessageNamesTheOffendingItem() {
        List<BadgeDef> tooLarge = replace(6, b -> new BadgeDef(b.code(), b.name(), b.description(),
                b.category(), 1001, b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(tooLarge))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 7 项")
                .hasMessageContaining("RECORD_100")
                .hasMessageContaining("门槛应落在 [1, 1000]")
                .hasMessageContaining("实际为 1001");

        List<BadgeDef> zero = replace(0, b -> new BadgeDef(b.code(), b.name(), b.description(),
                b.category(), 0, b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(zero))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIRST_RECORD")
                .hasMessageContaining("实际为 0");

        List<BadgeDef> negative = replace(0, b -> new BadgeDef(b.code(), b.name(), b.description(),
                b.category(), -1, b.metric()));
        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(negative))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实际为 -1");
    }

    /** 存在型口径的门槛只能是 1：写成 2 会让该成就永远无法解锁，且运行期毫无症状（需求 1.9）。 */
    @Test
    void existenceMetricWithTargetOtherThanOneIsRejected() {
        List<BadgeDef> defective = replace(10, b -> new BadgeDef(b.code(), b.name(), b.description(),
                b.category(), 2, b.metric()));

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INVITE_1")
                .hasMessageContaining("FIRST_INVITE_EVENT")
                .hasMessageContaining("存在型")
                .hasMessageContaining("实际为 2");
    }

    // ---- 缺陷 6：分类不连续 / 首现顺序错误（需求 1.8）----

    @Test
    void nonContiguousCategoryIsRejectedAndMessageNamesTheOffendingItem() {
        // 把第 3 项 STREAK_30 挪到 VOLUME，于是 STREAK 在第 4 项 STREAK_100 处「中断后又出现」。
        List<BadgeDef> defective = replace(2, b -> new BadgeDef(b.code(), b.name(), b.description(),
                AchievementCategory.VOLUME, b.target(), b.metric()));

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 4 项")
                .hasMessageContaining("STREAK_100")
                .hasMessageContaining("不连续");
    }

    @Test
    void wrongCategoryFirstAppearanceOrderIsRejected() {
        // 第 1 项直接落在 STREAK：首现顺序的第 1 个应为 START。
        List<BadgeDef> defective = replace(0, b -> new BadgeDef(b.code(), b.name(), b.description(),
                AchievementCategory.STREAK, b.target(), b.metric()));

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 1 项")
                .hasMessageContaining("FIRST_RECORD")
                .hasMessageContaining("首现顺序")
                .hasMessageContaining("应为 START");
    }

    // ---- 缺陷：某一项为 null（清单是手写常量，漏一个逗号就可能出现）----

    @Test
    void nullItemIsRejected() {
        List<BadgeDef> defective = new ArrayList<>(valid);
        defective.set(4, null);

        assertThatThrownBy(() -> GrowthBadgeCatalog.validate(defective))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 5 项")
                .hasMessageContaining("null");
    }

    // ---- 辅助 ----

    /** 从真实清单派生一份缺陷清单：只替换第 {@code index} 项，其余 15 项原样保留。 */
    private List<BadgeDef> replace(int index, UnaryOperator<BadgeDef> mutation) {
        List<BadgeDef> copy = new ArrayList<>(valid);
        copy.set(index, mutation.apply(copy.get(index)));
        return copy;
    }
}
