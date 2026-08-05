package com.damien.youyu.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 连续里程碑集合，从成就清单常量派生（需求 3.5～3.9、3.11；10.10）。
 *
 * <p>里程碑集合<strong>不在本 spec 的服务端代码、迁移脚本、数据库与 miniapp 中写死任何一个数值</strong>
 * （需求 3.5、10.10、9.16）：它在应用启动时由 {@link GrowthBadgeCatalog} 中统计口径为
 * {@link BadgeMetric#MAX_STREAK} 的成就门槛派生，升序去重、不可变。将来若成就清单增删了
 * {@code MAX_STREAK} 口径的门槛，里程碑集合自动跟随，无需改本类一个字。</p>
 *
 * <h2>为什么按口径过滤而不是按编码前缀</h2>
 *
 * <p>本类<strong>按 {@code BadgeMetric.MAX_STREAK} 口径过滤，而不是按 {@code STREAK_} 编码前缀</strong>：
 * 前缀是命名巧合，口径才是语义。将来若新增一枚编码不以 {@code STREAK_} 开头（如 {@code YEAR_ROUND}）
 * 但仍用 {@code MAX_STREAK} 口径的成就，它会自动成为里程碑；反之若某枚 {@code STREAK_*} 改了口径，
 * 它会自动退出里程碑集合。以口径为准，规则与语义永远一致，不会出现「编码看着像连续成就、
 * 实则统计的是别的东西」这类难查的错配。</p>
 *
 * <h2>进度按当前连续天数算，成就解锁按 max_streak_days 判</h2>
 *
 * <p>里程碑进度按<strong>当前连续天数</strong>计算（激励语义：你现在连到第几天），
 * 而成就解锁仍按 {@code max_streak_days} 判定（收集语义：你曾经连到第几天），两者刻意不同（需求 3.9）。
 * 因此本类只提供门槛集合与「下一里程碑」换算，<strong>不返回任何成就编码、解锁状态与解锁时刻</strong>。</p>
 *
 * <h2>门槛为空时不炸启动</h2>
 *
 * <p>成就清单中若没有任何 {@code MAX_STREAK} 口径的门槛，派生结果为空集，本类<strong>只记一条
 * WARN、不抛异常、不使应用启动失败、不使概览请求失败</strong>（需求 3.11）：空集让
 * {@link #nextAfter(int)} 恒返回 {@code null}，页面据此展示「已全部达成」。成就清单本身另有
 * {@link GrowthBadgeCatalog#selfCheck()} 的启动自校验兜底，本类再抛一次异常只会把一个可降级的
 * 展示问题升级为不可用。</p>
 *
 * <p>Feature: streak-system。覆盖需求 3.5、3.6、3.7、3.8、3.9、3.11、10.10。</p>
 */
@Component
public class StreakMilestones {

    private static final Logger log = LoggerFactory.getLogger(StreakMilestones.class);

    private final GrowthBadgeCatalog catalog;

    /** 升序、去重、不可变；由成就清单常量派生，不含任何写死的数值。 */
    private List<Integer> thresholds = List.of();

    public StreakMilestones(GrowthBadgeCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 启动期从成就清单派生里程碑集合（需求 3.5）。
     *
     * <p>按 {@link BadgeMetric#MAX_STREAK} 口径过滤，取门槛去重升序。派生为空时只告警、不抛异常
     * （需求 3.11）。</p>
     */
    @PostConstruct
    void derive() {
        thresholds = catalog.badges().stream()
                .filter(b -> b.metric() == BadgeMetric.MAX_STREAK)   // 口径过滤，不按编码前缀
                .map(BadgeDef::target)
                .distinct()
                .sorted()
                .toList();
        if (thresholds.isEmpty()) {
            // 需求 3.11：只告警，不使应用启动失败、不使概览请求失败。
            log.warn("[STREAK_MILESTONES_EMPTY] 成就清单中没有 MAX_STREAK 口径的门槛，里程碑区域将展示为已全部达成");
        }
    }

    /**
     * 下一里程碑：升序集合中大于当前连续天数的最小取值；不存在时返回 {@code null}（需求 3.6、3.7）。
     *
     * @param currentStreakDays 当前连续天数
     * @return 大于入参的最小门槛，或 {@code null}（当前连续天数已达到或超过最大门槛，即全部里程碑已达成）
     */
    public Integer nextAfter(int currentStreakDays) {
        for (Integer t : thresholds) {
            if (t > currentStreakDays) {
                return t;
            }
        }
        return null;
    }
}
