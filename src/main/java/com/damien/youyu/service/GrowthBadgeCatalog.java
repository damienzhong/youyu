package com.damien.youyu.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 9 枚徽章的编码、中文名、门槛、统计口径与展示顺序的<b>单一常量事实源</b>
 * （需求 8.1、8.7、8.8、8.10）。无状态单例。
 *
 * <h2>为什么清单只能有一份</h2>
 *
 * <p>徽章数据全部落在 {@code growth_events}，库里只存 {@code BADGE:<编码>} 这一个事实，
 * <b>不新建徽章表、不新增任何列</b>（需求 8.9）。门槛数值与展示名称一律不在迁移脚本、数据库或
 * miniapp 中重复定义（需求 8.10）：展示名称随成长概览响应下发（需求 8.5），
 * 前端只渲染服务端给的字符串。把「10 笔 / 小有账目」这类取值抄到前端或 SQL 里，
 * 就等于制造了两份会各自漂移的清单，而漂移在点亮那一刻才暴露。</p>
 *
 * <h2>{@code BADGE:} 是徽章的独占命名空间（需求 8.11，双向隔离）</h2>
 *
 * <p>本期 9 枚徽章里有 4 个编码与需求 3 的经验事件类型/事件键<b>同名</b>：
 * {@code FIRST_RECORD}、{@code STREAK_7}、{@code STREAK_30}、{@code BUDGET_MET}。
 * 两类行靠 {@code BADGE:} 前缀彻底分开，隔离必须是双向的：</p>
 *
 * <ul>
 *   <li><b>正向</b>：徽章是否已点亮，<b>只</b>看 {@code event_type = 'BADGE'} 且
 *       {@code event_key = 'BADGE:<编码>'} 的行。裸键 {@code FIRST_RECORD}（经验事件）
 *       不能当作 {@code FIRST_RECORD} 徽章已点亮的依据。</li>
 *   <li><b>反向</b>：{@code BADGE:} 行不参与需求 3 的经验事件判定、不计入需求 4 的累计记账天数，
 *       也不构成 {@code BUDGET_MET} 徽章的点亮条件——该条件只看
 *       {@code event_type = 'BUDGET_MET'} 的行（见 {@link BadgeMetric#BUDGET_MET_EVENT}）。
 *       同理 {@code INVITE_1} 徽章只看 {@code event_key = 'FIRST_INVITE'} 的行。</li>
 * </ul>
 *
 * <p>因为两个前缀不同的键在 {@code uk_growth_events_user_key} 上是两行，
 * 同一用户同时拥有 {@code FIRST_RECORD} 与 {@code BADGE:FIRST_RECORD} 是正常状态，
 * 不是重复数据。全部 {@code BADGE} 行的 {@code exp_amount} 恒为 0（需求 8.3），
 * 徽章不额外发放经验。</p>
 */
@Component
public class GrowthBadgeCatalog {

    /**
     * 徽章事件键的独占前缀（需求 8.11）。
     *
     * <p>唯一定义处，拼键一律走 {@link #eventKeyOf(String)}，不要在别处再写一遍字面量。</p>
     */
    public static final String BADGE_KEY_PREFIX = "BADGE:";

    /**
     * 9 枚徽章，<b>下标顺序即展示顺序</b>，与需求 8.1 的表格逐行一致（需求 8.1、8.8）。
     *
     * <p>{@code List.of} 返回的是不可变列表，因此对外暴露引用是安全的，两次调用顺序恒相同
     * （需求 8.8）。</p>
     */
    private static final List<BadgeDef> BADGES = List.of(
            new BadgeDef("FIRST_RECORD", "开张", 1, BadgeMetric.RECORD_COUNT),
            new BadgeDef("RECORD_10", "小有账目", 10, BadgeMetric.RECORD_COUNT),
            new BadgeDef("RECORD_100", "百笔有余", 100, BadgeMetric.RECORD_COUNT),
            new BadgeDef("RECORD_1000", "千笔如一", 1000, BadgeMetric.RECORD_COUNT),
            new BadgeDef("STREAK_7", "七日不辍", 7, BadgeMetric.MAX_STREAK),
            new BadgeDef("STREAK_30", "卅日成习", 30, BadgeMetric.MAX_STREAK),
            new BadgeDef("DAYS_100", "百日记账", 100, BadgeMetric.TOTAL_DAYS),
            new BadgeDef("BUDGET_MET", "预算达标", 1, BadgeMetric.BUDGET_MET_EVENT),
            new BadgeDef("INVITE_1", "同行有余", 1, BadgeMetric.FIRST_INVITE_EVENT));

    /**
     * 9 枚徽章的有序不可变列表，顺序即展示顺序（需求 8.1、8.5、8.8）。
     *
     * @return 恒含 9 个元素、顺序恒定、不可修改的列表
     */
    public List<BadgeDef> badges() {
        return BADGES;
    }

    /**
     * 点亮条件<b>已成立</b>的徽章编码集合（需求 8.1）。
     *
     * <p><b>只判条件，不判是否已写入事件。</b>调用方各取所需：结算侧用它减去已存在的
     * {@code BADGE:} 键得出本次应写入的行（需求 8.2）；查询侧用它识别「条件已成立但事件尚未写入」
     * 的徽章，那种徽章返回未点亮 + 当前值等于目标值 + 空解锁时刻，且不报错，
     * 由下一次成功结算自愈（需求 8.13）。</p>
     *
     * <p>判定一律取「大于或等于门槛」，因此门槛取等号即点亮。返回顺序与 {@link #badges()} 一致，
     * 便于逐条断言与稳定的事件组装顺序。</p>
     *
     * @param facts 统计事实；{@code null} 按 {@link GrowthFacts#EMPTY} 处理（降级路径不应因此失败）
     * @return 条件已成立的编码集合，可能为空；不可修改
     */
    public Set<String> qualified(GrowthFacts facts) {
        GrowthFacts safe = (facts == null) ? GrowthFacts.EMPTY : facts;
        Set<String> codes = new LinkedHashSet<>();
        for (BadgeDef badge : BADGES) {
            if (metricValue(badge.metric(), safe) >= badge.target()) {
                codes.add(badge.code());
            }
        }
        return Collections.unmodifiableSet(codes);
    }

    /**
     * 某枚徽章在概览响应里的「当前值」（需求 8.12）。
     *
     * <p>三条规则一处实现：已点亮恒等于目标值（不因删除交易使统计回落而展示进度回退）；
     * 未点亮取「统计量当前取值」与「目标值」两者中的较小者；结果恒落在 {@code [0, target]}
     * 闭区间内（脏数据导致的负统计量也钳到 0，不把负数发给前端）。</p>
     *
     * @param badge    徽章定义
     * @param facts    统计事实；{@code null} 按 {@link GrowthFacts#EMPTY} 处理
     * @param unlocked 是否已点亮，唯一依据是该用户存在对应的 {@code BADGE:<编码>} 行（需求 8.4、8.11）
     * @return 当前值，落在 {@code [0, badge.target()]}
     */
    public int currentOf(BadgeDef badge, GrowthFacts facts, boolean unlocked) {
        int target = badge.target();
        if (unlocked) {
            return target;
        }
        long value = metricValue(badge.metric(), (facts == null) ? GrowthFacts.EMPTY : facts);
        return (int) Math.max(0L, Math.min(value, target));
    }

    /**
     * 事件键恒为 {@code "BADGE:" + code}（需求 8.2、8.11）。
     *
     * <p>{@code BADGE:} 是徽章的<b>独占命名空间</b>，与 {@code FIRST_RECORD} / {@code STREAK_7} /
     * {@code STREAK_30} / {@code BUDGET_MET} 四个<b>同名经验事件键双向隔离</b>：徽章只认带前缀的键，
     * 经验事件只认裸键，两者在唯一索引上是两行、互不覆盖也互不判定。详见类级说明。</p>
     *
     * @param code 徽章编码，区分大小写
     * @return 该徽章的事件键
     * @throws IllegalArgumentException {@code code} 为 {@code null} 或空白。空编码会拼出裸前缀
     *                                  {@code "BADGE:"} 这样的键并被唯一索引接受，是一条无法归属到
     *                                  任何徽章的脏数据，必须在写入前就失败
     */
    public static String eventKeyOf(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("徽章编码不能为空");
        }
        return BADGE_KEY_PREFIX + code;
    }

    /**
     * 取某个统计口径在当前事实下的取值（需求 8.7）。
     *
     * <p>存在型口径映射为 1 / 0，于是「门槛为 1 且取值 ≥ 门槛」与「事件存在」等价，
     * 数量型与存在型两类徽章共用同一条比较逻辑。</p>
     */
    private static long metricValue(BadgeMetric metric, GrowthFacts facts) {
        return switch (metric) {
            case RECORD_COUNT -> facts.recordCount();
            case MAX_STREAK -> facts.maxStreakDays();
            case TOTAL_DAYS -> facts.totalRecordDays();
            case BUDGET_MET_EVENT -> facts.budgetMetEvent() ? 1L : 0L;
            case FIRST_INVITE_EVENT -> facts.firstInviteEvent() ? 1L : 0L;
        };
    }
}
