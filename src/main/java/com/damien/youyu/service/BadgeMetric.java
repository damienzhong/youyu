package com.damien.youyu.service;

/**
 * 成就解锁条件所依据的统计口径（需求 1.1、需求 3）。
 *
 * <p>每枚成就的门槛数值（{@link BadgeDef#target()}）都必须配一个口径，否则「当前值」无从计算。
 * 八个取值中七个是<b>数量型</b>口径（{@code RECORD_COUNT}、{@code MAX_STREAK}、{@code TOTAL_DAYS}、
 * {@code BUDGET_MET_COUNT}、{@code SAVING_MONTH_COUNT}、{@code COLLAB_MEMBER_COUNT}、
 * {@code TRAVEL_RECORD_COUNT}），门槛可以是任意正整数；{@code FIRST_INVITE_EVENT} 是<b>存在型</b>
 * 口径，门槛恒为 1，当前值只有 0 与 1 两种取值（需求 1.9）。</p>
 *
 * <p>本枚举<b>不落库、只在内存中使用</b>：库里只存 {@code BADGE:<编码>} 这一个事实，口径纯属服务端
 * 派生逻辑（需求 1.10）。因此新增取值或给既有取值改名<b>零数据影响</b>——不需要迁移脚本，
 * 也不会影响任何已解锁的成就。{@code BUDGET_MET_EVENT} 改名为 {@code BUDGET_MET_COUNT}
 * 正是基于这一点才可以直接改。</p>
 */
public enum BadgeMetric {

    /** 累计有效记账笔数（需求 3.1）。 */
    RECORD_COUNT,

    /** 历史最长连续记账天数，取 {@code user_growth.max_streak_days}（需求 3.1、3.2）。 */
    MAX_STREAK,

    /** 累计记账天数，取 {@code user_growth.total_record_days}（需求 3.1、3.2）。 */
    TOTAL_DAYS,

    /**
     * 预算达成月数：该用户 {@code event_type = 'BUDGET_MET'} 的成长事件条数（需求 3.6）。
     *
     * <p><b>原名 {@code BUDGET_MET_EVENT}</b>，语义由布尔（存在型）改为计数型。改名的前提是本枚举
     * 不落库、只在内存使用，因此零数据影响；语义改写也不改变既有 {@code BUDGET_MET} 成就的判定结果：
     * 门槛为 1 时「取值 ≥ 门槛」与「存在至少一条 {@code BUDGET_MET} 事件」两种判定<b>逐例相同</b>
     * （需求 1.5）。改成计数型之后，{@code BUDGET_MASTER}（门槛 3）才能复用同一个口径。</p>
     *
     * <p><b>只看经验事件类型，不看 {@code BADGE:BUDGET_MET} 行。</b>后者是本成就自己的解锁标记，
     * 拿它当解锁条件会让判定自我循环（已解锁 ⇒ 条件成立 ⇒ 应解锁），条件是否真的成立就再也测不出来。</p>
     */
    BUDGET_MET_COUNT,

    /**
     * 存在型：该用户是否存在 {@code event_key = 'FIRST_INVITE'} 的成长事件（需求 3.8）。
     *
     * <p>同样只看经验事件键，不看 {@code BADGE:INVITE_1} 行。取值只有 0 与 1。</p>
     */
    FIRST_INVITE_EVENT,

    /**
     * 储蓄月数：该用户 {@code event_type = 'SAVING_MONTH'} 的成长事件条数（需求 3.7）。
     *
     * <p>事实源是只追加的成长事件，因此取值只增不减。{@code BADGE} 行不计入本口径。</p>
     */
    SAVING_MONTH_COUNT,

    /**
     * 协作成员数：该用户拥有的账本中 {@code role = 'EDITOR'} 的 {@code ledger_members}
     * <b>成员行行数</b>（按行计数，不按去重用户计数，需求 3.3、3.4）。
     *
     * <p>衡量「有别人加入了我的账本」，不衡量「我加入了别人的账本」。</p>
     */
    COLLAB_MEMBER_COUNT,

    /**
     * 旅行记账笔数：落在「旅行」分类树内的有效支出笔数（需求 3.9、3.10）。
     *
     * <p>这是唯一一个<b>实时聚合</b>的数量型口径，改名或删除「旅行」分类会让未解锁时的进度回落；
     * 但已解锁的成就不撤销（需求 3.12）。</p>
     */
    TRAVEL_RECORD_COUNT
}
