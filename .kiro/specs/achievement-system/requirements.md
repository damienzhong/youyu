# Requirements Document

## Introduction

有余（youyu）的成长体系（growth-level-system，已实现）已经在 `growth_events` 表里以
`event_type = 'BADGE'` 的行承载了 9 枚徽章，但这 9 枚徽章只是「成长页上一面静态的墙」：
用户解锁的那一刻没有任何反馈，解锁了也没法告诉朋友。

本次新增**成就系统**，把徽章升级为一套完整的成就体验：

- **成就清单从 9 枚扩到 16 枚**，覆盖用户原始需求里的全部条目：第一笔账、坚持 7 / 30 / 100 / 365 天、
  100 / 500 / 1000 笔账、第一位协作成员、第一位邀请好友，以及预算达人、储蓄达人、旅行达人三枚主题成就。
  清单按 **起步 / 坚持 / 积累 / 协作 / 主题** 五个分类分组展示。
- **解锁存储沿用 `growth_events`**：每枚成就仍是一行 `event_type = 'BADGE'`、
  `event_key = 'BADGE:<成就编码>'`、`exp_amount = 0` 的成长事件，因此「一经解锁永不撤销」与
  「同一成就只解锁一次」继续由 `uk_growth_events_user_key` 唯一索引在数据库层免费获得，
  **不新建成就表、不迁移任何既有数据**。
- **新增播报游标表** `achievement_notices`：记录该用户「已播报到哪一条成就事件」，
  使客户端能拿到「刚刚解锁、还没告诉过用户」的成就清单，并在播报完成后推进游标。
  游标只增不减，因此播报语义是**至少一次**：ack 丢失只会导致重播，绝不会漏播。
- **解锁体验**：首条以带动画的解锁弹层展示，其余以 Toast 依次轻提示；已解锁的成就可分享给微信好友，
  也可把成就卡片保存到相册。
- **成就页** `pages/achievement/achievement`：按分类分组展示全部 16 枚成就与各自进度，
  是分享落地页，也是成长页与「我的」页的入口目标。

### 范围与前提约定（影响验收标准的关键决策）

以下九项是本 spec 的决策骨架，验收标准全部围绕它们展开。

1. **成就 = 徽章，不是第二套并行体系。** 本 spec **不新建成就表**、不新增成就归属列，
   解锁状态的唯一事实源仍是 `growth_events` 中 `event_type = 'BADGE'`、
   `event_key = 'BADGE:<成就编码>'` 的行；解锁时刻就是该行的 `created_at`。
   既有 9 枚徽章的编码、展示名称与门槛数值**一个都不改**，因此已解锁的用户不需要任何数据迁移，
   也不会出现「成就墙上少了一枚」。本 spec 只在清单上新增 7 枚、在清单外新增分类与播报两件事。
   术语上统一改称「成就」：`成就` 与 growth-level-system 的 `徽章` 指同一个东西，
   本文档只用「成就」一个名词，服务端既有的 `BADGE` 事件类型与 `BADGE:` 前缀**不改名**
   （改名要么写迁移脚本重写历史行、要么让代码与库里的字面量长期不一致，两者都比保留旧前缀糟）。
2. **成就不发经验、不影响等级。** 全部 `BADGE` 行的 `exp_amount` 恒为 0（沿用 growth-level-system
   需求 8.3），本 spec 新增的 `SAVING_MONTH` 事实事件的 `exp_amount` 也恒为 0。
   由此本 spec **不改变任何用户的经验值与等级**，也不触碰 growth-level-system 需求 3 的六类经验事件
   与需求 2 的等级曲线。理由：成就是横向的收集品，经验是纵向的成长轨迹，两者混算会让「加一枚成就」
   变成「改一次等级经济」，每次扩充清单都要重新论证不掉级。
3. **「坚持 N 天」按历史最长连续天数算，「累计 N 天」是另一回事。** 用户原始需求里的
   「坚持 7 / 30 / 100 / 365 天」一律取 `max_streak_days ≥ N`（沿用成长体系「连续天数按记账行为发生日
   即 `created_at` 算」的口径，补记历史不增加连续天数）。既有的 `DAYS_100`「百日记账」是**累计**记账天数，
   语义不同、保留不动，两者在成就页分属「坚持」与「积累」两个分类，靠分类与描述文案区分。
4. **新增的三枚主题成就必须有可测量、可解释的事实源。** 「达人」不能是模糊的运营感觉：
   **预算达人** = 累计 3 个自然月预算达成（`BUDGET_MET` 事件数 ≥ 3）；
   **储蓄达人** = 累计 3 个「储蓄月」（`SAVING_MONTH` 事件数 ≥ 3）；
   **旅行达人** = 「旅行」分类树下的有效支出笔数 ≥ 10。前两者的事实源是只追加的成长事件，
   因此进度只增不减；第三者是实时聚合，用户改名或删除「旅行」分类会使**未解锁时的进度**回落，
   但**已解锁的成就不撤销**（与成长体系「累计统计如实反映事实源、成就只增不减」的分工一致）。
5. **储蓄月用一类新的零经验事实事件承载。** 新增 `event_type = 'SAVING_MONTH'`、
   `event_key = 'SAVING_MONTH:<YYYY-MM>'`、`exp_amount = 0` 的成长事件：某已结束自然月的
   月度收入不低于 `0.01` 且月度结余不低于月度收入的 20% 时，逐月幂等落表。
   与预算达成一样**只回看最近 3 个已结束自然月**（容忍用户三个月不活跃），
   因此「储蓄月」会随时间累积，而不是只能看当下这三个月。
   代价是要改 `ck_growth_events_type` 的取值集合（六个变七个），由本 spec 的迁移脚本承担。
6. **播报靠一个单调游标，语义是「至少一次」。** 新表 `achievement_notices` 每个用户至多一行，
   只存 `last_notified_event_id`。待播报成就 = 该用户 `event_type = 'BADGE'` 且 `id` 大于游标的行。
   客户端播报完成后调用确认接口把游标推进到本次已展示的最大事件 id；游标更新取
   `GREATEST(旧值, 新值)`，因此并发与重复确认都安全。**确认失败只会重播、不会漏播**——
   选择「至少一次」而不是「恰好一次」，是因为漏播一枚成就是产品事故，重播一次只是轻微冗余，
   且「恰好一次」要求客户端与服务端就「已经播到哪儿」达成分布式共识，代价与收益完全不成比例。
7. **迁移时把历史成就一次性标记为已播报。** 迁移脚本按用户回填游标为该用户当前最大 `BADGE` 事件 id，
   否则存量用户升级后第一次打开小程序会被 9 枚历史成就连续轰炸。
8. **成就的任何故障都不得阻断记账、预算、登录、注销、邀请等主路径。** 与成长体系同一条原则：
   成就判定发生在成长体系的结算之内（业务事务提交之后、独立事务内），异常就地捕获只记告警日志；
   记账接口的响应字段集与状态码**不因成就解锁而变化**（成就绝不进记账响应），
   客户端的播报请求失败一律静默降级。本 spec **不放宽**成长体系既有的耗时预算：
   结算仍是至多 1000 毫秒、记账接口端到端仍是至多 2000 毫秒。
9. **分享图片在客户端生成，服务端不产图不存图。** 系统当前无对象存储（与 invite-system 的
   小程序码只做内存缓存、不落盘同源）。成就卡片由 miniapp 用 canvas 绘制后保存到相册，
   转发走微信 `onShareAppMessage` 的小程序卡片，服务端只下发成就名称、描述与解锁时刻。

### 与其它 spec 的关系

- **依赖 growth-level-system（已实现，`V32__user_growth.sql`）**：本 spec 复用其
  `growth_events` / `user_growth` 两张表、`GrowthSettlementService` 结算编排、
  `GrowthBadgeCatalog` 成就清单、`GrowthCalendarService` 连续天数与累计天数、
  `GrowthBudgetEvaluator` 预算达成判定。
  本 spec **取代**该 spec 的三条约定，其余全部继续生效：
  需求 8.1「实现且仅实现 9 枚徽章」→ 改为本 spec 需求 1 的 16 枚；
  需求 8.8「按该表格顺序返回」→ 改为本 spec 需求 1 的分类顺序；
  需求 3.10「单次结算写入的成长事件条数不超过 1016 条」→ 改为本 spec 需求 4 的 1026 条。
  该 spec 需求 3.1「只发放六类经验事件、不发放清单之外任何**带正经验**的事件」继续成立：
  本 spec 新增的事件经验值恒为 0。
- **依赖 invite-system（已实现）**：`INVITE_1` 成就的事实源仍是 `invite_relations` 中
  `inviter_id = 当前用户` 且 `status = 'REGISTERED'` 的行，本 spec 只读该表、不改其任何行。
- **依赖账本协作（`ledger_members`，已实现）**：`COLLAB_1` 成就的事实源是「该用户拥有的账本中
  存在 `role = 'EDITOR'` 的成员行」。本 spec 只读该表。
- **迁移版本号**：`src/main/resources/db/migration` 当前最大版本号为 32（`V32__user_growth.sql`），
  `V30` 由 user-feedback-system spec 预占（`V30__feedback.sql`，尚未落地）。本 spec 取
  **`V33__achievement.sql`**。
- 与 account-ledger-redesign、auth-email-passwordless 无功能耦合。

## Glossary

- **成就系统（Achievement_System）**：本 spec 涉及的服务端成就清单、成就解锁判定、储蓄月判定、
  播报游标维护与成就查询接口的整体。
- **成长体系（Growth_System）**：growth-level-system spec 已实现的成长档案、成长事件、经验结算、
  等级换算与成长查询接口的整体。本 spec 只扩展其成就清单与事实源，不改其经验与等级。
- **成就（achievement）**：一枚固定编码的成就标识。它与 growth-level-system 所称的「徽章」是同一个东西，
  以 `event_type = 'BADGE'`、`event_key = 'BADGE:<成就编码>'`、`exp_amount = 0` 的成长事件表示其已解锁。
- **成就编码**：区分大小写的固定字符串，取自需求 1 第 1 条列出的 16 个取值。
- **成就分类**：区分大小写的枚举，取值 `START`、`STREAK`、`VOLUME`、`SOCIAL`、`THEME` 五者之一，
  中文展示名依次为「起步」、「坚持」、「积累」、「协作」、「主题」。
- **已解锁**：该用户存在对应成就编码的 `BADGE` 成长事件。
- **解锁时刻**：该成就对应 `BADGE` 成长事件的 `created_at`。
- **成就事件 id**：该成就对应 `BADGE` 成长事件的 `id`（`growth_events` 主键，自增，只增不减）。
- **成就视图（achievement view）**：单枚成就随接口下发的字段组合，字段集见需求 6 第 2 条。
- **统计口径（metric）**：成就门槛所依据的统计量，取值 `RECORD_COUNT`、`MAX_STREAK`、`TOTAL_DAYS`、
  `BUDGET_MET_COUNT`、`SAVING_MONTH_COUNT`、`FIRST_INVITE_EVENT`、`COLLAB_MEMBER_COUNT`、
  `TRAVEL_RECORD_COUNT` 八者之一，各自定义见需求 3。
- **有效记账交易**：沿用 growth-level-system 的定义——同时满足 `created_by` 等于该用户 id、
  `deleted_at` 为 NULL、`type` 属于 `expense`/`income`、`ledger_id` 非 NULL 四条的 `transactions` 行。
- **累计记账笔数**：该用户有效记账交易的行数（统计口径 `RECORD_COUNT`）。
- **历史最长连续天数**：`user_growth.max_streak_days`，记账日历中最长连续自然日区间所含日期个数
  （统计口径 `MAX_STREAK`）。
- **累计记账天数**：`user_growth.total_record_days`，等于该用户 `event_type = 'DAILY_RECORD'` 的
  成长事件条数（统计口径 `TOTAL_DAYS`）。
- **协作成员数**：`ledger_members` 中 `role = 'EDITOR'` 且 `ledger_id` 属于该用户拥有的账本
  （`ledgers.user_id` 等于该用户 id）的行数（统计口径 `COLLAB_MEMBER_COUNT`）。
- **旅行分类树**：某账本内名称去首尾空白后等于 `旅行` 的 `kind = 'EXPENSE'` 分类，及其全部子分类
  （`parent_id` 指向该分类的行）。
- **旅行记账笔数**：该用户 `type = 'expense'` 的有效记账交易中，`category_id` 落在旅行分类树内的行数
  （统计口径 `TRAVEL_RECORD_COUNT`）。
- **时区口径**：本 spec 全部自然日与自然月边界的计算时区，取 `Asia/Shanghai` 即固定偏移 UTC+08:00
  （与 growth-level-system 同一口径，不随夏令时变化）。
- **结算日**：一次结算的执行时刻按时区口径折算所得的日期。
- **已结束自然月**：按时区口径其结束时刻早于或等于当前时刻的自然月；当前时刻所属的自然月不是已结束自然月。
- **月度收入合计**：`created_by` 等于该用户 id、`type` 为 `income`、`deleted_at` 为 NULL、
  `ledger_id` 非 NULL、`occurred_at` 落在按时区口径界定的毫秒级半开区间
  [该月 1 日 00:00:00.000, 次月 1 日 00:00:00.000) 的 `transactions` 行的 `amount` 合计，
  保留 2 位小数；查询结果为空时按 `0.00` 计。
- **月度支出合计**：口径同上（含同一毫秒级半开区间与空结果按 `0.00` 计），`type` 取 `expense`。
- **月度结余**：月度收入合计减去月度支出合计，可为负。
- **储蓄门槛值**：月度收入合计乘以 0.2 后对第 3 位小数四舍五入保留 2 位小数所得的取值。
- **储蓄月**：某已结束自然月 M 满足：该用户在 M 的月度收入合计大于或等于 `0.01`，
  且 M 的月度结余大于或等于 M 的储蓄门槛值。
- **储蓄月事件**：`event_type = 'SAVING_MONTH'`、`event_key = 'SAVING_MONTH:<YYYY-MM>'`、
  `exp_amount = 0` 的成长事件，表示该自然月已判定为储蓄月。
- **储蓄月数**：该用户储蓄月事件的条数（统计口径 `SAVING_MONTH_COUNT`）。
- **预算达成月数**：该用户 `event_type = 'BUDGET_MET'` 的成长事件条数（统计口径 `BUDGET_MET_COUNT`）。
- **结算**：沿用 growth-level-system 需求 9 的定义——成长体系的一次幂等计算过程，
  在新增有效记账交易的业务事务提交之后、或已认证用户请求成长概览时触发，于独立事务内执行。
  成就解锁判定与储蓄月判定都发生在结算之内。
- **播报游标**：`achievement_notices` 表的一行，主键 `user_id`，只存该用户已播报到的最大成就事件 id。
- **待播报成就**：该用户 `event_type = 'BADGE'` 且 `id` 大于其播报游标取值的成长事件所对应的成就；
  无播报游标行时按游标取值 0 处理。
- **播报**：miniapp 就一枚待播报成就向用户展示解锁弹层或 Toast 的一次行为。
- **成就页**：miniapp 新增页面 `pages/achievement/achievement`，按分类分组展示全部成就与进度，
  同时是成就分享的落地页。
- **成就卡片**：miniapp 在成就页用 canvas 绘制的一张图片，含成就名称、成就描述、解锁日期与产品名「有余」。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌
  （与 growth-level-system、invite-system 同一口径）。
- **miniapp**：微信小程序端（uni-app / Vue 3）。

## Requirements

### 需求 1：成就清单与单一事实源

**用户故事：** 作为用户，我希望看到一份说得清的成就清单——每枚成就叫什么、要做到什么、我离它还有多远。

#### 验收标准

1. THE 成就系统 SHALL 实现且仅实现以下 16 枚成就，其编码、展示名称、分类、统计口径与门槛如下，
   且列表顺序即展示顺序；成就编码 SHALL 区分大小写并与下表字面量逐字符相同，
   每个展示名称的长度 SHALL 落在 2 到 10 个 Unicode 码点的闭区间内，
   且 THE 成就系统 SHALL 不实现下表之外的任何成就编码：

   | 序号 | 成就编码 | 名称 | 分类 | 统计口径 | 门槛 |
   | --- | --- | --- | --- | --- | --- |
   | 1 | `FIRST_RECORD` | 开张 | `START` | `RECORD_COUNT` | 1 |
   | 2 | `STREAK_7` | 七日不辍 | `STREAK` | `MAX_STREAK` | 7 |
   | 3 | `STREAK_30` | 卅日成习 | `STREAK` | `MAX_STREAK` | 30 |
   | 4 | `STREAK_100` | 百日不辍 | `STREAK` | `MAX_STREAK` | 100 |
   | 5 | `STREAK_365` | 岁岁有余 | `STREAK` | `MAX_STREAK` | 365 |
   | 6 | `RECORD_10` | 小有账目 | `VOLUME` | `RECORD_COUNT` | 10 |
   | 7 | `RECORD_100` | 百笔有余 | `VOLUME` | `RECORD_COUNT` | 100 |
   | 8 | `RECORD_500` | 五百笔在册 | `VOLUME` | `RECORD_COUNT` | 500 |
   | 9 | `RECORD_1000` | 千笔如一 | `VOLUME` | `RECORD_COUNT` | 1000 |
   | 10 | `DAYS_100` | 百日记账 | `VOLUME` | `TOTAL_DAYS` | 100 |
   | 11 | `INVITE_1` | 同行有余 | `SOCIAL` | `FIRST_INVITE_EVENT` | 1 |
   | 12 | `COLLAB_1` | 共账之始 | `SOCIAL` | `COLLAB_MEMBER_COUNT` | 1 |
   | 13 | `BUDGET_MET` | 预算达标 | `THEME` | `BUDGET_MET_COUNT` | 1 |
   | 14 | `BUDGET_MASTER` | 预算达人 | `THEME` | `BUDGET_MET_COUNT` | 3 |
   | 15 | `SAVING_MASTER` | 储蓄达人 | `THEME` | `SAVING_MONTH_COUNT` | 3 |
   | 16 | `TRAVEL_MASTER` | 旅行达人 | `THEME` | `TRAVEL_RECORD_COUNT` | 10 |

2. THE 成就系统 SHALL 为 16 枚成就中的每一枚下发一条中文描述，其内容 SHALL 表述该成就第 1 条中门槛
   对应的达成条件，其长度 SHALL 落在 6 到 30 个 Unicode 码点的闭区间内；
   WHERE 某枚成就的门槛数值大于 1 THE 成就系统 SHALL 使其描述包含该门槛数值的十进制写法；
   THE 成就系统 SHALL 使 16 条描述两两不相同，且 SHALL 不在描述中出现成就编码、成就分类枚举取值
   与统计口径枚举取值三类原始字面量。
3. THE 成就系统 SHALL 把成就的编码、展示名称、描述、分类、统计口径、门槛数值与展示顺序统一在服务端代码中
   以单一常量定义为唯一事实源，SHALL 使该常量恰好含 16 项、使 16 个编码两两不相同、
   使 16 个展示名称两两不相同、使展示顺序取 1 到 16 的连续整数且两两不相同，
   SHALL 使展示名称、描述与门槛数值随成就查询接口下发，
   SHALL 不在迁移脚本、数据库或 miniapp 中重复定义其中任何一项，
   且 SHALL 不提供在运行期修改该常量任一项取值的接口或配置项。
4. THE 成就系统 SHALL 保持既有 9 枚成就（`FIRST_RECORD`、`RECORD_10`、`RECORD_100`、`RECORD_1000`、
   `STREAK_7`、`STREAK_30`、`DAYS_100`、`BUDGET_MET`、`INVITE_1`）的编码、展示名称与门槛数值
   与 growth-level-system 已实现的取值逐项相同。
5. THE 成就系统 SHALL 把既有 `BUDGET_MET` 成就的统计口径记为 `BUDGET_MET_COUNT` 且门槛记为 1；
   WHEN 判定该成就的解锁条件 THEN THE 成就系统 SHALL 得到与「存在至少一条 `event_type = 'BUDGET_MET'`
   的成长事件」相同的结论（口径改写不改变判定结果）。
6. THE 成就系统 SHALL 使成就编码集合是 growth-level-system 既有 9 个徽章编码集合的超集，
   且 SHALL 不删除、不重命名其中任何一个编码。
7. THE 成就系统 SHALL 按第 1 条表格的序号顺序返回成就列表；WHEN 同一用户在任意两个时刻请求成就清单
   THEN THE 成就系统 SHALL 返回逐项相同的成就编码序列，且该顺序 SHALL 不随解锁状态、解锁时刻、
   统计口径取值与请求时刻变化。
8. THE 成就系统 SHALL 使第 1 条表格中同一分类的成就在列表中连续出现，且分类的首次出现顺序
   SHALL 为 `START`、`STREAK`、`VOLUME`、`SOCIAL`、`THEME`。
9. THE 成就系统 SHALL 使每枚成就的门槛数值为落在 1 到 1000 的闭区间内的整数；
   WHERE 某枚成就的统计口径为存在型（`FIRST_INVITE_EVENT`）THE 成就系统 SHALL 使其门槛数值为 1。
10. THE 成就系统 SHALL 不新建成就表、SHALL 不为成就新增任何数据库列，且 SHALL 以 `growth_events` 中
    `event_type = 'BADGE'`、`event_key = 'BADGE:<成就编码>'` 的行作为解锁状态的唯一存储形式。
11. THE 成就系统 SHALL 使其写入的全部 `BADGE` 成长事件的 `exp_amount` 为 0，
    且 SHALL 不写入 `exp_amount` 不为 0 的 `BADGE` 行；WHEN 一次结算解锁 1 至 16 枚成就
    THEN THE 成就系统 SHALL 使该用户 `user_growth` 的经验值与等级两项取值与该次结算前逐项相同。
12. IF `growth_events` 中存在 `event_type = 'BADGE'` 且 `event_key` 在 `BADGE:` 前缀之后的部分
    不等于第 1 条表格中任何一个成就编码的行 THEN THE 成就系统 SHALL 在成就清单、已解锁成就数与
    待播报成就中忽略该行、SHALL 使成就视图列表项数仍为 16 且成就总数仍为 16、
    SHALL 记录一条含用户 id 与该行 `event_key` 的告警日志、SHALL 不返回错误，
    且 SHALL 保持该行的全部列取值不变。
13. IF 应用启动时的成就清单常量不满足第 3 条的项数、编码唯一性、展示名称唯一性、展示顺序连续性
    与第 9 条的门槛取值范围中的任何一条 THEN THE 应用 SHALL 启动失败，
    并 SHALL 记录一条指明首个违规项的错误日志。

### 需求 2：成就解锁的判定与不可撤销

**用户故事：** 作为用户，我希望我拿到的成就就一直是我的，不会因为我删了几笔旧账或者改了分类就被收回。

#### 验收标准

1. WHEN 结算发现某枚成就的对应统计量大于或等于其门槛数值 AND 该用户尚无该成就编码对应的 `BADGE` 事件
   THEN THE 成就系统 SHALL 写入一条 `user_id` 等于本次结算所属用户 id、`event_type` 为 `BADGE`、
   `event_key` 为 `BADGE:<成就编码>`、`exp_amount` 为 0、`created_at` 为本次结算执行时的服务端时刻的
   成长事件。
2. WHEN 某统计量恰好等于某枚成就的门槛数值 THEN THE 成就系统 SHALL 判定该成就的解锁条件成立
   （门槛取等号即解锁）。
3. THE 成就系统 SHALL 以「该用户存在对应成就编码的 `BADGE` 事件」作为已解锁的唯一判定依据。
4. WHEN 用户删除交易、清空回收站、修改交易分类、删除分类、下调或删除预算、移除协作成员，
   或其被邀请人注销 THEN THE 成就系统 SHALL 保持该用户全部 `BADGE` 事件的行数与全部列取值不变，
   且 SHALL 使该用户的已解锁成就数不下降（已解锁的成就不撤销）。
5. WHEN 同一枚成就的解锁条件在多次结算中反复成立 THEN THE 成就系统 SHALL 使该用户该成就编码的
   `BADGE` 事件行数为 1，且 SHALL 保持首次写入那一行的 `id` 与 `created_at` 取值不变。
6. WHEN 一次结算发现某个统计口径同时跨越同一口径下 2 至 5 枚成就的门槛 THEN THE 成就系统 SHALL 在该次结算内
   为这些成就各写入一条 `BADGE` 事件（跨门槛不漏发较低门槛的成就，`RECORD_COUNT` 口径下最多 5 枚同时解锁）；
   THE 成就系统 SHALL 按需求 1 第 1 条表格的序号升序写入这些事件，
   使其成就事件 `id` 的相对大小顺序与该序号顺序一致。
7. THE 成就系统 SHALL 以 `growth_events` 的 `(user_id, event_key)` 唯一索引作为「同一成就只解锁一次」的
   唯一保证手段，且 SHALL 不以应用层的先查询后写入作为该唯一性的保证手段。
8. WHEN 结算尝试写入的 `BADGE` 事件的 `(user_id, event_key)` 已存在 THEN THE 成就系统 SHALL 放弃该次插入、
   SHALL 继续完成本次结算的其余判定与写入步骤、SHALL 保持本次结算已写入的其它成长事件不回滚，
   且 SHALL 不返回错误（唯一键冲突不计为需求 4 第 14 条所述的异常）。
9. WHEN 同一用户的两个及以上结算在 1000 毫秒内并发执行 THEN THE 成就系统 SHALL 使该用户任一成就编码的
   `BADGE` 事件行数终态为至多 1，且 SHALL 不向记账、预算、登录、注销与邀请路径传播错误。
10. THE 成就系统 SHALL 使 `BADGE:` 前缀为成就事件的独占命名空间：WHERE 某成就编码与成长事件类型或
    经验事件键同名（`FIRST_RECORD`、`STREAK_7`、`STREAK_30`、`BUDGET_MET` 四者）
    THE 成就系统 SHALL 仅以 `event_type` 为 `BADGE` 且 `event_key` 等于 `BADGE:<编码>` 的行判定该成就已解锁，
    且 SHALL 不把 `BADGE` 行计入任何统计口径的取值。
11. IF 某枚成就的解锁条件已成立但因结算失败或结算被节流而尚未写入对应的 `BADGE` 事件
    THEN THE 成就系统 SHALL 返回该成就为未解锁、SHALL 返回其当前值等于门槛数值、
    SHALL 以空值返回其解锁时刻，且 SHALL 不返回错误。
12. THE 成就系统 SHALL 在每次结算内对该用户 16 枚成就中尚未解锁的每一枚各判定一次，
    且 SHALL 不因触发本次结算的操作类型而跳过其中任何一枚（判定范围与触发原因无关）。
13. IF 某枚成就的统计量小于其门槛数值 THEN THE 成就系统 SHALL 不为该成就写入 `BADGE` 事件、
    SHALL 返回该成就为未解锁，且 SHALL 以空值返回其解锁时刻与成就事件 id。
14. THE 成就系统 SHALL 使同一次结算内同一统计口径下全部成就的判定使用该口径的同一个取值
    （每个统计口径在单次结算内只取值一次），且 SHALL 使该取值的读取发生在本次结算的成就事件写入之前。

### 需求 3：统计口径

**用户故事：** 作为用户，我希望每枚成就的进度是按一条明确的规则算的，我看到的「差 3 笔」就真的是差 3 笔。

#### 验收标准

1. THE 成就系统 SHALL 把 `RECORD_COUNT` 取为该用户的累计记账笔数、把 `MAX_STREAK` 取为
   `user_growth.max_streak_days`、把 `TOTAL_DAYS` 取为 `user_growth.total_record_days`；
   WHEN 同一用户在同一时刻分别按本 spec 与按 growth-level-system 需求 4 与需求 7 的口径求值
   THEN THE 成就系统 SHALL 使这三个取值逐项相等。
2. THE 成就系统 SHALL 把 `MAX_STREAK` 与 `TOTAL_DAYS` 的事实源限定为记账日历
   （`event_type` 区分大小写等于 `DAILY_RECORD` 的成长事件），SHALL 按时区口径
   `Asia/Shanghai`（固定偏移 UTC+08:00）界定连续天数所依据的自然日边界，
   且 SHALL 不读取 `transactions` 表计算这两个口径的取值；
   WHEN 用户为历史日期补记交易 THEN THE 成就系统 SHALL 不因该补记增加 `MAX_STREAK` 的取值。
3. THE 成就系统 SHALL 把 `COLLAB_MEMBER_COUNT` 取为 `ledger_members` 中同时满足
   `role` 区分大小写等于 `EDITOR`、`ledger_id` 属于 `ledgers.user_id` 等于该用户 id 的账本两条的
   **成员行行数**（按行计数而非按去重用户计数）；WHEN 同一个用户以 `EDITOR` 身份加入该用户的 2 个账本
   THEN THE 成就系统 SHALL 把 `COLLAB_MEMBER_COUNT` 计为 2。
4. THE 成就系统 SHALL 把 `role` 等于 `OWNER` 的成员行、`user_id` 等于该用户本人 id 的成员行，
   以及该用户作为 `EDITOR` 加入他人账本的成员行三类排除在 `COLLAB_MEMBER_COUNT` 之外，
   并 SHALL 以 `ledgers.user_id` 作为账本归属的唯一依据
   （`COLLAB_1` 衡量「有别人加入了我的账本」，不衡量「我加入了别人的账本」）。
5. THE 成就系统 SHALL 只对 `ledger_members`、`ledgers`、`categories` 与 `transactions` 四表执行
   读取语句，且 SHALL 不对这四表执行任何插入、更新或删除语句。
6. THE 成就系统 SHALL 把 `BUDGET_MET_COUNT` 取为该用户 `event_type` 区分大小写等于 `BUDGET_MET` 的
   成长事件条数，SHALL 把 `event_type` 为 `BADGE` 的行（含 `event_key` 为 `BADGE:BUDGET_MET` 的行）
   排除在该计数之外，且 SHALL 不重新判定这些自然月是否达成预算
   （预算达成的判定由 growth-level-system 需求 5 承担）。
7. THE 成就系统 SHALL 把 `SAVING_MONTH_COUNT` 取为该用户 `event_type` 区分大小写等于 `SAVING_MONTH` 的
   成长事件条数，且 SHALL 把 `event_type` 为 `BADGE` 的行排除在该计数之外。
8. THE 成就系统 SHALL 把 `FIRST_INVITE_EVENT` 取为「该用户存在 `event_type` 区分大小写等于
   `FIRST_INVITE` 且 `event_key` 等于 `FIRST_INVITE` 的成长事件」时的 1、其余情形的 0，
   SHALL 把 `event_type` 为 `BADGE` 的行排除在该判定之外，
   且 SHALL 使该存在型口径的取值只取 0 或 1 两者之一。
9. THE 成就系统 SHALL 把旅行分类树取为 `categories` 中 `kind` 区分大小写等于 `EXPENSE`
   且 `name` 去首尾空白后逐字符等于 `旅行` 的分类，及其 `parent_id` 指向该分类的全部子分类；
   THE 成就系统 SHALL 以逐字符相等判定分类名称（SHALL 不使用前缀匹配、包含匹配或模糊匹配），
   SHALL 把 `category_id` 等于该父分类自身的交易计入旅行分类树内的交易，
   SHALL 按 `categories` 只有一层 `parent_id` 的既有结构把层级上界取为 2 且 SHALL 不递归展开更深层级，
   SHALL 不要求该分类的 `user_id` 等于该用户 id（协作账本内的分类归账本所有者），
   且 SHALL 使同一条交易在该计数中至多被计 1 次。
10. THE 成就系统 SHALL 把 `TRAVEL_RECORD_COUNT` 取为同时满足 `transactions.created_by` 等于该用户 id、
    `type` 区分大小写等于 `expense`、`deleted_at` 为 NULL、`ledger_id` 非 NULL、
    `category_id` 落在第 9 条所述旅行分类树内五条的 `transactions` 行数，
    并 SHALL 以 `transactions.created_by` 作为该口径的唯一归属依据、
    SHALL 不使用 `transactions.user_id` 参与该归属。
11. THE 成就系统 SHALL 跨该用户记账的全部账本合并计算 `TRAVEL_RECORD_COUNT`，
    且 SHALL 不按会话账本或 `X-Ledger-Id` 头过滤该计数。
12. WHEN 用户把「旅行」分类改名、删除该分类，或把交易改到其它分类，使 `TRAVEL_RECORD_COUNT` 的取值下降
    THEN THE 成就系统 SHALL 使 `TRAVEL_MASTER` 的当前值等于下降后的取值与门槛数值 10 两者中的较小者、
    SHALL 保持已写入的 `BADGE:TRAVEL_MASTER` 事件的全部列取值不变、
    SHALL 使该成就的是否已解锁在已解锁后保持为真，且 SHALL 不返回错误。
13. THE 成就系统 SHALL 使全部八个统计口径的取值为落在 0 到 9223372036854775807 闭区间内的整数，
    并 SHALL 以 64 位整型承载这些取值；IF 某个统计口径的查询结果为空，或该用户尚无 `user_growth` 行
    THEN THE 成就系统 SHALL 以 0 作为该口径的取值。
14. IF 某个统计口径的聚合查询抛出异常 THEN THE 成就系统 SHALL 以 0 作为该口径本次的取值、
    SHALL 记录一条含用户 id 与该口径枚举取值的告警日志、SHALL 继续返回其余口径的取值，
    且 SHALL 不向响应暴露服务端错误码（与需求 6 第 7 条一致）。
15. WHEN 某用户的有效记账交易为 10 万笔 AND 其拥有账本下的成员行为 100 行 THEN THE 成就系统 SHALL 使
    八个统计口径的求值合计服务端耗时不超过 500 毫秒（不含网络传输耗时）。
16. THE 成就系统 SHALL 使同一个统计口径在单次结算内或单次查询请求内只求值一次，
    并 SHALL 在该次结算或该次请求内对全部依赖该口径的成就复用同一个取值。

### 需求 4：储蓄月判定与结算集成

**用户故事：** 作为用户，我希望我某个月确实存下了钱这件事能被系统记住；作为开发者，我要的是这些新判定绝不拖慢记账。

#### 验收标准

1. WHEN 一次结算开始执行 THEN THE 成就系统 SHALL 判定「结算日所属自然月的前 1 个、前 2 个与前 3 个自然月」
   共 3 个已结束自然月是否为储蓄月，且 SHALL 不判定结算日所属的自然月；THE 成就系统 SHALL 把结算日取为
   本次结算执行时刻按时区口径 `Asia/Shanghai`（固定偏移 UTC+08:00）折算所得的日期、
   SHALL 使回看窗口恒为 3 个已结束自然月且与该用户注册时刻、本次结算的触发来源无关；
   WHERE 结算日所属自然月为某年 1 月 THE 成就系统 SHALL 把这 3 个回看月取为上一年的 10 月、11 月与 12 月。
2. WHEN 结算判定某已结束自然月 M 为储蓄月 AND 该用户尚无 `SAVING_MONTH:M` 事件
   THEN THE 成就系统 SHALL 写入一条 `event_type` 为 `SAVING_MONTH`、`event_key` 为 `SAVING_MONTH:M`、
   `exp_amount` 为 0、`created_at` 为本次结算执行时的服务端时刻的成长事件，其中 `event_key` SHALL 由
   `SAVING_MONTH:` 前缀、4 位年份、连字符 `-` 与 2 位左侧补零的月份依次拼成，其长度 SHALL 恒为 20 个字符。
3. THE 成就系统 SHALL 把「某已结束自然月 M 为储蓄月」判定为：该用户在 M 的月度收入合计大于或等于 `0.01`，
   且 M 的月度结余大于或等于 M 的储蓄门槛值；WHEN M 的月度结余恰好等于 M 的储蓄门槛值
   THEN THE 成就系统 SHALL 判定 M 为储蓄月（取等号即成立）。
4. IF 某已结束自然月 M 的月度收入合计小于 `0.01` THEN THE 成就系统 SHALL 判定 M 不是储蓄月
   （无收入不算存钱）；IF M 的月度收入查询结果为空 THEN THE 成就系统 SHALL 把 M 的月度收入合计按 `0.00` 计
   并判定 M 不是储蓄月。
5. IF 某已结束自然月 M 的月度结余小于 M 的储蓄门槛值，或 M 的月度结余为负
   THEN THE 成就系统 SHALL 判定 M 不是储蓄月。
6. THE 成就系统 SHALL 以交易的 `occurred_at` 落在按时区口径界定的毫秒级半开区间
   [该月 1 日 00:00:00.000, 次月 1 日 00:00:00.000) 作为月度收入合计与月度支出合计的月份归属依据；
   WHEN 某交易的 `occurred_at` 恰好等于次月 1 日 00:00:00.000 THEN THE 成就系统 SHALL 把该交易归属于次月；
   THE 成就系统 SHALL 不使用 `created_at` 参与该归属。
7. THE 成就系统 SHALL 以 `transactions.created_by` 等于该用户 id 作为月度收入合计与月度支出合计的
   唯一归属依据、SHALL 跨该用户记账的全部账本合并计算，且 SHALL 把 `deleted_at` 非空的行、
   `ledger_id` 为 NULL 的行与 `type` 等于 `transfer` 的行三类排除在这两项合计之外
   （与 Glossary 的「有效记账交易」一致）。
8. THE 成就系统 SHALL 以 `BigDecimal` 承载月度收入合计、月度支出合计、储蓄门槛值与月度结余的计算与比较，
   SHALL 把储蓄门槛值取为「月度收入合计乘以 0.2 后对第 3 位小数四舍五入保留 2 位小数」所得的取值，
   SHALL 使这四项均保留 2 位小数，且 SHALL 不使用浮点类型参与该计算与比较。
9. WHEN 用户在某已结束自然月 M 的 `SAVING_MONTH:M` 事件已写入之后新增该月的支出交易或删除该月的收入交易
   THEN THE 成就系统 SHALL 不删除该行，且 SHALL 保持该行的全部列取值不变（储蓄月一经判定不撤销）。
10. IF 某储蓄月早于结算日所属自然月 4 个月及以上 AND 该月的 `SAVING_MONTH` 事件尚未写入
    THEN THE 成就系统 SHALL 不为该月写入储蓄月事件（回看窗口固定为 3 个已结束自然月，使单次结算的查询次数有界）。
11. THE 成就系统 SHALL 使单次结算内为成就判定与储蓄月判定新增的数据库读查询次数按执行的 SQL 语句条数计
    且不超过 3 条（1 条协作成员数、1 条旅行记账笔数、1 条三个回看月按月份与交易类型分组的金额合计），
    SHALL 使该条数为常量上界、不随该用户拥有的账本数量、分类数量与交易笔数增长，
    且 SHALL 以本次结算已加载的该用户成长事件集合完成 `SAVING_MONTH` 与 `BADGE` 事件的存在性判定、
    SHALL 不为该存在性判定新增数据库查询。
12. THE 成就系统 SHALL 使单次结算写入的成长事件条数不超过 1026 条：
    至多 1000 条 `DAILY_RECORD`，加至多 1 条 `FIRST_RECORD`、2 条 `STREAK`、3 条 `BUDGET_MET`、
    1 条 `FIRST_INVITE`、3 条 `SAVING_MONTH` 与 16 条 `BADGE`。
13. THE 成就系统 SHALL 在成长体系的同一次结算事务内完成成就判定、成就事件写入与储蓄月事件写入，
    且 SHALL 不新增结算触发时机、SHALL 不引入定时任务、消息队列、`@Async` 或线程池。
14. IF 成就判定或储蓄月判定抛出任何异常 THEN THE 成就系统 SHALL 回滚本次结算事务内已执行的全部写入、
    SHALL 保持该次结算之前已提交的业务事务结果不变、SHALL 记录一条包含用户 id 且不含金额、邮箱与令牌的
    告警日志，且 SHALL 不向记账、预算、登录、注销与邀请路径传播该异常。
15. WHEN 成就判定失败 THEN THE 记账接口 SHALL 返回与判定成功时相同的 HTTP 状态码与相同的响应字段集，
    且该响应 SHALL 不包含任何成就字段。
16. WHEN 某次结算失败之后该用户再次触发结算 THEN THE 成就系统 SHALL 在该次结算内补齐上次未写入的
    `BADGE` 与 `SAVING_MONTH` 事件（判定幂等可重入，失败自愈）。
17. THE 成就系统 SHALL 使记账触发的结算的服务端处理耗时不超过 1000 毫秒、使记账接口的端到端服务端耗时
    不超过 2000 毫秒（两项均沿用 growth-level-system 需求 9 的预算，不因新增判定而放宽），
    其测量前提 SHALL 为该用户的有效记账交易不超过 10000 笔且其成长事件不超过 10000 条，
    其耗时 SHALL 按服务端进入处理到返回结果的时刻差度量、不含网络传输耗时；
    IF 结算耗时超过 1000 毫秒 THEN THE 成就系统 SHALL 记录一条含用户 id 与实际耗时的告警日志，
    且 SHALL 不中断已提交的记账结果。
18. THE 成就系统 SHALL 以 `growth_events` 的 `(user_id, event_key)` 唯一索引作为「同一自然月至多一条
    储蓄月事件」的唯一保证手段；WHEN 同一用户的两个及以上结算在 1000 毫秒内并发执行
    THEN THE 成就系统 SHALL 使该用户任一 `SAVING_MONTH:<YYYY-MM>` 事件键的行数终态为至多 1。
19. WHEN 结算尝试写入的 `SAVING_MONTH` 事件的 `(user_id, event_key)` 已存在 THEN THE 成就系统 SHALL
    放弃该次插入、SHALL 保持已存在那一行的全部列取值不变、SHALL 继续完成本次结算的其余判定与写入步骤，
    且 SHALL 不记录告警日志（唯一键冲突是预期的幂等路径）。
20. IF 某回看月的储蓄月判定不成立 THEN THE 成就系统 SHALL 不为该月写入任何成长事件、
    SHALL 不为该月写入任何负向标记或缓存行，且 SHALL 继续判定其余回看月。

### 需求 5：待播报成就与播报游标

**用户故事：** 作为用户，我希望刚解锁的成就当场就告诉我，而且同一枚成就不会在我确认之后又弹一遍。

#### 验收标准

1. THE 成就系统 SHALL 为每个用户维护至多一行播报游标，主键为 `user_id`，其 `last_notified_event_id`
   取值 SHALL 为大于或等于 0 的整数。
2. THE 成就系统 SHALL 把待播报成就定义为该用户 `event_type` 为 `BADGE` 且 `id` 大于其播报游标
   `last_notified_event_id` 取值的成长事件所对应的成就。
3. WHEN 某用户没有播报游标行 THEN THE 成就系统 SHALL 按游标取值 0 计算待播报成就。
4. THE 成就系统 SHALL 提供已认证用户查询自身待播报成就的接口，其成功响应的顶层字段集 SHALL 恰好为
   「待播报成就项列表、待播报总条数」2 项，SHALL 按成就事件 id 升序返回至多 10 项待播报成就
   （先解锁的先播报），SHALL 使每个待播报成就项的字段集恰好为「成就编码、展示名称、描述、分类、
   解锁时刻、成就事件 id」6 项，且 SHALL 使待播报总条数为落在 0 到 16 闭区间内的整数。
5. IF 某用户的待播报成就多于 10 项 THEN THE 成就系统 SHALL 在本次响应中返回成就事件 id 最小的 10 项、
   SHALL 使该响应的待播报总条数取截断前的全部待播报项个数（而非本次返回的项数），
   并 SHALL 使剩余项在游标推进后的后续请求中按成就事件 id 升序返回。
6. THE 成就系统 SHALL 提供已认证用户推进自身播报游标的接口，其请求 SHALL 携带一个必填整数入参
   `lastEventId`，表示本次已播报到的最大成就事件 id，其允许取值范围 SHALL 为 0 到该用户当前最大
   `BADGE` 成长事件 `id` 的闭区间（该用户没有任何 `BADGE` 成长事件时该上界按 0 计）。
7. WHEN 已认证用户以取值大于当前游标且落在允许取值范围内的 `lastEventId` 请求推进游标
   THEN THE 成就系统 SHALL 把该用户的 `last_notified_event_id` 置为 `lastEventId`、
   SHALL 把该行的 `updated_at` 置为本次请求的服务端时刻，
   且 SHALL 使成功响应的顶层字段集恰好为「推进后的游标取值」1 项。
8. WHEN 已认证用户以取值小于或等于当前游标的 `lastEventId` 请求推进游标 THEN THE 成就系统 SHALL 保持
   该用户的 `last_notified_event_id` 取值不变、SHALL 在响应中按第 7 条相同的字段集返回当前游标取值，
   且 SHALL 不返回错误（重复确认幂等）。
9. THE 成就系统 SHALL 使播报游标的取值单调不减：WHEN 对同一用户执行任意次推进请求
   THEN THE 成就系统 SHALL 使每次请求后的 `last_notified_event_id` 大于或等于该次请求前的取值。
10. WHEN 同一用户的两个及以上推进请求在 1000 毫秒内并发执行 THEN THE 成就系统 SHALL 使该用户
    `last_notified_event_id` 的终态等于这些请求的 `lastEventId` 取值与请求前游标取值中的最大值，
    且 SHALL 使该用户在 `achievement_notices` 表中的行数终态为 1。
11. WHEN 某用户尚无播报游标行且请求推进游标 THEN THE 成就系统 SHALL 在该次请求内创建该用户的播报游标行，
    并写入 `created_at` 与 `updated_at` 为同一服务端时刻。
12. IF `lastEventId` 缺失、为空值、无法解析为整数、小于 0，或大于第 6 条规定的允许取值上界
    THEN THE 成就系统 SHALL 拒绝该请求、SHALL 返回错误体 `{code, message, field}` 且其 `code` 取
    `ACHIEVEMENT_ACK_PARAM_INVALID`、`field` 取 `lastEventId`、`message` 取指示该入参取值不合法的
    中文文案，并 SHALL 保持 `achievement_notices` 表的行数与全部列取值不变。
13. WHEN 某用户没有任何 `BADGE` 成长事件 AND 该用户以取值 0 的 `lastEventId` 请求推进游标
    THEN THE 成就系统 SHALL 接受该请求并返回游标取值 0。
14. THE 成就系统 SHALL 只对 `growth_events` 表执行读取语句以计算待播报成就，
    且 SHALL 不在待播报查询与游标推进请求内触发结算、SHALL 不向 `growth_events` 与 `user_growth`
    两表执行任何插入、更新或删除语句。
15. WHEN 已认证用户请求待播报成就或推进播报游标 THEN THE 成就系统 SHALL 在服务端处理耗时不超过
    2000 毫秒内返回成功结果或错误标识（不含网络传输耗时）。
16. WHEN 用户请求待播报成就 AND 该用户当前无待播报成就（含成就播报完成后游标已推进且期间无新成就解锁的
    情形）THEN THE 成就系统 SHALL 返回空列表与待播报总条数 0，且 SHALL 不返回错误。
17. WHEN 已认证用户请求待播报成就 THEN THE 成就系统 SHALL 保持 `achievement_notices` 表的行数与
    全部列取值不变；WHEN 该用户在期间无新成就解锁且未推进游标时连续两次请求待播报成就
    THEN THE 成就系统 SHALL 返回相同的项、相同顺序与相同的待播报总条数（查询不推进游标、可重复读取）。
18. IF 某次游标推进请求未被服务端处理完成（客户端超时、网络中断或返回错误标识）
    THEN THE 成就系统 SHALL 保持该用户 `last_notified_event_id` 取值不变，
    且 SHALL 在后续待播报查询中继续返回本次已播报过的成就
    （播报语义为至少一次：允许重播、不允许漏播）。
19. IF 待播报查询或游标推进的数据库访问抛出异常 THEN THE 成就系统 SHALL 记录一条含用户 id 的告警日志、
    SHALL 保持 `achievement_notices` 表的行数与全部列取值不变，
    且 SHALL 不向记账、登录与注销路径传播该异常。

### 需求 6：成就查询接口与权限

**用户故事：** 作为开发者，我希望成就接口只能读到自己的成就，不能靠改参数看别人解锁了什么。

#### 验收标准

1. THE 成就系统 SHALL 提供已认证用户查询自身成就清单的接口，其成功响应的顶层字段集 SHALL 恰好为
   「成就视图列表、已解锁成就数、成就总数」3 项，SHALL 使这 3 个字段的键在每次成功响应中恒存在、
   SHALL 使成就视图列表恒含 16 项、SHALL 使成就总数恒为 16，
   且 SHALL 不在该顶层字段集中返回第 4 个字段。
2. THE 成就系统 SHALL 使成就视图的字段集恰好为以下 9 项：成就编码、展示名称、描述、分类、门槛数值、
   当前值、是否已解锁、解锁时刻、成就事件 id；THE 成就系统 SHALL 使这 9 个字段的键在全部 16 项上恒存在、
   SHALL 不因某项取值为空而省略其键、SHALL 不返回第 10 个字段，
   且 SHALL 使该字段集与列表项数不随该用户的交易笔数、成长事件条数与会话账本取值变化。
3. WHERE 某枚成就已解锁 THE 成就系统 SHALL 返回其解锁时刻为对应 `BADGE` 事件的 `created_at`、
   返回其成就事件 id 为该事件的 `id`；WHERE 某枚成就未解锁 THE 成就系统 SHALL 以空值返回
   解锁时刻与成就事件 id 两项，且 SHALL 不以 0、空字符串或当前时刻替代该空值；
   THE 成就系统 SHALL 使解锁时刻的时间表示形式与成长概览接口徽章列表的解锁时刻一致，
   且 SHALL 不新增第二种时间表示形式。
4. WHERE 某枚成就已解锁 THE 成就系统 SHALL 使其当前值恒等于其门槛数值；
   WHERE 某枚成就未解锁 THE 成就系统 SHALL 使其当前值等于该成就统计口径的当前取值与门槛数值
   两者中的较小者；THE 成就系统 SHALL 使任一成就的当前值落在 0 到其门槛数值的闭区间内。
5. THE 成就系统 SHALL 使成就清单响应中的已解锁成就数等于该列表中已解锁项的个数，
   且 SHALL 使该取值落在 0 到 16 的闭区间内。
6. WHEN 已认证用户请求成就清单 THEN THE 成就系统 SHALL 在返回结果之前触发一次结算，
   并 SHALL 复用 growth-level-system 需求 10.14 的概览侧节流器、SHALL 不新增节流器；
   THE 成就系统 SHALL 按 `user_id` 维度以 10 秒窗口节流该结算，
   SHALL 在进程启动后该用户的首次此类请求内执行结算，
   且 SHALL 不因该节流改变记账触发的结算的执行与否。
7. IF 成就清单请求内的结算失败或被节流 THEN THE 成就系统 SHALL 返回该用户当前已持久化的解锁状态
   与实时聚合的当前值、SHALL 不返回服务端错误、SHALL 使响应字段集与结算成功时相同，
   且 SHALL 保持 `growth_events`、`user_growth` 与 `achievement_notices` 三表的行数与全部列取值不变。
8. THE 成就系统 SHALL 要求成就清单接口、待播报成就接口与游标推进接口携带有效令牌；
   IF 请求未携带令牌、令牌无法解析、令牌签名校验失败、令牌已过期、或其标识的用户在 `users` 表中不存在
   THEN THE 成就系统 SHALL 以第 13 条的统一错误体返回既有错误码 `UNAUTHENTICATED`
   （优先于任何字段校验）、SHALL 使该响应不含成就视图列表、已解锁成就数、
   待播报成就项与游标取值四项中的任何一项，
   且 SHALL 保持 `growth_events` 与 `achievement_notices` 两表的行数与全部列取值不变。
9. THE 成就系统 SHALL 由三个接口在 JWT 过滤链之外显式执行「令牌用户在 `users` 表中仍存在」的库查询，
   SHALL 使该校验先于结算、入参校验与任何聚合查询执行，
   且 SHALL 使该存在性校验查询在单次请求内至多执行 1 次。
10. THE 成就系统 SHALL 把三个接口的数据范围硬性限定为当前会话用户本人的成就与播报游标，
    SHALL 以有效令牌所标识的用户 id 作为唯一的数据归属依据，
    SHALL 忽略请求中任何用于指定目标用户身份的查询参数、路径参数、请求体字段与自定义请求头，
    且 SHALL 不因请求携带此类字段而返回错误码。
11. THE 成就系统 SHALL 使三个接口的数据与会话账本无关，SHALL 不要求请求携带 `X-Ledger-Id` 头、
    SHALL 不因该头缺失或取值不可访问而拒绝请求；WHEN 同一用户分别以不携带 `X-Ledger-Id` 头与
    携带任意取值的 `X-Ledger-Id` 头请求这三个接口 THEN THE 成就系统 SHALL 返回逐项相同的结果。
12. THE 成就系统 SHALL 使三个接口的响应不含 `email`、`wx_openid`、`wx_unionid`、`invite_code`、
    `plan` 与 `role` 六个字段的键与取值，且 SHALL 不返回任何金额字段。
13. THE 成就系统 SHALL 以统一错误体格式 `{code, message, field}` 返回错误，其字段集 SHALL 恰好为
    这 3 项；THE 成就系统 SHALL 只新增 `ACHIEVEMENT_ACK_PARAM_INVALID` 一个错误码
    （结算失败、结算节流与空待播报列表均不对外暴露错误码），SHALL 使该错误码的 `field` 取 `lastEventId`、
    使无字段归属的错误的 `field` 取空值，且 SHALL 使 `message` 为长度不超过 100 个字符的中文文案、
    不含用户 id、邮箱与令牌三类内容。
14. WHEN 已认证用户请求成就清单 THEN THE 成就系统 SHALL 在服务端处理耗时不超过 2000 毫秒内返回
    成功结果或错误标识，该耗时 SHALL 按服务端进入处理到返回结果的时刻差度量、不含网络传输耗时，
    且其中结算占用的耗时 SHALL 不超过 1000 毫秒。
15. THE 成就系统 SHALL 不修改成长概览接口与经验明细接口的路径、入参与既有错误码。
16. WHEN 用户 A 以自身有效令牌请求这三个接口 THEN THE 成就系统 SHALL 只返回用户 A 的成就与播报游标数据，
    且 SHALL 不在响应中返回任何其它用户的成就编码解锁状态、解锁时刻、成就事件 id 与游标取值。
17. WHEN 已认证用户在请求中携带取值为他人 user id 的身份字段 THEN THE 成就系统 SHALL 返回与不携带该字段时
    逐项相同的结果，且 SHALL 不返回错误。
18. WHEN 尚无任何交易、成长事件与播报游标行的已认证用户请求成就清单 THEN THE 成就系统 SHALL 返回
    成就视图列表 16 项、成就总数 16、已解锁成就数 0、16 项的是否已解锁均为否且当前值均为 0，
    且 SHALL 不返回错误。

### 需求 7：解锁播报（动画与 Toast）

**用户故事：** 作为用户，我希望解锁成就的那一刻有点动静——一个好看的弹层加一句提示，而不是我自己去翻页面才发现。

#### 验收标准

1. WHEN 记账请求返回成功 THEN THE miniapp SHALL 在该响应返回后 1000 毫秒内发起一次待播报成就请求，
   且 SHALL 使该请求与记账结果的展示互不等待。
2. WHEN 用户打开成长页或成就页 AND 该页的数据请求返回成功 THEN THE miniapp SHALL 在该响应返回后
   1000 毫秒内发起一次待播报成就请求。
3. IF 待播报成就请求返回错误标识，或自请求发出起 3000 毫秒内无响应 THEN THE miniapp SHALL 放弃本次播报、
   SHALL 不重试该请求、SHALL 不展示任何提示，且 SHALL 保持当前页面的展示内容与跳转行为不变
   （播报故障静默降级）。
4. WHEN 待播报成就请求返回的列表非空 THEN THE miniapp SHALL 以带动画的解锁弹层播报该列表的第 1 项、
   SHALL 在该弹层内展示该成就的名称、描述与精确到自然日的解锁日期，
   并 SHALL 保持该弹层展示直到用户触发关闭操作、遮罩点击或进入成就页的操作（SHALL 不自动关闭）。
5. WHERE 待播报列表含 2 项及以上 THE miniapp SHALL 在解锁弹层关闭之后以 Toast 依次播报第 2 项起的
   至多 2 项，每条 Toast 的展示时长 SHALL 为 1500 毫秒、相邻两条 Toast 的间隔 SHALL 为 300 毫秒，
   且 SHALL 不为同一次播报展示 2 个及以上解锁弹层。
6. THE miniapp SHALL 使单次播报展示的成就项数不超过 3 项（1 个解锁弹层加至多 2 条 Toast）；
   WHERE 待播报列表多于 3 项 THE miniapp SHALL 把剩余项留待后续播报，且 SHALL 不展示这些剩余项。
7. THE miniapp SHALL 使解锁弹层的入场动画时长落在 600 到 1500 毫秒的闭区间内，
   并 SHALL 只以元素的位移、缩放与不透明度实现该动画。
8. WHEN 用户点击解锁弹层的关闭操作或点击弹层遮罩 THEN THE miniapp SHALL 在 300 毫秒内结束动画并关闭弹层。
9. WHEN 本次播报的全部成就已展示完成，或用户在本次播报结束前关闭解锁弹层 THEN THE miniapp SHALL 在
   1000 毫秒内发起一次游标推进请求，其 `lastEventId` 取本次已展示成就项的最大成就事件 id；
   WHERE 本次播报未展示任何成就项 THE miniapp SHALL 不发起游标推进请求。
10. IF 游标推进请求返回错误标识，或自请求发出起 3000 毫秒内无响应 THEN THE miniapp SHALL 不重试该请求、
    SHALL 不展示错误提示、SHALL 不阻断用户当前操作，
    且 SHALL 接受这些成就在后续播报中被再次展示（播报语义为至少一次）。
11. THE miniapp SHALL 不把未展示的成就项计入游标推进请求的 `lastEventId`
    （未播报的成就必须留在待播报集合内）。
12. THE miniapp SHALL 使记账成功后的页面返回、列表刷新与余额刷新的发起不依赖待播报成就请求与
    游标推进请求的返回，且 SHALL 使这三项行为在播报请求返回之前即已发起；
    WHILE 播报进行中 THE miniapp SHALL 保持记账结果已展示的取值不变。
13. WHILE 解锁弹层展示中 THE miniapp SHALL 提供进入成就页的操作与分享操作两个入口；
    WHEN 用户触发该弹层内的分享操作 THEN THE miniapp SHALL 保持该弹层继续展示。
14. IF 同一页面在上一次播报尚未结束时再次触发播报 THEN THE miniapp SHALL 放弃后一次播报请求，
    且 SHALL 不叠加展示 2 个及以上解锁弹层；THE miniapp SHALL 把「播报进行中」取为自待播报成就请求
    发出时刻起、至游标推进请求发出或本次播报被放弃时刻止的区间。
15. IF 当前不存在已登录状态 THEN THE miniapp SHALL 不发起待播报成就请求、SHALL 不展示解锁弹层与 Toast，
    且 SHALL 保持当前页面的展示内容不变。
16. WHEN 用户在解锁弹层内触发进入成就页的操作 THEN THE miniapp SHALL 在 300 毫秒内关闭该弹层并打开成就页、
    SHALL 放弃本次尚未展示的 Toast 项，且 SHALL 按第 9 条发起游标推进请求，
    其 `lastEventId` 取本次已展示成就项的最大成就事件 id。

### 需求 8：成就分享

**用户故事：** 作为用户，我希望把刚拿到的成就晒给朋友看，可以转发到微信，也可以存成图片。

#### 验收标准

1. WHERE 某枚成就已解锁 THE miniapp SHALL 在成就页该成就项内提供「分享给好友」与
   「保存成就卡片到相册」两个操作；WHILE 解锁弹层展示中 THE miniapp SHALL 为该弹层正在播报的那一枚成就
   提供这两个操作，且 SHALL 不在弹层内提供其它成就的分享与保存操作。
2. WHERE 某枚成就未解锁 THE miniapp SHALL 不提供该成就的分享与保存操作；
   IF 用户触发未解锁成就的分享入口或保存入口 THEN THE miniapp SHALL 展示该成就尚未解锁的提示文案、
   SHALL 不发起 canvas 绘制、SHALL 不写入相册，且 SHALL 不发起转发。
3. WHEN 用户对某枚已解锁成就触发分享给好友 THEN THE miniapp SHALL 经微信 `onShareAppMessage` 返回
   一张分享卡片，其 `path` 等于 `/pages/achievement/achievement?code={成就编码经 URL 编码后的取值}`，
   其标题包含产品名「有余」与该成就的展示名称，且标题长度落在 1 到 30 个字符的闭区间内。
4. WHEN 用户对某枚已解锁成就触发保存成就卡片到相册 THEN THE miniapp SHALL 以 canvas 绘制一张成就卡片，
   其内容 SHALL 包含该成就的展示名称、该成就的描述、该成就精确到自然日的解锁日期与产品名「有余」四项，
   且 SHALL 不包含这四项之外的用户数据。
5. THE miniapp SHALL 使成就卡片的绘制内容与分享卡片的标题均不包含金额、邮箱、邀请码与账本名称四类内容，
   且 SHALL 不包含其它用户的任何标识。
6. WHEN 成就卡片绘制完成 AND 相册写入成功 THEN THE miniapp SHALL 展示保存成功的提示文案，
   其展示时长 SHALL 为 1500 毫秒，且 SHALL 保持停留在当前页面。
7. WHEN 用户触发保存成就卡片到相册 AND 相册写入授权尚未授予 THEN THE miniapp SHALL 先发起一次
   相册写入授权请求；IF 用户在该次请求中拒绝相册写入授权 THEN THE miniapp SHALL 展示需要授权的提示文案、
   SHALL 不写入相册、SHALL 保持停留在当前页面，且 SHALL 保持已展示的成就清单与进度取值不变。
8. IF 从用户触发保存的时刻起 3000 毫秒内成就卡片的绘制与相册写入未全部完成 THEN THE miniapp SHALL
   结束本次保存、SHALL 不写入相册、SHALL 展示保存失败的提示文案、SHALL 保持停留在当前页面，
   且 SHALL 允许用户再次触发保存。
9. THE 成就系统 SHALL 不生成、不缓存、不存储任何成就分享图片，且 SHALL 不新增任何返回图片数据的接口。
10. WHEN 用户点击成就分享卡片进入 miniapp THEN THE miniapp SHALL 打开成就页，
    并把地址参数 `code` 经 URL 解码并裁剪首尾空白后的取值作为待高亮成就编码传入该页；
    THE miniapp SHALL 只接受长度不超过 64 个字符的该取值。
11. WHEN 成就页收到的待高亮成就编码等于成就清单响应中某项的成就编码 THEN THE miniapp SHALL 在
    1000 毫秒内滚动到该项并以高亮样式展示该项，SHALL 在高亮展示 3000 毫秒后把该项恢复为默认样式，
    且 SHALL 不因该项是否已解锁而改变上述滚动与高亮行为。
12. IF 成就页启动参数不含 `code`，或 `code` 去空白后为空，或其去空白后长度超过 64 个字符，
    或其取值不等于成就清单响应中任何一项的成就编码
    THEN THE miniapp SHALL 展示不含高亮项的默认成就页，且 SHALL 不展示错误提示。
13. WHEN 未登录用户经成就分享卡片打开 miniapp THEN THE miniapp SHALL 展示登录引导、
    SHALL 不发起成就清单请求，且 SHALL 保留该次启动的 `code` 取值。
14. WHEN 经成就分享卡片进入的用户完成登录 THEN THE miniapp SHALL 打开成就页，
    并把第 13 条保留的 `code` 取值按第 10 条的解码与裁剪规则作为待高亮成就编码传入该页。
15. WHILE 本次成就卡片的绘制或相册写入尚未结束 IF 用户再次触发保存成就卡片到相册
    THEN THE miniapp SHALL 放弃后一次触发、SHALL 不发起第二次 canvas 绘制，且 SHALL 不叠加展示提示文案。
16. IF 相册写入授权处于用户此前已拒绝的状态 AND 用户触发保存成就卡片到相册 THEN THE miniapp SHALL
    展示需要授权的提示文案与打开系统设置的操作、SHALL 不发起 canvas 绘制，且 SHALL 保持停留在当前页面。

### 需求 9：成就页与入口

**用户故事：** 作为用户，我希望有一个地方能一眼看完我拿到了哪些成就、还差哪些，而不是散落在各个页面。

#### 验收标准

1. WHILE 用户处于已登录状态 AND 成长页的数据请求已返回成功 THE miniapp SHALL 在成长页展示进入成就页的
   入口，并 SHALL 在该入口处展示形如「已解锁成就数 / 成就总数」的两个整数取值，
   其中已解锁成就数取自该响应中已解锁项的个数并落在 0 到 16 的闭区间内、成就总数恒为 16。
2. WHEN 用户点击成就页入口 THEN THE miniapp SHALL 打开成就页 `pages/achievement/achievement`。
3. WHEN 成就页加载完成 AND 成就清单请求返回成功 THEN THE miniapp SHALL 按服务端返回的顺序展示全部
   16 枚成就，SHALL 按分类分组展示且组的展示顺序为「起步」、「坚持」、「积累」、「协作」、「主题」，
   每组 SHALL 展示该分类的中文展示名，且每枚成就项 SHALL 展示其展示名称与描述两项取值。
4. THE miniapp SHALL 为每枚已解锁成就展示品牌绿图标与解锁日期，该解锁日期 SHALL 取该成就视图解锁时刻
   所属自然日的年、月、日三项，且 SHALL 不为其展示进度文案。
5. THE miniapp SHALL 为每枚未解锁成就展示灰度图标与形如「当前值 / 门槛数值」的进度文案，
   其中当前值与门槛数值 SHALL 分别取自该成就视图的当前值与门槛数值两项且当前值不大于门槛数值，
   且 SHALL 不为其展示解锁日期。
6. THE miniapp SHALL 不在任何页面展示成就编码、统计口径枚举取值与成就事件 id 三类原始取值。
7. IF 成就清单请求返回错误标识，或 10000 毫秒内无响应 THEN THE miniapp SHALL 展示加载失败的提示文案
   与重试操作、SHALL 不展示任何成就项、SHALL 不展示已解锁成就数与成就总数、
   SHALL 结束进行中的下拉刷新动效，且 SHALL 不自动重发该请求。
8. WHEN 用户在成就页错误态触发重试 THEN THE miniapp SHALL 重新发起成就清单请求，
   SHALL 在该请求返回之前使重试操作不可再次触发，
   并 SHALL 在该请求返回成功后移除加载失败的提示文案与重试操作。
9. WHEN 成就页下拉刷新触发的成就清单请求返回成功 THEN THE miniapp SHALL 在 1000 毫秒内结束刷新动效，
   并 SHALL 以本次响应的取值整体替换已展示的成就项、已解锁成就数与成就总数。
10. WHEN 用户在距上一次成就清单请求发出不足 3000 毫秒时触发下拉刷新 THEN THE miniapp SHALL 不发起请求、
    SHALL 在 1000 毫秒内结束刷新动效、SHALL 保持已展示的成就取值不变，且 SHALL 不展示错误提示。
11. THE miniapp SHALL 把成就页注册为非 tabBar 页面，并 SHALL 在该页面的页面级配置中开启下拉刷新。
12. THE miniapp SHALL 把全部成就相关请求收敛到单一 api 模块，并 SHALL 使这些请求不携带 `X-Ledger-Id` 头。
13. THE miniapp SHALL 复用既有的品牌绿主色与既有卡片、分组样式类实现成就页，
    且 SHALL 不新增第二套颜色体系。
14. IF 用户处于未登录状态 THEN THE miniapp SHALL 不在成长页展示进入成就页的入口，
    且 SHALL 不发起成就清单请求。
15. IF 成长页的数据请求返回错误标识或在 10000 毫秒内无响应 THEN THE miniapp SHALL 展示不含已解锁成就数
    与成就总数两个取值的成就页入口，且 SHALL 保持点击该入口打开成就页的行为不变。
16. WHILE 成就清单请求进行中 THE miniapp SHALL 展示加载中指示，SHALL 不展示加载失败的提示文案与重试操作，
    且 SHALL 不展示任何成就项。

### 需求 10：数据模型与迁移

**用户故事：** 作为开发者，我需要新增的播报游标表与放宽后的事件类型约束能与既有 Flyway 迁移体系一致地演进。

#### 验收标准

1. THE 迁移脚本 SHALL 新建 `achievement_notices` 表，该表 SHALL 恰好包含以下 4 列：
   `user_id`（BIGINT NOT NULL，主键，不声明 `AUTO_INCREMENT`、不声明 `DEFAULT`，
   取值等于 `users.id` 中已存在的取值、由服务层写入）、
   `last_notified_event_id`（BIGINT NOT NULL，缺省 0）、
   `created_at`（DATETIME NOT NULL，不声明 `DEFAULT`、不声明 `ON UPDATE`，秒级精度）、
   `updated_at`（DATETIME NOT NULL，不声明 `DEFAULT`、不声明 `ON UPDATE`，秒级精度）。
2. THE 迁移脚本 SHALL 不为 `achievement_notices` 新增独立自增主键列、SHALL 不为 `user_id` 另建唯一约束，
   且 SHALL 使该表建成后恰好只有 1 个索引即 `user_id` 单列主键。
3. THE 迁移脚本 SHALL 建立名为 `ck_achievement_notices_event_id` 的具名 CHECK 约束，
   其表达式 SHALL 恰好为「`last_notified_event_id` 大于或等于 0」，即取值 0 通过、取值 -1 不通过。
4. THE 迁移脚本 SHALL 使 `achievement_notices` 表不包含任何指向 `users(id)` 的外键，
   且 SHALL 使该表建成后的外键数量为 0
   （注销时由服务层在同一事务内显式删除该行，与 `user_growth` 同一取舍）。
5. THE 迁移脚本 SHALL 先删除 `growth_events` 上已存在的 `ck_growth_events_type` 约束、再建立同名约束，
   新约束取值集合 SHALL 恰好为 `FIRST_RECORD`、`DAILY_RECORD`、`STREAK`、`BUDGET_MET`、`FIRST_INVITE`、
   `BADGE`、`SAVING_MONTH` 七个取值，且 SHALL 与 `V32__user_growth.sql` 一致地在表达式内对
   `event_type` 显式应用 `utf8mb4_bin` 排序规则以保证区分大小写。
6. WHEN 迁移完成 THEN THE 数据库 SHALL 拒绝 `event_type` 取值为该七个取值之外任何字符串
   （含仅大小写不同的字符串，如 `saving_month`、`Badge`，以及空字符串）的插入与更新语句，
   并 SHALL 保持被拒绝语句所涉行的原有取值不变。
7. THE 迁移脚本 SHALL 为每个存在至少一条 `event_type` 区分大小写等于 `BADGE` 的成长事件的用户
   回填恰好一行播报游标，其 `last_notified_event_id` 取该用户此类成长事件的最大 `id`，
   其 `created_at` 与 `updated_at` 取迁移执行时刻的同一时间值（迁移前已解锁的成就一律视为已播报）。
8. THE 迁移脚本 SHALL 不为没有任何成长事件的用户、以及只有非 `BADGE` 成长事件的用户回填播报游标行，
   回填后 `achievement_notices` 的行数 SHALL 恰好等于存在至少一条 `BADGE` 成长事件的去重用户数。
9. THE 迁移脚本 SHALL 不修改、不删除 `growth_events` 与 `user_growth` 两表的任何已存在行的任何列取值，
   迁移前后两表的行数 SHALL 相等。
10. THE 迁移脚本 SHALL 以 `V33__achievement.sql` 为文件名置于 `src/main/resources/db/migration` 目录，
    SHALL 不占用 `V1` 至 `V32` 中任一版本号（其中 `V30` 已由 user-feedback-system 预占），
    且 SHALL 不修改、不重命名、不删除任何已存在的迁移文件。
11. THE 迁移脚本 SHALL 使 `achievement_notices` 表使用 InnoDB 引擎、`utf8mb4` 字符集与
    `utf8mb4_unicode_ci` 排序规则，且 SHALL 为该表与其 4 个列各写一条长度为 1 至 255 个字符的
    非空中文注释。
12. WHEN 应用以 Hibernate `ddl-auto=validate` 在迁移后的数据库上启动 THEN THE 应用 SHALL 启动成功
    且 SHALL 不输出任何表结构校验失败的错误信息。
13. WHEN 在同一数据库上连续执行两次迁移 THEN THE `flyway_schema_history` 表 SHALL 恰好包含该版本的
    1 条执行成功记录，且 `achievement_notices` 的行数与列定义 SHALL 与第一次执行后完全一致。
14. THE 清库脚本 `deploy/reset-db.sql` SHALL 在清空 `users` 表之前清空 `achievement_notices` 表，
    并 SHALL 与成长体系两表一样注明该表无外键、清空不依赖外键检查开关取值。
15. WHEN 执行清库脚本 THEN THE `achievement_notices` 表 SHALL 存在、列定义 SHALL 不变且行数 SHALL 为 0，
    且 THE `flyway_schema_history` 表的记录数 SHALL 与执行前相等。
16. IF 迁移脚本执行过程中任一语句失败 THEN THE Flyway SHALL 不将该版本记录为执行成功，
    且 THE 应用 SHALL 启动失败并输出指明失败版本号与失败语句所在表的错误信息。
17. IF 对 `achievement_notices` 执行使 `last_notified_event_id` 取值小于 0 的插入或更新语句
    THEN THE 数据库 SHALL 拒绝该语句，并 SHALL 保持目标行原有取值不变。
18. WHEN 迁移在 `growth_events` 全部存量行的 `event_type` 均落在该七个取值集合内、
    且该表行数不超过 100 万行的数据库上执行 THEN THE 迁移 SHALL 成功完成且总耗时 SHALL 不超过 60 秒。

### 需求 11：账号注销与数据清理

**用户故事：** 作为用户，我注销账号之后，我的成就与播报记录都应该干净地消失；重新注册就是从零开始。

#### 验收标准

1. WHEN 已认证用户的账号注销请求通过全部前置校验并进入注销事务 THEN THE 成就系统 SHALL 在该同一事务内
   以 `user_id` 等于该用户 id 为唯一过滤条件执行 1 条硬删除语句删除其在 `achievement_notices` 表中的行，
   该语句的影响行数 SHALL 为 0 或 1。
2. THE 成就系统 SHALL 在注销序列中把 `achievement_notices` 的删除置于既有成长数据
   （`growth_events` 与 `user_growth` 两表）删除之后、删除 `users` 行之前，
   且 SHALL 保持既有注销步骤的相对顺序、过滤条件与影响行数不变。
3. WHEN 某用户没有播报游标行且执行注销 THEN THE 成就系统 SHALL 以影响行数 0 视为删除成功、
   SHALL 不返回错误标识，且 SHALL 不因此中止或回滚注销事务。
4. THE 成就系统 SHALL 在删除播报游标行之前不执行任何存在性预查询、
   SHALL 对 `achievement_notices` 表在单次注销内只执行第 1 条所述的 1 条删除语句，
   且 SHALL 不写入该行的任何软删除标记、归档副本或更新语句。
5. IF 播报游标行的删除抛出异常 THEN THE 注销事务 SHALL 回滚、THE 成就系统 SHALL 保持该用户的
   成就事件、播报游标与 `users` 行的行数与全部列取值为注销前的状态，
   且 THE 注销接口 SHALL 返回指示注销失败的错误标识。
6. WHEN 用户注销之后以同一邮箱重新注册 AND 该用户请求成就清单 THEN THE 成就系统 SHALL 返回成就总数 16、
   已解锁成就数 0，且 SHALL 使 16 项成就视图的是否已解锁均为否、当前值均为 0、
   解锁时刻与成就事件 id 均为空值。
7. WHEN 某用户注销 THEN THE 成就系统 SHALL 保持其它用户在 `achievement_notices`、`growth_events`
   与 `user_growth` 三表中的行数与全部列取值不变。
8. IF 注销的前置校验未通过 THEN THE 成就系统 SHALL 不对 `achievement_notices` 表执行任何删除语句、
   SHALL 保持该表的行数与全部列取值不变，且 THE 注销接口 SHALL 返回指示前置校验失败的错误标识。
9. WHEN 注销事务提交完成 THEN THE 成就系统 SHALL 使 `user_id` 等于该用户 id 的行在
   `achievement_notices` 与 `growth_events` 两表中的行数均为 0。
10. WHEN 用户注销之后以同一邮箱重新注册 AND 该用户请求待播报成就 THEN THE 成就系统 SHALL 返回空列表
    与待播报总条数 0，且 SHALL 使该用户在 `achievement_notices` 表中的行数为 0。
11. IF 请求成就清单、待播报成就或游标推进接口所携带的令牌标识的用户在 `users` 表中已不存在
    （含注销前签发、注销后使用的令牌）THEN THE 成就系统 SHALL 返回 `UNAUTHENTICATED`，
    且 SHALL 不在 `achievement_notices` 表中创建任何行。

### 需求 12：与成长体系既有契约的兼容

**用户故事：** 作为开发者，我要的是加了成就系统之后，成长页、经验明细与记账接口的既有行为一个都没被改坏。

#### 验收标准

1. THE 成就系统 SHALL 使成长概览接口的顶层响应字段集恰好保持为 growth-level-system 需求 10.1 的 15 项，
   且 SHALL 使其中每个徽章列表项的字段集恰好保持为「徽章编码、名称、是否已点亮、解锁时刻、目标值、
   当前值」6 项；THE 成就系统 SHALL 不向徽章列表项新增描述、成就分类、统计口径与成就事件 id 四项中的
   任何一项，且 SHALL 不重命名、不删除该 6 项中的任何一项。
2. THE 成就系统 SHALL 使成长概览接口返回的徽章列表恒含 16 项，由本 spec 需求 1 第 1 条的 16 枚成就派生，
   其顺序 SHALL 与该表格的序号顺序（序号 1 至 16）相同；THE 成就系统 SHALL 使该列表包含
   growth-level-system 既有 9 个徽章编码中的每一个；WHEN 同一用户连续两次请求成长概览
   THEN THE 成就系统 SHALL 使两次返回的徽章列表项顺序与徽章编码逐项相同。
3. WHEN 同一已认证用户先后请求成长概览接口与成就清单接口 AND 两次请求之间该用户没有新解锁的成就
   THEN THE 成就系统 SHALL 使徽章列表第 N 项（N 为 1 至 16）的徽章编码、名称、是否已点亮、解锁时刻、
   目标值与当前值，分别等于成就视图列表第 N 项的成就编码、展示名称、是否已解锁、解锁时刻、门槛数值
   与当前值；THE 成就系统 SHALL 使该逐项相等对已解锁项与未解锁项同时成立。
4. THE 成就系统 SHALL 保持记账接口、预算接口、登录接口、注销接口与邀请接口的响应字段集不变：
   SHALL 不向这五类接口的响应新增、删除或重命名任何字段，且 SHALL 不向记账响应新增任何成就字段、
   任何播报字段与任何徽章字段；THE 成就系统 SHALL 保持这五类接口既有错误码的取值集合与触发条件不变，
   且 SHALL 不为这五类接口新增任何错误码
   （本 spec 新增的 `ACHIEVEMENT_ACK_PARAM_INVALID` 仅用于需求 6 的游标推进接口）。
5. THE 成就系统 SHALL 保持经验明细接口的分页入参、排序、列表项字段集与顶层字段集不变；
   THE 成就系统 SHALL 使该接口继续返回该用户的全部成长事件（含 `exp_amount` 为 0 的 `BADGE` 行与
   `SAVING_MONTH` 行）、SHALL 使总条数计入这些行，且 SHALL 不按 `event_type` 或 `exp_amount`
   过滤任何行。
6. THE 成就系统 SHALL 保持等级阈值函数、经验事件清单与各事件的经验值取值不变，
   SHALL 不新增任何带正经验的事件类型；WHEN 某用户在一次或多次结算中新解锁 1 至 16 枚成就
   与/或新增 1 至 3 条储蓄月事件 THEN THE 成就系统 SHALL 使该用户 `user_growth` 的 `exp` 与 `level`
   两列取值等于这些事件写入前的取值。
7. THE 成就系统 SHALL 保持 `growth_events` 为只追加表：对该表已插入的行 SHALL 不执行任何更新语句、
   SHALL 不执行除账号注销时该用户成长事件硬删除之外的任何删除语句，
   且 SHALL 使已插入行的 `id`、`user_id`、`event_type`、`event_key`、`exp_amount` 与 `created_at`
   六列取值在其存续期内不变；THE 迁移脚本 SHALL 不对该表已存在的任何行执行更新或删除语句。
8. THE 成就系统 SHALL 保持 `user_growth` 表的列集合、各列类型与缺省值不变：
   SHALL 不为成就、成就分类、储蓄月或播报状态向该表新增列，SHALL 不删除、不重命名该表任何已有列，
   且 SHALL 不为该表新增索引或约束（播报状态一律落在 `achievement_notices` 表）。
9. THE 成就系统 SHALL 保持结算触发时机仅为 growth-level-system 需求 9.1 的两类——
   新增有效记账交易的业务事务提交后回调内，以及已认证用户请求成长概览时
   （成就清单请求按需求 6 第 6 条复用概览侧的同一结算入口与同一节流器，不构成第三类触发时机）；
   THE 成就系统 SHALL 保持记账触发结算的节流窗口与成长概览侧 10 秒进程内节流窗口的取值不变、
   SHALL 不新增节流器；THE 成就系统 SHALL 保持结算异常在提交后回调内就地捕获、只记录含用户 id 的
   告警日志、不向记账、预算、登录、注销与邀请路径传播的故障隔离方式不变，
   并 SHALL 保持结算不超过 1000 毫秒、记账接口端到端不超过 2000 毫秒两项耗时预算不变。
10. IF 成长概览请求内的结算失败或被节流 THEN THE 成就系统 SHALL 返回与结算成功时相同的顶层字段集与
    相同的徽章列表项字段集、SHALL 返回该用户当前已持久化的解锁状态与实时聚合的当前值，
    且 SHALL 不返回错误码；IF 成长概览请求内的结算失败 AND 该用户尚无成长档案
    THEN THE 成就系统 SHALL 返回等级 1、经验值 0、累计记账天数 0、当前连续天数 0、历史最长连续天数 0
    与 16 枚均未点亮的徽章（各项当前值为 0），并 SHALL 返回真实的累计记账笔数与累计金额。
11. WHEN 经验明细返回 `event_type` 为 `SAVING_MONTH` 或 `BADGE` 的成长事件 THEN THE miniapp SHALL
    为该项展示该事件类型对应的中文文案，其长度 SHALL 不超过 10 个字符，
    且 SHALL 不展示 `SAVING_MONTH`、`BADGE` 两个原始枚举取值与该项的事件键原文；
    IF 某项的 `event_type` 不属于该七个取值 THEN THE miniapp SHALL 为该项展示统一的兜底中文文案、
    SHALL 保持该页其余项的展示取值不变，且 SHALL 不展示错误提示、SHALL 不中断本页渲染。
