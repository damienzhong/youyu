package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link StreakMilestones} 的示例/边界单元测试（关联需求 3.5、3.6、3.7、3.8、3.9、3.11、10.10）。
 *
 * <p>锁住三条：</p>
 *
 * <ul>
 *   <li>用真实 {@link GrowthBadgeCatalog} 派生出的里程碑集合恰为 {@code [7, 30, 100, 365]}
 *       （由 {@code nextAfter} 的一串取值间接验证，集合本身不对外暴露）；</li>
 *   <li>构造一份没有 {@code MAX_STREAK} 口径的清单 → 派生为空集 + 恰一条
 *       {@code [STREAK_MILESTONES_EMPTY]} WARN + 不抛异常，且 {@code nextAfter} 恒返回 {@code null}；</li>
 *   <li>{@code nextAfter} 在 0 / 6 / 7 / 8 / 364 / 365 / 366 处的取值。</li>
 * </ul>
 */
class StreakMilestonesTest {

    private Logger milestonesLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        milestonesLogger = (Logger) LoggerFactory.getLogger(StreakMilestones.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        milestonesLogger.addAppender(logAppender);
        milestonesLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        milestonesLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ---- 正常清单派生出 [7, 30, 100, 365] ----

    @Test
    void deriveFromRealCatalogYieldsFourStreakThresholds() {
        StreakMilestones milestones = new StreakMilestones(new GrowthBadgeCatalog());
        milestones.derive();

        // 集合不对外暴露，用 nextAfter 的阶梯间接锁死升序去重后的 [7, 30, 100, 365]
        assertThat(milestones.nextAfter(0)).isEqualTo(7);
        assertThat(milestones.nextAfter(7)).isEqualTo(30);
        assertThat(milestones.nextAfter(30)).isEqualTo(100);
        assertThat(milestones.nextAfter(100)).isEqualTo(365);
        assertThat(milestones.nextAfter(365)).isNull();

        assertThat(warnCount()).isZero();
    }

    // ---- nextAfter 在若干边界处的取值 ----

    @Test
    void nextAfterAtBoundaries() {
        StreakMilestones milestones = new StreakMilestones(new GrowthBadgeCatalog());
        milestones.derive();

        assertThat(milestones.nextAfter(0)).isEqualTo(7);
        assertThat(milestones.nextAfter(6)).isEqualTo(7);
        assertThat(milestones.nextAfter(7)).isEqualTo(30);
        assertThat(milestones.nextAfter(8)).isEqualTo(30);
        assertThat(milestones.nextAfter(364)).isEqualTo(365);
        assertThat(milestones.nextAfter(365)).isNull();
        assertThat(milestones.nextAfter(366)).isNull();
    }

    // ---- 无 MAX_STREAK 口径的清单：空集 + 一条 WARN + 不抛异常 ----

    @Test
    void deriveFromCatalogWithoutMaxStreakYieldsEmptySetAndOneWarn() {
        GrowthBadgeCatalog catalog = mock(GrowthBadgeCatalog.class);
        when(catalog.badges()).thenReturn(List.of(
                new BadgeDef("FIRST_RECORD", "开张", "记下第 1 笔账，从今天开始",
                        AchievementCategory.START, 1, BadgeMetric.RECORD_COUNT),
                new BadgeDef("RECORD_100", "百笔有余", "累计记账满 100 笔",
                        AchievementCategory.VOLUME, 100, BadgeMetric.RECORD_COUNT),
                new BadgeDef("INVITE_1", "同行有余", "成功邀请第 1 位好友加入",
                        AchievementCategory.SOCIAL, 1, BadgeMetric.FIRST_INVITE_EVENT)));

        StreakMilestones milestones = new StreakMilestones(catalog);

        assertThatCode(milestones::derive).doesNotThrowAnyException();

        // 空集 → nextAfter 恒 null（页面据此展示「已全部达成」）
        assertThat(milestones.nextAfter(0)).isNull();
        assertThat(milestones.nextAfter(1000)).isNull();

        assertThat(warnCount()).isEqualTo(1);
        assertThat(logAppender.list.get(0).getFormattedMessage())
                .contains("[STREAK_MILESTONES_EMPTY]");
    }

    /** 统计 WARN 及以上级别的日志条数。 */
    private long warnCount() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .count();
    }
}
