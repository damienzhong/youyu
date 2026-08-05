package com.damien.youyu.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 16 枚成就的编码、中文名、描述、分类、门槛、统计口径与展示顺序的<b>单一常量事实源</b>
 * （需求 1.1、1.2、1.3、1.7、1.8）。无状态单例。
 *
 * <h2>本 spec 的扩容（9 → 16 枚）</h2>
 *
 * <p>清单从 growth-level-system 时期的 9 枚扩到 16 枚，新增 {@code STREAK_100}、
 * {@code STREAK_365}、{@code RECORD_500}、{@code COLLAB_1}、{@code BUDGET_MASTER}、
 * {@code SAVING_MASTER}、{@code TRAVEL_MASTER} 七枚；{@link BadgeDef} 同时新增
 * {@code description} 与 {@code category} 两个分量，五个分类
 * （{@link AchievementCategory}：起步 / 坚持 / 积累 / 协作 / 主题）按需求 1.8 连续排布，
 * 分类首现顺序即该枚举的声明顺序。</p>
 *
 * <p><b>既有 9 枚的编码、展示名称与门槛数值一字不改</b>（需求 1.4、1.6）：
 * {@code FIRST_RECORD}、{@code RECORD_10}、{@code RECORD_100}、{@code RECORD_1000}、
 * {@code STREAK_7}、{@code STREAK_30}、{@code DAYS_100}、{@code BUDGET_MET}、{@code INVITE_1}
 * 与 {@code V32} 时期逐项相同，因此已解锁的用户<b>零数据迁移</b>，成就墙上不会少一枚。
 * 扩容只改动了清单的<b>排布顺序</b>（改为按分类连续），而顺序不落库、只影响展示。</p>
 *
 * <p>{@code BADGE:} 前缀与命名空间隔离规则同样<b>一条不改</b>（见下），
 * {@link #qualified(GrowthFacts)} 与 {@link #currentOf(BadgeDef, GrowthFacts, boolean)}
 * 的判定逻辑也一条不改——只是清单变长了。</p>
 *
 * <h2>为什么清单只能有一份</h2>
 *
 * <p>成就数据全部落在 {@code growth_events}，库里只存 {@code BADGE:<编码>} 这一个事实，
 * <b>不新建成就表、不新增任何列</b>（需求 1.10）。门槛数值、展示名称、描述与分类一律不在迁移脚本、
 * 数据库或 miniapp 中重复定义（需求 1.3）：这四项随成就查询接口下发（需求 6.2），
 * 前端只渲染服务端给的字符串。把「10 笔 / 小有账目」这类取值抄到前端或 SQL 里，
 * 就等于制造了两份会各自漂移的清单，而漂移在解锁那一刻才暴露。</p>
 *
 * <h2>{@code BADGE:} 是成就的独占命名空间（需求 2.10，双向隔离）</h2>
 *
 * <p>清单里有 4 个编码与经验事件类型/事件键<b>同名</b>：
 * {@code FIRST_RECORD}、{@code STREAK_7}、{@code STREAK_30}、{@code BUDGET_MET}。
 * 两类行靠 {@code BADGE:} 前缀彻底分开，隔离必须是双向的：</p>
 *
 * <ul>
 *   <li><b>正向</b>：成就是否已解锁，<b>只</b>看 {@code event_type = 'BADGE'} 且
 *       {@code event_key = 'BADGE:<编码>'} 的行。裸键 {@code FIRST_RECORD}（经验事件）
 *       不能当作 {@code FIRST_RECORD} 成就已解锁的依据。</li>
 *   <li><b>反向</b>：{@code BADGE:} 行不参与经验事件判定、不计入累计记账天数，
 *       也不构成 {@code BUDGET_MET} 成就的解锁条件——该条件只看
 *       {@code event_type = 'BUDGET_MET'} 的行（见 {@link BadgeMetric#BUDGET_MET_COUNT}）。
 *       同理 {@code INVITE_1} 成就只看 {@code event_key = 'FIRST_INVITE'} 的行，
 *       {@code SAVING_MASTER} 只看 {@code event_type = 'SAVING_MONTH'} 的行。</li>
 * </ul>
 *
 * <p>因为两个前缀不同的键在 {@code uk_growth_events_user_key} 上是两行，
 * 同一用户同时拥有 {@code FIRST_RECORD} 与 {@code BADGE:FIRST_RECORD} 是正常状态，
 * 不是重复数据。全部 {@code BADGE} 行的 {@code exp_amount} 恒为 0（需求 1.11），
 * 成就不额外发放经验、不影响等级。</p>
 *
 * <h2>启动自校验</h2>
 *
 * <p>清单是一份手写常量，而它同时是接口契约（16 项、门槛、文案）与解锁判定的唯一依据。
 * 因此本类在 {@link #selfCheck()} 里于容器初始化阶段把需求 1.2、1.3、1.9 的全部结构约束
 * 逐条断言一遍，任一条不成立即抛 {@link IllegalStateException} 让应用启动失败
 * （需求 1.13）。详见 {@link #validate(List)}。</p>
 */
@Component
public class GrowthBadgeCatalog {

    private static final Logger log = LoggerFactory.getLogger(GrowthBadgeCatalog.class);

    /**
     * 成就事件键的独占前缀（需求 2.10）。
     *
     * <p>唯一定义处，拼键一律走 {@link #eventKeyOf(String)}，不要在别处再写一遍字面量。</p>
     */
    public static final String BADGE_KEY_PREFIX = "BADGE:";

    /** 清单项数（需求 1.1、1.3）：恰好 16 项，多一项少一项都算缺陷。 */
    static final int EXPECTED_SIZE = 16;

    /** 展示名称的长度闭区间，单位是 Unicode 码点（需求 1.1、1.3）。 */
    static final int NAME_MIN_CODE_POINTS = 2;
    static final int NAME_MAX_CODE_POINTS = 10;

    /** 中文描述的长度闭区间，单位是 Unicode 码点（需求 1.2）。 */
    static final int DESC_MIN_CODE_POINTS = 6;
    static final int DESC_MAX_CODE_POINTS = 30;

    /** 门槛数值的取值闭区间（需求 1.9）。 */
    static final int TARGET_MIN = 1;
    static final int TARGET_MAX = 1000;

    /**
     * 存在型统计口径（需求 1.9）：取值只映射成 1 / 0，因此门槛只能是 1。
     *
     * <p>门槛若被写成 2，该成就将永远无法解锁——这类缺陷不会抛异常、不会报错，
     * 只会让一枚成就静默地永久灰着，所以只能靠启动自校验拦住。</p>
     */
    private static final Set<BadgeMetric> EXISTENCE_METRICS = EnumSet.of(BadgeMetric.FIRST_INVITE_EVENT);

    /**
     * 16 枚成就，<b>下标顺序即展示顺序</b>，与需求 1.1 的表格逐行一致（需求 1.1、1.7、1.8）。
     *
     * <p>同一分类的成就连续出现，分类首现顺序为
     * {@code START → STREAK → VOLUME → SOCIAL → THEME}（需求 1.8）。
     * {@code List.of} 返回的是不可变列表，因此对外暴露引用是安全的，两次调用顺序恒相同
     * （需求 1.7）。</p>
     */
    private static final List<BadgeDef> BADGES = List.of(
            // ---- 起步 ----
            new BadgeDef("FIRST_RECORD", "开张", "记下第 1 笔账，从今天开始",
                    AchievementCategory.START, 1, BadgeMetric.RECORD_COUNT),
            // ---- 坚持（历史最长连续天数）----
            new BadgeDef("STREAK_7", "七日不辍", "连续记账满 7 天",
                    AchievementCategory.STREAK, 7, BadgeMetric.MAX_STREAK),
            new BadgeDef("STREAK_30", "卅日成习", "连续记账满 30 天，习惯已成",
                    AchievementCategory.STREAK, 30, BadgeMetric.MAX_STREAK),
            new BadgeDef("STREAK_100", "百日不辍", "连续记账满 100 天",
                    AchievementCategory.STREAK, 100, BadgeMetric.MAX_STREAK),
            new BadgeDef("STREAK_365", "岁岁有余", "连续记账满 365 天，整整一年",
                    AchievementCategory.STREAK, 365, BadgeMetric.MAX_STREAK),
            // ---- 积累（累计笔数与累计天数）----
            new BadgeDef("RECORD_10", "小有账目", "累计记账满 10 笔",
                    AchievementCategory.VOLUME, 10, BadgeMetric.RECORD_COUNT),
            new BadgeDef("RECORD_100", "百笔有余", "累计记账满 100 笔",
                    AchievementCategory.VOLUME, 100, BadgeMetric.RECORD_COUNT),
            new BadgeDef("RECORD_500", "五百笔在册", "累计记账满 500 笔",
                    AchievementCategory.VOLUME, 500, BadgeMetric.RECORD_COUNT),
            new BadgeDef("RECORD_1000", "千笔如一", "累计记账满 1000 笔",
                    AchievementCategory.VOLUME, 1000, BadgeMetric.RECORD_COUNT),
            new BadgeDef("DAYS_100", "百日记账", "累计记账天数满 100 天",
                    AchievementCategory.VOLUME, 100, BadgeMetric.TOTAL_DAYS),
            // ---- 协作 ----
            new BadgeDef("INVITE_1", "同行有余", "成功邀请第 1 位好友加入",
                    AchievementCategory.SOCIAL, 1, BadgeMetric.FIRST_INVITE_EVENT),
            new BadgeDef("COLLAB_1", "共账之始", "第 1 位成员加入你的账本",
                    AchievementCategory.SOCIAL, 1, BadgeMetric.COLLAB_MEMBER_COUNT),
            // ---- 主题 ----
            new BadgeDef("BUDGET_MET", "预算达标", "首次在一个月内守住预算",
                    AchievementCategory.THEME, 1, BadgeMetric.BUDGET_MET_COUNT),
            new BadgeDef("BUDGET_MASTER", "预算达人", "累计 3 个月达成预算",
                    AchievementCategory.THEME, 3, BadgeMetric.BUDGET_MET_COUNT),
            new BadgeDef("SAVING_MASTER", "储蓄达人", "累计 3 个月存下两成收入",
                    AchievementCategory.THEME, 3, BadgeMetric.SAVING_MONTH_COUNT),
            new BadgeDef("TRAVEL_MASTER", "旅行达人", "旅行支出累计满 10 笔",
                    AchievementCategory.THEME, 10, BadgeMetric.TRAVEL_RECORD_COUNT));

    /**
     * 16 枚成就的有序不可变列表，顺序即展示顺序（需求 1.1、1.3、1.7、1.8）。
     *
     * @return 恒含 16 个元素、顺序恒定、不可修改的列表
     */
    public List<BadgeDef> badges() {
        return BADGES;
    }

    /**
     * 启动自校验：容器初始化本单例时校验 {@link #BADGES}，不合规则让应用启动失败（需求 1.13）。
     *
     * <p><b>为什么放在启动期而不是测试里。</b>清单缺陷（少一项、code 写重、门槛越界、
     * 分类排布断开）不会让任何代码抛异常，只会让接口安静地少返回一枚成就、
     * 让某枚成就永远解锁不了、或让成就页的分组顺序错乱——这类缺陷在运行期几乎无症状，
     * 一旦上线就会以「成就墙上少了一枚」的形式被用户发现，而那时库里已经存了
     * 按错误清单写入的 {@code BADGE} 行。宁可启动失败：<b>绝不以一份错误清单对外服务。</b>
     * 单元测试同样会跑这些断言（任务 2.6），但测试拦不住「测试没跟着改」的情形，
     * 启动自校验拦得住。</p>
     *
     * <p>先记一条 ERROR 再抛出（需求 1.13 要求日志指明首个违规项）：异常栈在 Spring 启动失败时
     * 会被层层包装，日志里那一行才是运维最先看到的东西。</p>
     *
     * @throws IllegalStateException 清单不满足任一约束，消息指明<b>首个</b>违规项
     */
    @PostConstruct
    void selfCheck() {
        try {
            validate(BADGES);
        } catch (IllegalStateException e) {
            log.error("[ACHIEVEMENT_CATALOG_INVALID] {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 校验一份成就清单是否满足需求 1.2、1.3、1.9 的全部结构约束。
     *
     * <p>逐项按下述顺序断言，<b>发现首个违规项即抛出</b>，消息含该项的序号、编码与被违反的规则：</p>
     *
     * <ol>
     *   <li>清单恰好 {@value #EXPECTED_SIZE} 项（需求 1.3）；</li>
     *   <li>编码非空且两两不同（需求 1.3）；</li>
     *   <li>名称非空、两两不同、长度落在 [{@value #NAME_MIN_CODE_POINTS},
     *       {@value #NAME_MAX_CODE_POINTS}] 个 Unicode 码点（需求 1.1、1.3）；</li>
     *   <li>描述非空、两两不同、长度落在 [{@value #DESC_MIN_CODE_POINTS},
     *       {@value #DESC_MAX_CODE_POINTS}] 个 Unicode 码点，且门槛 &gt; 1 时含该门槛数值的
     *       十进制写法（需求 1.2）；</li>
     *   <li>门槛落在 [{@value #TARGET_MIN}, {@value #TARGET_MAX}]，存在型口径的门槛恒为 1（需求 1.9）；</li>
     *   <li>同一分类连续出现，且分类首现顺序等于 {@link AchievementCategory} 的声明顺序
     *       {@code START/STREAK/VOLUME/SOCIAL/THEME}（需求 1.8）。</li>
     * </ol>
     *
     * <p><b>长度一律按 Unicode 码点计</b>（{@link String#codePointCount(int, int)}），
     * 不用 {@link String#length()}：后者数的是 UTF-16 的 {@code char} 个数。
     * 常用汉字在 UTF-16 下确实是 1 个 char，但 emoji（如 🎉）与扩展区的生僻汉字（如 𠀀）
     * 落在辅助平面，各占 2 个 char（一个代理对），用 {@code length()} 会把它们数成 2，
     * 于是「6–30 个码点」的区间会随文案里有没有 emoji 而漂移——需求 1.1、1.2 说的是<b>码点</b>。</p>
     *
     * <p>做成 {@code static} 且只依赖入参，是为了能对构造出来的缺陷清单单独调用（任务 2.6），
     * 无需启动容器。</p>
     *
     * @param badges 待校验的清单，允许为 {@code null}（按项数不符处理）
     * @throws IllegalStateException 任一约束不成立；消息指明首个违规项与被违反的规则
     */
    static void validate(List<BadgeDef> badges) {
        int size = (badges == null) ? 0 : badges.size();
        if (badges == null || size != EXPECTED_SIZE) {
            throw invalid("清单项数应为 " + EXPECTED_SIZE + " 项，实际为 " + size + " 项");
        }

        Set<String> seenCodes = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> seenDescriptions = new HashSet<>();
        Set<AchievementCategory> seenCategories = new LinkedHashSet<>();
        AchievementCategory previousCategory = null;

        for (int i = 0; i < size; i++) {
            BadgeDef badge = badges.get(i);
            int no = i + 1;
            if (badge == null) {
                throw invalid("第 " + no + " 项为 null");
            }
            String at = "第 " + no + " 项（编码 " + badge.code() + "）";

            // ---- 编码：非空 + 两两不同（需求 1.3）----
            if (badge.code() == null || badge.code().isBlank()) {
                throw invalid("第 " + no + " 项的编码为空");
            }
            if (!seenCodes.add(badge.code())) {
                throw invalid(at + "的编码重复出现");
            }

            // ---- 名称：非空 + 长度 + 两两不同（需求 1.1、1.3）----
            if (badge.name() == null) {
                throw invalid(at + "的名称为 null");
            }
            int nameLength = codePoints(badge.name());
            if (nameLength < NAME_MIN_CODE_POINTS || nameLength > NAME_MAX_CODE_POINTS) {
                throw invalid(at + "的名称长度应落在 [" + NAME_MIN_CODE_POINTS + ", " + NAME_MAX_CODE_POINTS
                        + "] 个 Unicode 码点，实际为 " + nameLength + " 个");
            }
            if (!seenNames.add(badge.name())) {
                throw invalid(at + "的名称「" + badge.name() + "」重复出现");
            }

            // ---- 门槛：取值区间 + 存在型口径恒为 1（需求 1.9）----
            if (badge.metric() == null) {
                throw invalid(at + "的统计口径为 null");
            }
            if (badge.target() < TARGET_MIN || badge.target() > TARGET_MAX) {
                throw invalid(at + "的门槛应落在 [" + TARGET_MIN + ", " + TARGET_MAX + "]，实际为 " + badge.target());
            }
            if (EXISTENCE_METRICS.contains(badge.metric()) && badge.target() != 1) {
                throw invalid(at + "的统计口径 " + badge.metric() + " 是存在型，门槛应恒为 1，实际为 " + badge.target());
            }

            // ---- 描述：非空 + 长度 + 两两不同 + 含门槛数值（需求 1.2）----
            if (badge.description() == null) {
                throw invalid(at + "的描述为 null");
            }
            int descLength = codePoints(badge.description());
            if (descLength < DESC_MIN_CODE_POINTS || descLength > DESC_MAX_CODE_POINTS) {
                throw invalid(at + "的描述长度应落在 [" + DESC_MIN_CODE_POINTS + ", " + DESC_MAX_CODE_POINTS
                        + "] 个 Unicode 码点，实际为 " + descLength + " 个");
            }
            if (!seenDescriptions.add(badge.description())) {
                throw invalid(at + "的描述重复出现");
            }
            if (badge.target() > 1 && !badge.description().contains(Integer.toString(badge.target()))) {
                throw invalid(at + "的描述未包含门槛数值 " + badge.target() + " 的十进制写法");
            }

            // ---- 分类：同分类连续 + 首现顺序即枚举声明顺序（需求 1.8）----
            if (badge.category() == null) {
                throw invalid(at + "的分类为 null");
            }
            if (badge.category() != previousCategory) {
                if (!seenCategories.add(badge.category())) {
                    throw invalid(at + "的分类 " + badge.category() + " 不连续：该分类在前面已出现过又被中断");
                }
                AchievementCategory expected = AchievementCategory.values()[seenCategories.size() - 1];
                if (badge.category() != expected) {
                    throw invalid(at + "的分类首现顺序错误：第 " + seenCategories.size() + " 个首现的分类应为 "
                            + expected + "，实际为 " + badge.category());
                }
                previousCategory = badge.category();
            }
        }
    }

    /**
     * 名称与描述的长度一律按 Unicode 码点计，见 {@link #validate(List)} 的说明。
     */
    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("成就清单自校验失败：" + detail);
    }

    /**
     * 解锁条件<b>已成立</b>的成就编码集合（需求 1.1、2.1、2.2）。
     *
     * <p><b>只判条件，不判是否已写入事件。</b>调用方各取所需：结算侧用它减去已存在的
     * {@code BADGE:} 键得出本次应写入的行（需求 2.1）；查询侧用它识别「条件已成立但事件尚未写入」
     * 的成就，那种成就返回未解锁 + 当前值等于门槛 + 空解锁时刻，且不报错，
     * 由下一次成功结算自愈（需求 2.11）。</p>
     *
     * <p>判定一律取「大于或等于门槛」，因此门槛取等号即解锁（需求 2.2）。遍历<b>整份</b>清单、
     * 各判定之间没有 {@code else}，因此同一口径跨越多枚成就的门槛时一枚都不漏
     * （需求 2.6、2.12）。返回顺序与 {@link #badges()} 一致，
     * 便于逐条断言与稳定的事件组装顺序（同批 {@code BADGE} 事件 id 的相对大小与展示序号一致）。</p>
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
     * 某枚成就在响应里的「当前值」（需求 6.4、3.12）。
     *
     * <p>三条规则一处实现：已解锁恒等于门槛（不因删除交易、改名或删除「旅行」分类使统计回落
     * 而展示进度回退）；未解锁取「统计量当前取值」与「门槛」两者中的较小者；结果恒落在
     * {@code [0, target]} 闭区间内（脏数据导致的负统计量也钳到 0，不把负数发给前端）。</p>
     *
     * @param badge    成就定义
     * @param facts    统计事实；{@code null} 按 {@link GrowthFacts#EMPTY} 处理
     * @param unlocked 是否已解锁，唯一依据是该用户存在对应的 {@code BADGE:<编码>} 行（需求 2.3、2.10）
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
     * 事件键恒为 {@code "BADGE:" + code}（需求 1.10、2.10）。
     *
     * <p>{@code BADGE:} 是成就的<b>独占命名空间</b>，与 {@code FIRST_RECORD} / {@code STREAK_7} /
     * {@code STREAK_30} / {@code BUDGET_MET} 四个<b>同名经验事件键双向隔离</b>：成就只认带前缀的键，
     * 经验事件只认裸键，两者在唯一索引上是两行、互不覆盖也互不判定。详见类级说明。</p>
     *
     * @param code 成就编码，区分大小写
     * @return 该成就的事件键
     * @throws IllegalArgumentException {@code code} 为 {@code null} 或空白。空编码会拼出裸前缀
     *                                  {@code "BADGE:"} 这样的键并被唯一索引接受，是一条无法归属到
     *                                  任何成就的脏数据，必须在写入前就失败
     */
    public static String eventKeyOf(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("徽章编码不能为空");
        }
        return BADGE_KEY_PREFIX + code;
    }

    /**
     * 取某个统计口径在当前事实下的取值（需求 3.1~3.13、3.16）。
     *
     * <p>存在型口径映射为 1 / 0，于是「门槛为 1 且取值 ≥ 门槛」与「事件存在」等价，
     * 数量型与存在型两类成就共用同一条比较逻辑。八个口径的取值全部来自传入的
     * {@link GrowthFacts}——本方法不查库、不读时钟，因此判定是纯函数，
     * 同一口径在单次结算 / 单次请求内只求值一次（需求 3.16）。</p>
     */
    private static long metricValue(BadgeMetric metric, GrowthFacts facts) {
        return switch (metric) {
            case RECORD_COUNT -> facts.recordCount();
            case MAX_STREAK -> facts.maxStreakDays();
            case TOTAL_DAYS -> facts.totalRecordDays();
            case BUDGET_MET_COUNT -> facts.budgetMetCount();
            case FIRST_INVITE_EVENT -> facts.firstInviteEvent() ? 1L : 0L;
            case SAVING_MONTH_COUNT -> facts.savingMonthCount();
            case COLLAB_MEMBER_COUNT -> facts.collabMemberCount();
            case TRAVEL_RECORD_COUNT -> facts.travelRecordCount();
        };
    }
}
