# Requirements Document

## Introduction

有余（youyu）目前把「记账」这件事的价值完全交给了报表：用户坚持记了三百天，产品也不会为此说一句话。
本次新增**成长体系**，把用户在产品里的长期行为沉淀成一条可见、可积累、不会倒退的轨迹：

- **等级（Level）与经验（EXP）**：经验来自若干确定的成长事件（第一笔记账、每日记账、连续 7 / 30 天、
  预算达成、首次邀请好友），等级由经验按一条固定公式换算，范围 Lv1 到 Lv100。
- **成长事件表** `growth_events`：每条经验与每枚徽章都是表里的一行，
  以 `(user_id, event_key)` 唯一索引承担幂等，**经验只增不减**。
- **成长档案** `user_growth`：物化当前经验、等级、累计记账天数、连续记账天数，供成长页快速读取，
  且必须能由 `growth_events` 与交易事实源完整重算。
- **成长页**：展示等级、经验与升级进度、累计记账笔数、累计金额、累计记账天数、当前连续天数、徽章墙，
  并提供经验明细分页查询。

### 范围与前提约定（影响验收标准的关键决策）

以下八项是本 spec 的决策骨架，验收标准全部围绕它们展开。

1. **经验只增不减、同一事件只发一次。** 经验是「做过某事」的历史事实：删除交易、清空回收站、
   下调预算、被邀请人注销，一律**不扣经验、不降级**。倒扣会让用户掉级（体验恶劣），且要求每个
   经验事件都能反查其事实依据（实现复杂度陡增）。幂等由 `growth_events` 的
   `(user_id, event_key)` 唯一索引在**数据库层**保证，不依赖应用层的「先查再写」。
   与此相对，**累计统计如实反映当前事实源**：删掉一笔交易，累计笔数与累计金额会随之下降，
   但已获得的经验、等级与徽章不动。这条「经验/徽章只增不减，累计笔数与累计金额如实反映事实」
   的分工贯穿全文。
2. **等级曲线是一条公式，不是一张手工表。** 升到 Lv L 所需累计经验为
   `threshold(L) = 2 × (L − 1)² + 8 × (L − 1)`，L 取 1 到 100（`threshold(1) = 0`、
   `threshold(2) = 10`、`threshold(100) = 20394`）。等级取「满足 `threshold(L) ≤ 经验值` 的最大 L」，
   上限 100。**Lv100 之后经验继续累计、等级恒为 100**。刻意让 `threshold(2) = 10`，
   使「第一笔记账 +10 EXP」当场升到 Lv2。
3. **「连续天数」按记账行为发生日算，不按交易日期算。** 记账日取交易 `created_at`（写入时刻）
   所在的 `Asia/Shanghai` 自然日，**不取 `occurred_at`**：`occurred_at` 可任意补记甚至填未来，
   若用它计算连续天数，用户一次性补录 30 天历史即可拿到「连续 30 天」，与「坚持记账」的激励意图相反。
   记账日历以 `DAILY_RECORD:<日期>` 事件逐日落表，因此连续中断后重新开始只影响「当前连续天数」，
   已发放的经验与「历史最长连续天数」不受影响。**「连续是否已中断」在读取时按判定日实时判定**，
   物化列只承载连续段长度，因此跨日后或结算失败时都不会返回过期的非零连续天数。
   **连续 7 天与连续 30 天均为终身一次性成就**
   （取舍：可重复获得会让长期用户的经验无界膨胀，并需要额外维护「上次结算点」状态；本期定为一次性）。
4. **「预算达成」= 自然月结束后该月支出未超总预算，由结算时的回看判定，不引入定时任务。**
   判定发生在任一次结算时，回看**最近 3 个已结束自然月**（容忍用户三个月不活跃），
   逐月按 `BUDGET_MET:<YYYY-MM>` 幂等发放，每个自然月至多 50 EXP。要求该月确有总预算且确有支出
   （零支出不算达成），且**每月至多发放一次**，不因用户拥有多个账本而叠加。
   判定**仅限该用户自己拥有的账本**（协作账本的预算不由他设定），这与累计统计「跨全部账本合并」
   刻意不同：预算达成衡量「自己设的预算守住了没有」，累计统计衡量「自己记了多少账」。
5. **累计金额只算收支、不算转账与余额调整，单币种。** 系统当前无币种字段（金额一律
   `DECIMAL(18,2)` 人民币），故无多币种口径问题。转账与余额调整的 `ledger_id` 为空，
   一律排除在累计统计之外。多账本合并计算：口径是「该用户记的账」而非「某个账本的账」。
   **归属键取 `transactions.created_by`（记账人），不取 `transactions.user_id`** ——
   后者自 `V9__ledger_enforce.sql` 起已放宽为可空且服务层不再写入，对新数据恒为 NULL。
6. **徽章本期实现，复用成长事件表，不新增徽章表。** 每枚徽章是 `growth_events` 中一条
   `event_type = 'BADGE'`、`exp_amount = 0` 的行，因此徽章天然「一经点亮永不熄灭」，
   且解锁时间就是该行的 `created_at`。徽章不额外发放经验（避免同一行为两次计分）。
   本期徽章清单固定 9 枚，见需求 8。
7. **成长体系的任何故障都不得阻断记账、预算、登录、注销、邀请等主路径。** 这与
   invite-system「邀请码问题绝不阻断注册」是同一条原则。落地方式：结算**在业务事务提交之后、
   于独立事务内执行**，其抛出的任何异常一律就地捕获并只记录告警日志，不向业务调用方传播；
   记账接口的响应内容与状态码不因结算成败而变化。结算本身幂等可重入，任何一次失败都会在
   下一次记账或下一次打开成长页时自动补齐，因此**不需要**补偿任务、消息队列或定时任务。
8. **累计天数与连续天数用物化列，累计笔数与累计金额用实时聚合。** 前两者需要 distinct 日期聚合与
   连续段扫描，成本随历史线性增长，且语义上只增不减，适合物化到 `user_growth`；后两者必须如实反映
   删除与修改，实时 `COUNT`/`SUM`（走既有索引 `idx_tx_created_by`）天然一致，物化反而制造对账负担。
   物化列必须能由 `growth_events` 完整重算，且**增量维护的结果与全量重算的结果必须相等**。

### 与其它 spec 的关系

- **依赖 invite-system（已实现）**：「首次邀请好友」经验的事实源是 `invite_relations` 表中
  `inviter_id = 当前用户` 且 `status = 'REGISTERED'` 的行数。本 spec **只读该表**，
  不修改其任何行、不调用邀请系统的任何服务类、不修改登录/注册路径。
  由于 invite-system 明确「邀请关系只在新账号创建那一刻建立」且「注销不删行、只把被邀请人那行置
  `INVALID`」，本 spec 相应约定：**不在被邀请人的注册请求内为邀请人发放经验**
  （邀请人下次结算时补齐），且被邀请人注销导致计数回落时**不撤销**已发放的经验与徽章。
- **迁移版本号**：`src/main/resources/db/migration` 当前最大版本号为 31（`V31__user_invite.sql`），
  `V30` 由 user-feedback-system spec 预占（`V30__feedback.sql`，尚未落地）。本 spec 取
  **`V32__user_growth.sql`**。
- 与 user-feedback-system、account-ledger-redesign 无功能耦合。

## Glossary

- **成长体系（Growth_System）**：本 spec 涉及的服务端成长档案、成长事件、经验结算、等级换算、
  徽章判定与成长查询接口的整体。
- **经验值（EXP）**：非负整数，等于该用户在 `growth_events` 中全部行的 `exp_amount` 之和。
  用户原始需求中的「成长值」与经验值是同一个量，本 spec 统一只用「经验值」一个名词，
  不引入第二套并行计量。
- **等级（Level）**：由经验值按 **等级阈值函数** 换算得到的整数，取值范围 1 到 100。
- **等级阈值函数（threshold）**：`threshold(L) = 2 × (L − 1)² + 8 × (L − 1)`，定义域 L ∈ [1, 100]，
  取值为非负整数。`threshold(1) = 0`、`threshold(2) = 10`、`threshold(3) = 24`、
  `threshold(10) = 234`、`threshold(50) = 5194`、`threshold(100) = 20394`。
- **满级**：等级等于 100 的状态。
- **成长事件（growth event）**：`growth_events` 表的一行，主键 `id`，由 `(user_id, event_key)` 唯一确定。
  经验与徽章共用该表。
- **事件键（event_key）**：成长事件的幂等键，长度不超过 64 个字符的字符串，取值形如
  `FIRST_RECORD`、`DAILY_RECORD:2025-06-01`、`STREAK_7`、`STREAK_30`、`BUDGET_MET:2025-05`、
  `FIRST_INVITE`、`BADGE:RECORD_100`。
- **事件类型（event_type）**：区分大小写的枚举，取值 `FIRST_RECORD`、`DAILY_RECORD`、`STREAK`、
  `BUDGET_MET`、`FIRST_INVITE`、`BADGE` 六者之一。
- **成长档案（growth profile）**：`user_growth` 表的一行，主键 `user_id`，每个用户至多一行，
  物化该用户的经验值、等级、累计记账天数、连续记账天数与最近记账日。
- **有效记账交易**：同时满足以下四条的 `transactions` 行：`created_by` 等于该用户 id、
  `deleted_at` 为 NULL、`type` 属于 `expense`/`income`、`ledger_id` 非 NULL。
  该定义排除转账（`type = 'transfer'`）与余额调整（收支类型但 `ledger_id` 为 NULL），
  也排除回收站中的软删除记录。
- **记账日**：一个 `Asia/Shanghai` 自然日 D，使该用户存在至少一笔 `created_at` 落在 D 的有效记账交易。
- **记账日历**：该用户全部 `event_type = 'DAILY_RECORD'` 成长事件所对应的日期集合。
  该集合只追加、不删除，是累计记账天数与连续记账天数的唯一计算依据。
- **累计记账天数**：记账日历中的日期个数，等于该用户 `event_type = 'DAILY_RECORD'` 的成长事件条数。
- **最近记账日**：记账日历中的最大日期；记账日历为空时无最近记账日。
- **连续段长度**：以最近记账日为终点，向前逐日回溯、日期在记账日历中连续存在的自然日个数。
- **当前连续天数**：`last_record_date` 等于**判定日**或判定日的前一日时，取连续段长度；否则取 0（连续已中断）。
  该判定在读取时实时进行，不取物化列的历史取值。
- **判定日**：生成成长概览响应的时刻所在的 `Asia/Shanghai` 自然日。与结算日的区别在于：结算日是写入侧的
  时刻归属，判定日是读取侧的时刻归属；同一次请求内两者通常相同，但结算被节流或失败时可能不同。
- **追补起点**：本次结算开始追补记账日历的第一个自然日。取该用户 `created_at` 大于或等于
  「`last_record_date` 的次日 00:00」的有效记账交易中最早那一笔的记账日；`last_record_date` 为空值时，
  取该用户全部有效记账交易中最早那一笔的记账日。
- **追补窗口**：自追补起点起、长度至多 1000 个自然日且不越过结算日的连续自然日区间；
  窗口末日等于「追补起点加 999 天」与结算日两者中的较小者。
- **历史最长连续天数**：记账日历中最长的连续自然日区间所含的日期个数；记账日历为空时为 0。
- **结算**：成长体系的一次幂等计算过程：读取事实源、按需补写缺失的成长事件、重算并写回成长档案。
  详见需求 9。
- **结算日**：执行结算时服务端当前时刻所在的 `Asia/Shanghai` 自然日。
- **已结束自然月**：结束时刻早于或等于结算时刻的自然月；结算日所属的自然月不是已结束自然月。
- **预算达成**：某已结束自然月 M 与**该用户自己拥有的**某账本 L（`ledgers.user_id` 等于该用户 id；
  协作账本不参与判定）满足：`budgets` 中存在 `(ledger_id = L, budget_month = M)` 的行；
  该账本在 M 内的**月度有效支出合计**大于 `0.00`；且该合计小于或等于该行的 `amount`
  （合计等于预算金额时视为达成）。
- **月度有效支出合计**：`ledger_id` 等于该账本、`type` 为 `expense`、`deleted_at` 为 NULL、
  `occurred_at` 落在半开区间 [该月 1 日 00:00, 次月 1 日 00:00) 的 `transactions` 行的
  `amount` 合计（保留 2 位小数），口径与 `BudgetService` 既有的月度支出聚合一致。
- **累计记账笔数**：该用户有效记账交易的行数。
- **累计支出金额**：该用户 `type = 'expense'` 的有效记账交易的 `amount` 合计，保留 2 位小数。
- **累计收入金额**：该用户 `type = 'income'` 的有效记账交易的 `amount` 合计，保留 2 位小数。
- **徽章（badge）**：一枚固定编码的成就标识，以 `event_type = 'BADGE'`、
  `event_key = 'BADGE:<徽章编码>'`、`exp_amount = 0` 的成长事件表示其已点亮。
- **徽章编码**：区分大小写的固定字符串，取自需求 8 第 1 条列出的 9 个取值。
- **已点亮**：该用户存在对应徽章编码的 `BADGE` 成长事件。
- **成长页**：miniapp 新增页面 `pages/growth/growth`，展示等级、经验、累计统计与徽章墙。
- **经验明细页**：miniapp 新增页面 `pages/growthlog/growthlog`，分页展示该用户的成长事件流水。
  它是独立页面而非成长页内的区域，成长页只提供进入它的入口、不展示任何列表项。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌
  （与 invite-system 同一口径）。
- **miniapp**：微信小程序端（uni-app / Vue 3）。

## Requirements

### 需求 1：成长档案与经验总账

**用户故事：** 作为用户，我希望我的经验和等级是一本只往前记的账，我做过的事不会因为我后来删了一笔账就被抹掉。

#### 验收标准

1. THE 成长体系 SHALL 为每个用户维护至多一行成长档案，主键为 `user_id`，其 `exp` 取值 SHALL 为大于或等于 0 的整数，`level` 取值 SHALL 为 1 到 100 的整数。
2. THE 成长体系 SHALL 使成长档案的 `exp` 恒等于该用户全部成长事件的 `exp_amount` 之和；WHEN 一次结算完成 THEN THE 成长体系 SHALL 使该等式在该次结算提交后成立。
3. THE 成长体系 SHALL 使任意成长事件的 `exp_amount` 取值大于或等于 0，且 SHALL 不向 `growth_events` 表执行任何使 `exp_amount` 为负的插入或更新语句。
4. THE 成长体系 SHALL 不对已插入的成长事件执行删除或更新语句（`growth_events` 是只追加表）；WHEN 用户删除交易、清空回收站、修改交易、下调或删除预算，或其被邀请人注销 THEN THE 成长体系 SHALL 保持该用户全部成长事件的行数与全部列取值不变，且 SHALL 保持成长档案的 `exp` 与 `level` 取值不变（经验只增不减、等级不降）。
5. THE 成长体系 SHALL 以 `growth_events` 的 `(user_id, event_key)` 唯一索引作为「同一事件只发一次」的唯一保证手段，且 SHALL 不以应用层的先查询后写入作为该唯一性的保证手段。
6. WHEN 结算尝试写入的成长事件的 `(user_id, event_key)` 已存在 THEN THE 成长体系 SHALL 放弃该次插入、SHALL 保持已存在那一行的 `id`、`event_type`、`exp_amount` 与 `created_at` 取值不变，且 SHALL 继续完成本次结算的其余步骤（不抛出异常、不中止结算）。
7. WHEN 同一用户的两次结算连续执行且两次之间没有任何有效记账交易、预算或邀请关系的变化 THEN THE 成长体系 SHALL 使第二次结算后该用户的成长事件行数与成长档案的 `exp`、`level`、`total_record_days`、`current_streak_days`、`max_streak_days` 取值与第一次结算后完全相同（结算幂等）。
8. WHEN 同一用户的两个及以上结算在 1000 毫秒内并发执行 THEN THE 成长体系 SHALL 使该用户任一 `event_key` 的成长事件行数终态为至多 1，且 SHALL 使成长档案 `exp` 的终态等于该用户全部成长事件 `exp_amount` 之和。
9. WHEN 结算读取或写回成长档案 THEN THE 成长体系 SHALL 先对该用户的成长档案行加行级写锁再更新其列取值。
10. WHEN 某用户尚无成长档案且触发结算 THEN THE 成长体系 SHALL 在该次结算内创建该用户的成长档案行，并写入 `created_at` 与 `updated_at` 为同一服务端时刻。
11. WHEN 结算更新成长档案的任一列 THEN THE 成长体系 SHALL 把 `updated_at` 与 `last_settled_at` 置为该次结算的服务端时刻，且 SHALL 不修改 `user_id` 与 `created_at`。
12. THE 成长体系 SHALL 提供一个以 `growth_events` 与交易事实源为输入的全量重算操作；WHEN 对同一用户先执行任意次结算、再执行一次全量重算 THEN THE 成长体系 SHALL 使全量重算后成长档案的 `exp`、`level`、`total_record_days`、`current_streak_days`、`max_streak_days` 五列取值与重算前完全相同（增量维护结果与全量重算结果一致）。

### 需求 2：等级曲线（Lv1 到 Lv100）

**用户故事：** 作为用户，我希望等级怎么升是一条说得清的规则，我看得到下一级还差多少经验，也知道满级在哪。

#### 验收标准

1. THE 成长体系 SHALL 以 `threshold(L) = 2 × (L − 1)² + 8 × (L − 1)` 作为升到等级 L 所需的累计经验值，定义域 L 为 1 到 100 的整数，且 SHALL 使 `threshold(1) = 0`、`threshold(2) = 10`、`threshold(100) = 20394`。
2. THE 成长体系 SHALL 使 `threshold` 在定义域上严格单调递增：对任意 1 ≤ L < 100，`threshold(L)` SHALL 小于 `threshold(L + 1)`。
3. WHEN 由经验值 E 换算等级 THEN THE 成长体系 SHALL 返回满足 `threshold(L) ≤ E` 的最大整数 L，且 SHALL 使返回值不超过 100。
4. THE 成长体系 SHALL 以整数比较实现等级换算，且 SHALL 不使用浮点开方或浮点除法参与该换算（避免阈值边界因浮点误差错级）。
5. WHEN 经验值 E 等于某个 `threshold(L)` THEN THE 成长体系 SHALL 返回等级 L（阈值取等号即升级）。
6. WHEN 经验值 E 大于或等于 20394 THEN THE 成长体系 SHALL 返回等级 100；WHILE 用户处于满级 THE 成长体系 SHALL 继续累加其经验值并 SHALL 保持等级为 100（满级后经验继续累计、等级不再上升）。
7. WHEN 用户经验值增加 THEN THE 成长体系 SHALL 使其等级取值不减少（经验单调增导致等级单调不减）。
8. THE 成长体系 SHALL 在成长概览响应中返回当前等级、经验值、当前等级起始经验（等于 `threshold(当前等级)`）、下一等级所需经验（等于 `threshold(当前等级 + 1)`）、本级内已获得经验（等于经验值减去当前等级起始经验）、升级还需经验（等于下一等级所需经验减去经验值）与最高等级 100 七项。
9. WHERE 用户处于满级 THE 成长体系 SHALL 以空值返回下一等级所需经验与升级还需经验两项，并 SHALL 返回表明已达满级的标识为真。
10. THE 成长体系 SHALL 使成长概览响应中的「本级内已获得经验」大于或等于 0；WHERE 用户未达满级 THE 成长体系 SHALL 使「升级还需经验」大于或等于 1。
11. THE 成长体系 SHALL 不把等级阈值以逐级枚举的常量表形式写死在迁移脚本或数据库中，且 SHALL 由第 1 条的公式在应用启动或首次使用时派生全部 100 个阈值（等级曲线的唯一事实源是该公式）。

### 需求 3：经验来源与幂等键

**用户故事：** 作为用户，我希望我知道做什么能拿到经验、每件事能拿几次，不会出现同一件事反复加分或者莫名其妙加分。

#### 验收标准

1. THE 成长体系 SHALL 只发放以下六类经验事件，且 SHALL 不发放此清单之外的任何带正经验的事件：

   | 事件类型 | 事件键 | 经验值 | 发放频次 |
   | --- | --- | --- | --- |
   | `FIRST_RECORD` | `FIRST_RECORD` | 10 | 每个用户终身一次 |
   | `DAILY_RECORD` | `DAILY_RECORD:<YYYY-MM-DD>` | 5 | 每个记账日一次 |
   | `STREAK` | `STREAK_7` | 30 | 每个用户终身一次 |
   | `STREAK` | `STREAK_30` | 100 | 每个用户终身一次 |
   | `BUDGET_MET` | `BUDGET_MET:<YYYY-MM>` | 50 | 每个自然月一次 |
   | `FIRST_INVITE` | `FIRST_INVITE` | 80 | 每个用户终身一次 |

2. WHEN 结算发现该用户的累计记账笔数大于或等于 1 AND 该用户尚无 `FIRST_RECORD` 事件 THEN THE 成长体系 SHALL 写入一条 `event_type` 为 `FIRST_RECORD`、`event_key` 为 `FIRST_RECORD`、`exp_amount` 为 10 的成长事件。
3. WHEN 结算发现该用户的历史最长连续天数大于或等于 7 AND 该用户尚无 `STREAK_7` 事件 THEN THE 成长体系 SHALL 写入一条 `event_type` 为 `STREAK`、`event_key` 为 `STREAK_7`、`exp_amount` 为 30 的成长事件。
4. WHEN 结算发现该用户的历史最长连续天数大于或等于 30 AND 该用户尚无 `STREAK_30` 事件 THEN THE 成长体系 SHALL 写入一条 `event_type` 为 `STREAK`、`event_key` 为 `STREAK_30`、`exp_amount` 为 100 的成长事件。
5. THE 成长体系 SHALL 把 `STREAK_7` 与 `STREAK_30` 均视为终身一次性成就：WHEN 用户在已获得该事件之后再次达成同一连续天数门槛 THEN THE 成长体系 SHALL 不再写入该 `event_key` 的成长事件、SHALL 不增加经验值。
6. WHEN 结算发现该用户的历史最长连续天数大于或等于 30 AND 该用户尚无 `STREAK_7` 与 `STREAK_30` 两个事件 THEN THE 成长体系 SHALL 在同一次结算内写入这两个事件（共 130 经验），且 SHALL 不因门槛跨越而漏发较低门槛的事件。
7. THE 成长体系 SHALL 使 `DAILY_RECORD` 事件的 `event_key` 由固定前缀 `DAILY_RECORD:` 与该记账日按 `YYYY-MM-DD` 格式化的 `Asia/Shanghai` 日期拼成；THE 成长体系 SHALL 使 `BUDGET_MET` 事件的 `event_key` 由固定前缀 `BUDGET_MET:` 与该自然月按 `YYYY-MM` 格式化的取值拼成。
8. THE 成长体系 SHALL 使每条成长事件的 `event_key` 长度不超过 64 个字符、`event_type` 长度不超过 16 个字符，且 SHALL 使 `event_type` 取自 `FIRST_RECORD`、`DAILY_RECORD`、`STREAK`、`BUDGET_MET`、`FIRST_INVITE`、`BADGE` 六个区分大小写的取值。
9. WHEN 写入成长事件 THEN THE 成长体系 SHALL 把 `created_at` 置为该次结算的服务端时刻，且 SHALL 不在事件行中记录任何指向具体交易、预算或邀请关系的外键（成长事件不依赖其事实源的存续）。
10. THE 成长体系 SHALL 使单次结算写入的成长事件条数不超过 1016 条：至多 1000 条 `DAILY_RECORD`（见需求 4 第 6 条），加至多 1 条 `FIRST_RECORD`、2 条 `STREAK`、3 条 `BUDGET_MET`、1 条 `FIRST_INVITE` 与 9 条 `BADGE`（使单次结算的写入量有界）。
11. IF 某个应发放的经验事件因唯一索引冲突以外的数据库故障而写入失败 THEN THE 成长体系 SHALL 中止本次结算、SHALL 回滚本次结算已写入的成长事件与成长档案变更，SHALL 记录一条告警日志，且 SHALL 不向调用方传播异常（依需求 9 第 7 条的故障隔离）。

### 需求 4：记账日历、累计天数与连续天数

**用户故事：** 作为用户，我希望「连续记账」算的是我真的每天都来记了，而不是我某天把过去一个月的账一次补完就算连续一个月。

#### 验收标准

1. THE 成长体系 SHALL 以交易的 `created_at` 所在的 `Asia/Shanghai` 自然日作为该笔交易的记账日，且 SHALL 不使用 `occurred_at` 参与记账日、累计记账天数与连续天数的任何计算。
2. WHEN 结算发现结算日是该用户的记账日 AND 本次结算的追补窗口末日等于结算日（历史记账日已在本次结算内补齐）AND 该用户尚无 `DAILY_RECORD:<结算日>` 事件 THEN THE 成长体系 SHALL 写入该事件（`exp_amount` 为 5）。
3. IF 本次结算的追补起点晚于结算日（即 `last_record_date` 已等于结算日），或追补窗口内不存在尚不在记账日历中的记账日 THEN THE 成长体系 SHALL 不写入任何 `DAILY_RECORD` 事件、SHALL 不增加该用户经验值，且 SHALL 继续完成本次结算的其余步骤（不抛出异常、不中止结算）。
4. WHEN 用户在同一自然日内创建 2 至 100 笔有效记账交易并各触发一次结算 THEN THE 成长体系 SHALL 使该日的 `DAILY_RECORD` 事件条数为 1，且 SHALL 使该日因 `DAILY_RECORD` 增加的经验值合计为 5。
5. WHEN 用户为某个已在记账日历中的历史日期补记交易 THEN THE 成长体系 SHALL 不为该历史日期新增 `DAILY_RECORD` 事件（该日期的事件已存在），并 SHALL 按第 2 条为结算日写入 `DAILY_RECORD` 事件（补记行为发生在结算日）。
6. WHEN 结算追补历史记账日 THEN THE 成长体系 SHALL 先以一次 `MIN(created_at)` 聚合查询确定**追补起点**；SHALL 把**追补窗口**取为自追补起点起、长度至多 1000 个自然日且不越过结算日的连续自然日区间（窗口末日等于「追补起点加 999 天」与结算日两者中的较小者）；SHALL 仅以一次 distinct 日期聚合查询读取 `created_at` 落在半开区间 [追补起点 00:00, 窗口末日次日 00:00) 的有效记账交易的记账日集合（按 `created_by` 走既有索引 `idx_tx_created_by`，SHALL 不读取 `created_at` 早于追补起点的行、SHALL 不对交易表执行无 `created_at` 上下界的全量扫描）；并 SHALL 按日期升序为该集合中不在记账日历里的每个记账日写入 `DAILY_RECORD` 事件；THE 成长体系 SHALL 使单次结算的追补查询次数不超过 2 次、写入的 `DAILY_RECORD` 事件条数（含结算日那一条）不超过 1000 条。
7. THE 成长体系 SHALL 使累计记账天数等于该用户 `event_type` 为 `DAILY_RECORD` 的成长事件条数，且 SHALL 把该取值物化到成长档案的 `total_record_days` 列。
8. THE 成长体系 SHALL 以记账日历（而非交易事实源）作为连续段长度与历史最长连续天数的唯一计算依据；WHEN 用户删除某记账日的全部有效记账交易 THEN THE 成长体系 SHALL 保持该用户的累计记账天数、连续段长度与历史最长连续天数取值不变。
9. THE 成长体系 SHALL 把连续段长度物化到成长档案的 `current_streak_days` 列、把历史最长连续天数物化到 `max_streak_days` 列、把最近记账日物化到 `last_record_date` 列，并 SHALL 使 `max_streak_days` 大于或等于 `current_streak_days`；WHEN 一次结算提交 THEN THE 成长体系 SHALL 使以下两条不变式成立：`last_record_date` 恒等于记账日历中的最大日期（记账日历为空时 `last_record_date` 为空值），且不存在早于 `last_record_date` 且不在记账日历中的记账日（记账日历在 `last_record_date` 之前无空洞，故第 6 条以 `last_record_date` 推导追补起点不会漏日）。
10. WHEN 记账日历为空 THEN THE 成长体系 SHALL 使 `total_record_days`、`current_streak_days`、`max_streak_days` 三列取值均为 0，且 SHALL 使 `last_record_date` 为空值。
11. WHEN 成长概览响应返回当前连续天数 AND `last_record_date` 等于**判定日**或判定日的前一日 THEN THE 成长体系 SHALL 返回等于 `current_streak_days` 的取值。
12. WHEN 记账日历包含日期 D 与 D 的次日 THEN THE 成长体系 SHALL 把这两个日期计入同一连续自然日区间；WHEN 记账日历包含 D 与 D 之后第 2 日但不含 D 的次日 THEN THE 成长体系 SHALL 把这两个日期计入不同的连续自然日区间。
13. WHEN 对同一记账日历分别以增量维护与全量重算两种方式计算 THEN THE 成长体系 SHALL 得到相同的累计记账天数、连续段长度与历史最长连续天数（对齐需求 1 第 12 条）。
14. IF 本次结算的追补窗口末日早于结算日（该用户仍有未补发的历史记账日）THEN THE 成长体系 SHALL 不在本次结算内为结算日写入 `DAILY_RECORD:<结算日>` 事件（以免 `last_record_date` 越过尚未补发的记账日、破坏第 9 条的无空洞不变式）、SHALL 把本次结算的 `last_record_date` 置为本次窗口内最大的已补发记账日、SHALL 在下一次结算以更新后的 `last_record_date` 为依据按第 6 条继续追补，SHALL 使每次结算的追补起点严格晚于上一次结算的追补起点，且 SHALL 使补齐该用户全部历史记账日所需的结算次数不超过其尚未补发的记账日个数（每次结算至少补发 1 个记账日，追补在有限次结算内必然收敛）。
15. IF 成长概览响应生成时 `last_record_date` 早于判定日的前一日，或 `last_record_date` 为空值 THEN THE 成长体系 SHALL 返回当前连续天数为 0、SHALL 以该基于判定日的实时判定结果为准而不返回物化的 `current_streak_days` 取值，且 SHALL 不因该次读取而修改 `current_streak_days` 列（物化列只承载连续段长度；「连续是否已中断」一律在读取时以判定日实时判定，因此读取时已跨日或本次结算失败时均不会返回过期的非零连续天数）。
16. THE 成长体系 SHALL 以固定偏移 UTC+08:00 的 `Asia/Shanghai` 时区界定记账日、结算日、判定日、追补窗口边界与全部自然日边界，且 SHALL 不依赖 JVM、数据库会话或操作系统的默认时区取值；WHEN 运行环境的默认时区被改为任一其它时区 THEN THE 成长体系 SHALL 使同一批交易计算出的记账日、`DAILY_RECORD` 事件键、累计记账天数、连续段长度与历史最长连续天数取值保持不变（`Asia/Shanghai` 不实行夏令时，任一自然日恒为 24 小时，日期加减不出现 23 或 25 小时的偏移）。

### 需求 5：预算达成经验

**用户故事：** 作为用户，我希望我一个月没超预算这件事能被系统承认，而且不需要我在月底那天刚好打开小程序。

#### 验收标准

1. THE 成长体系 SHALL 在每次结算时判定「结算日所属自然月的前 1 个、前 2 个与前 3 个自然月」共 3 个已结束自然月的预算达成情况，且 SHALL 不判定结算日所属的自然月（未结束的自然月不参与判定）。
2. WHEN 结算判定某已结束自然月 M 达成预算 AND 该用户尚无 `BUDGET_MET:M` 事件 THEN THE 成长体系 SHALL 写入一条 `event_type` 为 `BUDGET_MET`、`event_key` 为 `BUDGET_MET:M`、`exp_amount` 为 50 的成长事件。
3. THE 成长体系 SHALL 把「某已结束自然月 M 达成预算」判定为：存在至少一个该用户拥有的账本 L（`ledgers.user_id` 等于该用户 id），使 `budgets` 中存在 `(ledger_id = L, budget_month = M)` 的行、该账本在 M 内的月度有效支出合计大于 0、且该合计小于或等于该行的 `amount`。
4. IF 某已结束自然月 M 内该用户拥有的全部账本都没有 `(ledger_id, budget_month = M)` 的 `budgets` 行 THEN THE 成长体系 SHALL 判定 M 未达成预算、SHALL 不写入 `BUDGET_MET:M` 事件（未设总预算即无从达成）。
5. IF 某已结束自然月 M 内某账本已设总预算 AND 该账本在 M 内的月度有效支出合计等于 0 THEN THE 成长体系 SHALL 判定该账本在 M 未达成预算（零支出不算达成）。
6. IF 某已结束自然月 M 内某账本的月度有效支出合计大于该账本该月的总预算金额 THEN THE 成长体系 SHALL 判定该账本在 M 未达成预算。
7. WHEN 某已结束自然月 M 内该用户拥有 2 个及以上均达成预算的账本 THEN THE 成长体系 SHALL 为 M 写入恰好 1 条 `BUDGET_MET:M` 事件、SHALL 使 M 因预算达成增加的经验值合计为 50（多账本不叠加、不可通过新建账本刷取）。
8. WHEN 用户在某已结束自然月 M 的 `BUDGET_MET:M` 事件已发放之后下调、删除该月总预算，或新增该月的支出交易 THEN THE 成长体系 SHALL 保持该事件不变、SHALL 不撤销该 50 经验（经验只增不减）。
9. WHEN 用户连续 3 个自然月未打开小程序、随后触发一次结算 THEN THE 成长体系 SHALL 判定这 3 个已结束自然月并为其中达成预算的每个月各写入 1 条 `BUDGET_MET` 事件（回看窗口容忍 3 个月不活跃）。
10. IF 某达成预算的自然月早于结算日所属自然月 4 个月及以上 THEN THE 成长体系 SHALL 不为该月写入 `BUDGET_MET` 事件（回看窗口固定为 3 个已结束自然月，使单次结算的查询次数有界）。
11. THE 成长体系 SHALL 使月度有效支出合计的聚合口径与 `BudgetService` 既有的月度支出聚合完全一致：按 `ledger_id` 过滤、只计 `type` 为 `expense` 的行、排除 `deleted_at` 非空的行、按 `occurred_at` 落在半开区间 [该月 1 日 00:00, 次月 1 日 00:00) 取值，金额保留 2 位小数。
12. THE 成长体系 SHALL 不修改 `budgets` 与 `category_budgets` 两表的任何行，且 SHALL 不改变预算模块既有接口的响应字段与错误码。
13. THE 成长体系 SHALL 不把该用户作为成员参与的协作账本（`ledgers.user_id` 不等于该用户 id）纳入预算达成判定；WHEN 某协作账本在已结束自然月 M 达成其预算 THEN THE 成长体系 SHALL 不因此为该成员写入 `BUDGET_MET:M` 事件（该预算不由该成员设定）。该口径与需求 7 第 8 条「累计统计跨全部账本合并」刻意不同：预算达成衡量「自己设的预算守住了没有」，只能在自己拥有的账本集合内判定；累计统计衡量「自己记了多少账」，与账本归属无关。THE 成长体系 SHALL 使这两处的查询条件彼此独立、SHALL 不复用同一段过滤条件。
14. IF `BudgetService` 既有的月度支出聚合口径将来发生变更 THEN THE 成长体系 SHALL 以本需求第 11 条自述的口径为准、SHALL 不自动跟随该变更，且该口径的任何调整 SHALL 先修改本需求文档。
15. THE 成长体系 SHALL 使单次结算内预算判定所执行的数据库读查询次数不超过 8 次（1 次取该用户拥有的账本清单、1 次取其已有的 `BUDGET_MET` 事件键、3 次取三个回看月的预算行、3 次取三个回看月的支出合计），且 SHALL 使该次数不随该用户拥有的账本数量增长。

### 需求 6：首次邀请好友经验

**用户故事：** 作为用户，我希望我拉来的第一个朋友能给我记一笔成长；作为开发者，我希望这条经验绝不会拖累注册流程。

#### 验收标准

1. WHEN 结算发现 `invite_relations` 中 `inviter_id` 等于该用户 id 且 `status` 为 `REGISTERED` 的行数大于或等于 1 AND 该用户尚无 `FIRST_INVITE` 事件 THEN THE 成长体系 SHALL 写入一条 `event_type` 为 `FIRST_INVITE`、`event_key` 为 `FIRST_INVITE`、`exp_amount` 为 80 的成长事件。
2. THE 成长体系 SHALL 把 `FIRST_INVITE` 作为终身一次性事件：WHEN 该用户名下 `status` 为 `REGISTERED` 的邀请关系行数从 1 增加到 100 THEN THE 成长体系 SHALL 保持 `FIRST_INVITE` 事件条数为 1、SHALL 不因邀请更多用户而增加经验值。
3. WHEN 该用户的被邀请人注销致其邀请关系 `status` 变为 `INVALID`、使 `REGISTERED` 行数回落到 0 THEN THE 成长体系 SHALL 保持已写入的 `FIRST_INVITE` 事件不变、SHALL 不撤销该 80 经验（经验只增不减）。
4. THE 成长体系 SHALL 只对 `invite_relations` 表执行读取语句，且 SHALL 不对该表执行任何插入、更新或删除语句。
5. THE 成长体系 SHALL 不在被邀请人的登录/注册请求内为邀请人执行结算或写入任何成长事件；WHEN 邀请人在其被邀请人注册之后首次触发结算 THEN THE 成长体系 SHALL 在该次结算内补发 `FIRST_INVITE` 事件（邀请经验延迟到邀请人自己的结算，避免耦合注册主路径）。
6. THE 成长体系 SHALL 不修改 `AuthService`、`InviteBindingService` 与登录/注册接口的既有行为、响应字段与错误码；WHEN 成长体系不可用 THEN THE 登录/注册接口 SHALL 照常成功并签发令牌。
7. IF 读取 `invite_relations` 时发生数据库异常 THEN THE 成长体系 SHALL 中止本次结算、SHALL 记录一条告警日志、SHALL 不向调用方传播异常，且 SHALL 保持该用户的成长事件与成长档案为本次结算前的状态。

### 需求 7：累计统计口径

**用户故事：** 作为用户，我希望成长页上的累计笔数和累计金额跟我实际记的账对得上，删掉的账不该还算在里面。

#### 验收标准

1. THE 成长体系 SHALL 以 `transactions.created_by` 等于当前用户 id 作为累计统计的唯一归属依据，且 SHALL 不使用 `transactions.user_id` 参与任何统计（该列已于 `V9__ledger_enforce.sql` 放宽为可空且服务层不再写入）。
2. THE 成长体系 SHALL 把累计记账笔数定义为该用户有效记账交易的行数，即同时满足 `created_by` 等于该用户 id、`deleted_at` 为 NULL、`type` 属于 `expense`/`income`、`ledger_id` 非 NULL 四条的 `transactions` 行数。
3. THE 成长体系 SHALL 把累计支出金额定义为该用户 `type` 为 `expense` 的有效记账交易的 `amount` 合计、把累计收入金额定义为该用户 `type` 为 `income` 的有效记账交易的 `amount` 合计，两者均保留 2 位小数，且在无匹配行时取值为 `0.00`。
4. THE 成长体系 SHALL 把 `type` 为 `transfer` 的交易排除在累计记账笔数、累计支出金额与累计收入金额之外（转账不是收支）。
5. THE 成长体系 SHALL 把 `ledger_id` 为 NULL 的交易排除在累计记账笔数、累计支出金额与累计收入金额之外（余额调整与转账均以 `ledger_id` 为 NULL 落库，不计入记账统计）。
6. THE 成长体系 SHALL 把 `deleted_at` 非 NULL 的交易排除在累计记账笔数、累计支出金额与累计收入金额之外；WHEN 用户把一笔交易移入回收站 THEN THE 成长体系 SHALL 使累计记账笔数减 1、使对应的累计金额减去该笔金额，且 SHALL 保持该用户经验值、等级与全部成长事件不变。
7. WHEN 用户从回收站恢复一笔交易 THEN THE 成长体系 SHALL 使累计记账笔数加 1、使对应的累计金额加上该笔金额，且 SHALL 保持该用户经验值、等级与全部成长事件不变。
8. THE 成长体系 SHALL 合并该用户在全部账本（含其作为成员参与的协作账本）内记的账计算累计统计，且 SHALL 不按会话账本过滤（成长数据与当前账本无关）。
9. THE 成长体系 SHALL 以实时聚合查询读取累计记账笔数、累计支出金额与累计收入金额，且 SHALL 不把这三项物化到 `user_growth` 表的任何列（避免与事实源产生对账负担）。
10. THE 成长体系 SHALL 使累计记账笔数为 0 时累计支出金额与累计收入金额均为 `0.00`，且 SHALL 使这三项取值均大于或等于 0。
11. THE 成长体系 SHALL 把全部金额以 `BigDecimal`（`DECIMAL(18,2)` 口径）参与计算与返回，且 SHALL 不使用浮点类型参与金额的求和与舍入。
12. THE 成长体系 SHALL 不为累计统计新增任何交易表的列或索引；WHERE 需要按 `created_by` 过滤 THE 成长体系 SHALL 复用既有索引 `idx_tx_created_by`（该索引由 `V13__transaction_created_by.sql` 建立，是仅含 `created_by` 一列的**单列索引**，不覆盖 `deleted_at`、`type` 与 `ledger_id`）；THE 成长体系 SHALL 接受这三个条件以回表方式过滤，且 SHALL 以第 13 条的耗时上界成立作为不新增覆盖索引的前提。
13. WHEN 某用户的有效记账交易达到 10 万笔 THEN THE 成长体系 SHALL 使累计记账笔数、累计支出金额与累计收入金额三项聚合的服务端耗时合计不超过 500 毫秒（不含网络传输耗时；与需求 9 第 12 条的 2000 毫秒自洽——结算与响应组装占用其余 1500 毫秒）；IF 该合计耗时超过 500 毫秒 THEN THE 成长体系 SHALL 记录一条告警日志且 SHALL 不使本次请求失败。
14. THE 成长体系 SHALL 把累计支出金额与累计收入金额的上界取为 `DECIMAL(18,2)` 可表示的最大值 `9999999999999999.99`；IF 某项合计超过该上界 THEN THE 成长体系 SHALL 以该上界返回该项、SHALL 记录一条告警日志，且 SHALL 不返回负值或回绕值、SHALL 不使本次请求失败。
15. WHERE 某笔有效记账交易的 `amount` 小于 `0.01`（既有 CHECK 约束 `ck_tx_amount_positive` 已禁止该取值，本条仅覆盖历史数据或约束缺失的情形）THE 成长体系 SHALL 仍把该笔计入累计记账笔数、SHALL 以其原值计入对应的金额合计，且 SHALL 不取绝对值、SHALL 不跳过该行；IF 某项合计因此为负 THEN THE 成长体系 SHALL 以 `0.00` 返回该项。

### 需求 8：徽章体系

**用户故事：** 作为用户，我希望我攒下的成就能亮在页面上，而且亮了就一直亮着，不会因为我整理了一下旧账目就熄灭。

#### 验收标准

1. THE 成长体系 SHALL 实现且仅实现以下 9 枚徽章，其编码、名称与点亮条件如下：

   | 徽章编码 | 名称 | 点亮条件 |
   | --- | --- | --- |
   | `FIRST_RECORD` | 开张 | 累计记账笔数大于或等于 1 |
   | `RECORD_10` | 小有账目 | 累计记账笔数大于或等于 10 |
   | `RECORD_100` | 百笔有余 | 累计记账笔数大于或等于 100 |
   | `RECORD_1000` | 千笔如一 | 累计记账笔数大于或等于 1000 |
   | `STREAK_7` | 七日不辍 | 历史最长连续天数大于或等于 7 |
   | `STREAK_30` | 卅日成习 | 历史最长连续天数大于或等于 30 |
   | `DAYS_100` | 百日记账 | 累计记账天数大于或等于 100 |
   | `BUDGET_MET` | 预算达标 | 该用户存在至少一条 `event_type` 为 `BUDGET_MET` 的成长事件 |
   | `INVITE_1` | 同行有余 | 该用户存在 `event_key` 为 `FIRST_INVITE` 的成长事件 |

2. WHEN 结算发现某枚徽章的点亮条件成立 AND 该用户尚无该徽章编码对应的 `BADGE` 事件 THEN THE 成长体系 SHALL 写入一条 `event_type` 为 `BADGE`、`event_key` 为 `BADGE:<徽章编码>`、`exp_amount` 为 0 的成长事件。
3. THE 成长体系 SHALL 使全部 `BADGE` 事件的 `exp_amount` 为 0（徽章不额外发放经验，避免同一行为两次计分）；WHEN 一次结算点亮 1 至 9 枚徽章 THEN THE 成长体系 SHALL 使该用户经验值因这些徽章增加 0。
4. THE 成长体系 SHALL 以「该用户存在对应 `BADGE` 事件」作为徽章已点亮的唯一判定依据；WHEN 用户删除交易使累计记账笔数低于某枚已点亮徽章的门槛 THEN THE 成长体系 SHALL 保持该徽章为已点亮（徽章一经点亮永不熄灭）。
5. THE 成长体系 SHALL 在成长概览响应中返回全部 9 枚徽章（含未点亮的），每枚 SHALL 包含徽章编码、名称、是否已点亮、解锁时刻、目标值与当前值六个字段。
6. WHERE 某枚徽章已点亮 THE 成长体系 SHALL 返回该徽章的解锁时刻为对应 `BADGE` 事件的 `created_at`；WHERE 某枚徽章未点亮 THE 成长体系 SHALL 以空值返回该徽章的解锁时刻。
7. THE 成长体系 SHALL 使每枚徽章返回的目标值等于第 1 条中该徽章点亮条件的门槛数值（`BUDGET_MET` 与 `INVITE_1` 两枚的目标值为 1），并 SHALL 使当前值为该门槛所对应统计口径的当前取值。
8. THE 成长体系 SHALL 按第 1 条表格中的顺序返回徽章列表，且 SHALL 使两次连续请求返回的徽章顺序相同。
9. THE 成长体系 SHALL 不新建徽章表、SHALL 不为徽章新增任何数据库列（徽章数据全部落在 `growth_events`）。
10. THE 成长体系 SHALL 把徽章的编码、展示名称、点亮门槛、目标值与展示顺序统一在服务端代码中以单一常量定义为唯一事实源，SHALL 使展示名称随成长概览响应下发，且 SHALL 不在迁移脚本、数据库或 miniapp 中重复定义该清单的任何门槛数值或展示名称。
11. THE 成长体系 SHALL 把 `BADGE:` 前缀作为徽章事件的独占命名空间：WHERE 某徽章编码与需求 3 的事件类型或事件键同名（`FIRST_RECORD`、`STREAK_7`、`STREAK_30`、`BUDGET_MET` 四者）THE 成长体系 SHALL 仅以 `event_type` 为 `BADGE` 且 `event_key` 等于 `BADGE:<编码>` 的行判定该徽章是否已点亮，且 SHALL 不把这些 `BADGE` 行计入需求 3 的经验事件判定、需求 4 的累计记账天数，或本需求第 1 条中 `BUDGET_MET` 徽章的点亮条件（该条件只看 `event_type` 为 `BUDGET_MET` 的行）；反向地，THE 成长体系 SHALL 不把任何非 `BADGE` 类型的行当作徽章已点亮的依据（双向隔离）。
12. WHERE 某枚徽章已点亮 THE 成长体系 SHALL 使其返回的当前值恒等于其目标值（不因用户删除交易使统计回落而展示进度回退）；WHERE 某枚徽章未点亮 THE 成长体系 SHALL 使其返回的当前值等于该门槛对应统计量当前取值与目标值两者中的较小者；THE 成长体系 SHALL 使任一徽章返回的当前值落在 0 到其目标值的闭区间内。
13. IF 某枚徽章的点亮条件已成立但因结算失败或结算被节流而尚未写入对应的 `BADGE` 事件 THEN THE 成长体系 SHALL 返回该徽章为未点亮、SHALL 返回其当前值等于目标值、SHALL 以空值返回其解锁时刻，且 SHALL 不返回错误（该徽章由下一次成功的结算按第 2 条自愈点亮）。

### 需求 9：结算时机、事务边界与故障隔离

**用户故事：** 作为开发者，我要的是成长体系彻底挂掉的那天，记账、预算、登录、注销、邀请一个都不受影响。

#### 验收标准

1. THE 成长体系 SHALL 在以下两类时机触发结算：新增有效记账交易的业务事务成功提交之后；已认证用户请求成长概览时（先结算再返回）。
2. THE 成长体系 SHALL 不在以下路径触发结算：转账、余额调整、交易修改、交易删除、回收站恢复与彻底删除、预算的任何写入、登录/注册、账号注销、邀请关系绑定。
3. THE 成长体系 SHALL 以承载新增记账交易的业务事务的**提交后回调**（Spring 的 `TransactionSynchronization.afterCommit` 或 `@TransactionalEventListener(AFTER_COMMIT)`）作为触发点，SHALL 在该回调内以 `REQUIRES_NEW` 开启与该业务事务相互独立的事务完成结算的读写，且 SHALL 在**调用线程内同步执行**该结算（选择同步的理由：用户提交记账后立即打开成长页即可看到已到账的经验，无需处理「刚记完账但等级还没涨」的观感问题；代价是结算耗时计入记账接口响应耗时，由第 13、14 条约束）；WHEN 结算回滚 THEN THE 成长体系 SHALL 保持该笔交易与其账户余额变更已提交的状态不变。
4. THE 成长体系 SHALL 以「一次导入请求 = 一次业务事务提交 = 一次结算」作为结算次数的判定单位：WHEN 批量新增有效记账交易（账单导入或数据导入）在单个业务事务内成功提交 THEN THE 成长体系 SHALL 对该次导入执行恰好 1 次结算、SHALL 把该事务内注册的多个提交后回调合并为 1 次结算，且 SHALL 不按导入的每一行分别触发结算；IF 一次导入请求被实现拆分为 N 个业务事务 THEN THE 成长体系 SHALL 使该请求触发的结算次数不超过 N。
5. IF 结算抛出任何异常（含运行时异常、受检异常、行锁等待超时与数据库连接获取失败）THEN THE 成长体系 SHALL 在提交后回调内部捕获该异常、SHALL 记录一条包含用户 id 的告警日志，SHALL 不使该异常穿出该回调，且 SHALL 不向业务调用方传播该异常。
6. WHEN 结算失败 THEN THE 记账接口 SHALL 返回与结算成功时相同的 HTTP 状态码与相同的响应字段集（记账响应不含任何成长字段，结算成败对记账不可见）。
7. IF 结算在写入成长事件或成长档案的过程中失败 THEN THE 成长体系 SHALL 回滚本次结算内已执行的全部写入，SHALL 保持该用户的成长事件行数与成长档案全部列取值为本次结算前的状态，且 SHALL 不产生部分写入。
8. WHEN 某次结算失败之后该用户再次触发结算 THEN THE 成长体系 SHALL 在该次结算内补齐上次未写入的成长事件（结算幂等可重入，失败自愈，无需补偿任务）。
9. THE 成长体系 SHALL 不引入定时任务、消息队列、`@Async`、线程池或任何执行器来驱动结算（结算只由第 1 条的两类时机在调用线程内同步触发，与第 3 条的同步执行自洽）。
10. IF 成长概览请求内的结算失败 THEN THE 成长体系 SHALL 返回该用户成长档案的当前持久化取值与实时聚合的累计统计、SHALL 不返回服务端错误，且 SHALL 使响应字段集与结算成功时相同（可能返回略旧的经验与等级）。
11. IF 成长概览请求内的结算失败 AND 该用户尚无成长档案 THEN THE 成长体系 SHALL 返回等级 1、经验值 0、累计记账天数 0、当前连续天数 0、历史最长连续天数 0 与 9 枚均未点亮的徽章，并 SHALL 返回真实的累计记账笔数与累计金额。
12. WHEN 已认证用户请求成长概览 THEN THE 成长体系 SHALL 在服务端处理耗时不超过 2000 毫秒内返回成功结果或错误标识（含结算耗时，不含网络传输耗时）。
13. WHEN 新增有效记账交易触发的结算执行 THEN THE 成长体系 SHALL 使该结算的服务端处理耗时（口径为「开启独立事务到该事务提交或回滚」）不超过 1000 毫秒；IF 该结算耗时超过 1000 毫秒 THEN THE 成长体系 SHALL 记录一条含用户 id 与实际耗时的告警日志，且 SHALL 不中断已提交的记账结果。
14. THE 成长体系 SHALL 使记账接口的端到端服务端耗时预算为 2000 毫秒，其中同步结算至多占用 1000 毫秒、记账自身的处理预算不低于 1000 毫秒（与本项目其它接口的 2000 毫秒口径一致）；THE 成长体系 SHALL 不因新增结算而使记账接口的耗时预算超过 2000 毫秒。
15. IF 记账触发的结算发现该用户成长档案的 `last_settled_at` 距结算时刻不足 60 秒 AND `last_record_date` 已等于结算日 THEN THE 成长体系 SHALL 跳过本次结算、SHALL 不开启结算事务、SHALL 不写入任何成长事件与成长档案列取值（结算节流）；被该节流推迟的经验与徽章 SHALL 由后续任一次结算按第 8 条的幂等自愈补齐；THE 成长体系 SHALL 不对成长概览请求触发的结算施加该 60 秒节流。
16. IF 结算在 500 毫秒内未取得该用户成长档案行的写锁 THEN THE 成长体系 SHALL 放弃本次结算、SHALL 回滚本次结算已执行的写入、SHALL 记录一条告警日志、SHALL 不向调用方传播异常，且应发放的成长事件 SHALL 由下一次结算补齐。

### 需求 10：成长查询接口与权限

**用户故事：** 作为开发者，我希望成长接口只能读到自己的成长数据，不能靠改参数看别人的等级。

#### 验收标准

1. THE 成长体系 SHALL 提供已认证用户查询自身成长概览的接口，其成功响应 SHALL 包含等级、经验值、当前等级起始经验、下一等级所需经验、本级内已获得经验、升级还需经验、最高等级、是否满级、累计记账笔数、累计支出金额、累计收入金额、累计记账天数、当前连续天数、历史最长连续天数与徽章列表十五项。
2. THE 成长体系 SHALL 提供已认证用户查询自身经验明细的接口，支持分页参数 `page`（整数，取值范围 0 到 100000，缺省 0）与 `size`（整数，取值范围 1 到 50，缺省 20）。
3. WHEN 已认证用户请求经验明细 THEN THE 成长体系 SHALL 仅返回 `user_id` 等于当前会话用户的成长事件，按 `id` 倒序排列，每项 SHALL 包含事件 `id`、事件类型、事件键、经验值与发生时刻五个字段。
4. WHEN 已认证用户以生效取值为 `page` 与 `size` 的分页参数请求经验明细 THEN THE 成长体系 SHALL 自该排序序列的第 `page × size + 1` 条起返回列表项，且单次请求返回的列表项条数 SHALL 不超过生效的 `size`。
5. THE 成长体系 SHALL 在经验明细响应中返回该用户的成长事件总条数，该总条数 SHALL 不受 `page` 与 `size` 影响；WHEN 客户端以同一 `size` 逐页取完全部页 THEN 各页返回的列表项条数之和 SHALL 等于该总条数。
6. THE 成长体系 SHALL 要求成长概览接口与经验明细接口携带**有效令牌**；IF 请求未携带令牌、令牌签名校验失败、令牌已过期、或其标识的用户在 `users` 表中不存在 THEN THE 成长体系 SHALL 返回 `UNAUTHENTICATED`（优先于任何字段校验），且 SHALL 保持 `growth_events` 与 `user_growth` 两表数据不变。
7. THE 成长体系 SHALL 由接口在 JWT 过滤链之外显式执行「令牌用户在 `users` 表中仍存在」的库查询（过滤链只校验签名与有效期、不查库，因此已注销用户的未过期令牌仍会被过滤链放行）；THE 成长体系 SHALL 使该校验先于结算、分页参数校验与任何聚合查询执行，并在不通过时返回 `UNAUTHENTICATED`。
8. THE 成长体系 SHALL 把两个接口的数据范围硬性限定为当前会话用户本人的成长档案与成长事件，SHALL 以有效令牌所标识的用户 id 作为唯一的数据归属依据，且 SHALL 忽略请求中任何用于指定目标用户身份的输入字段。
9. IF 经验明细请求的 `page` 或 `size` 无法解析为整数，或 `page` 小于 0，或 `page` 大于 100000，或 `size` 小于 1，或 `size` 大于 50 THEN THE 成长体系 SHALL 拒绝该请求并返回 `GROWTH_PAGE_PARAM_INVALID`，且响应中 SHALL 不包含任何列表项与任何计数值。
10. WHEN 当前用户没有任何成长事件，或请求的页码超出已有数据范围 THEN THE 成长体系 SHALL 返回空列表与真实的成长事件总条数，且 SHALL 不返回错误。
11. WHEN 已认证用户请求经验明细 THEN THE 成长体系 SHALL 在服务端处理耗时不超过 2000 毫秒内返回成功结果或错误标识（不含网络传输耗时），且 SHALL 不在该接口内触发结算；THE 成长体系 SHALL 允许经验明细返回的数据比成长概览旧（同一时刻两接口的经验值合计可不相等），且 SHALL 不因该差异返回错误、SHALL 不因该差异写入任何表。
12. THE 成长体系 SHALL 使两个接口的数据与会话账本无关，且 SHALL 不要求请求携带 `X-Ledger-Id` 头、SHALL 不因该头缺失或取值不可访问而拒绝请求。
13. THE 成长体系 SHALL 使成长概览接口的成功响应字段集**恰好等于**第 1 条的 15 项、使经验明细接口的列表项字段集恰好等于第 3 条的 5 项且其顶层字段集恰好为「列表项与总条数」2 项；由该相等性，两个接口的响应 SHALL 不含 `email`、`wx_openid`、`wx_unionid`、`invite_code`、`plan` 与 `role` 六个字段的键与取值。
14. IF 同一用户由成长概览接口驱动的结算在最近 10 秒内已执行过 1 次 THEN THE 成长体系 SHALL 跳过本次结算、SHALL 返回该用户成长档案的当前持久化取值与实时聚合的累计统计、SHALL 使响应字段集与执行结算时相同，且 SHALL 不返回错误、SHALL 不新增错误码（成长概览是本 spec 唯一的写入型 GET 接口，选择「降级跳过结算」而非「拒绝请求」，以免为一个只读自身数据的接口引入新错误码并损害体验）；THE 成长体系 SHALL 以 `user_id` 作为该节流的统计维度、SHALL 把节流状态保存在应用实例进程内的内存中、SHALL 在进程启动后该用户的首次请求执行结算，且 SHALL 不因该节流影响需求 9 第 1 条中记账提交后触发的结算。
15. THE 成长体系 SHALL 以统一错误体格式 `{code, message, field}` 返回错误，且 SHALL 只新增 `GROWTH_PAGE_PARAM_INVALID` 一个错误码（结算失败与结算节流均不对外暴露错误码，见需求 9 第 10 条与本需求第 14 条）。

### 需求 11：数据模型与迁移

**用户故事：** 作为开发者，我需要成长档案与成长事件有清晰的表结构和迁移脚本，能与既有 Flyway 迁移体系一致地演进。

#### 验收标准

1. THE 迁移脚本 SHALL 新建 `user_growth` 表，该表 SHALL 恰好包含以下 10 列：`user_id`（BIGINT NOT NULL，主键，SHALL 不声明 `AUTO_INCREMENT`，其取值 SHALL 等于 `users.id` 中已存在的取值、由服务层在该用户首次结算时以有效令牌所标识的用户 id 写入、SHALL 不由数据库生成；THE 迁移脚本 SHALL 不为该表新增独立自增主键列、SHALL 不为 `user_id` 另建唯一约束——成长档案与用户是一对一关系，以 `user_id` 直接作主键使「每个用户至多一行」由主键保证，并使按 `user_id` 读写档案不经二级索引回表）、`exp`（BIGINT NOT NULL，缺省 0）、`level`（INT NOT NULL，缺省 1）、`total_record_days`（INT NOT NULL，缺省 0）、`current_streak_days`（INT NOT NULL，缺省 0）、`max_streak_days`（INT NOT NULL，缺省 0）、`last_record_date`（DATE NULL）、`last_settled_at`（DATETIME NULL）、`created_at`（DATETIME NOT NULL）、`updated_at`（DATETIME NOT NULL）。
2. THE 迁移脚本 SHALL 新建 `growth_events` 表，该表 SHALL 恰好包含以下 6 列：`id`（BIGINT NOT NULL AUTO_INCREMENT，主键）、`user_id`（BIGINT NOT NULL）、`event_type`（VARCHAR(16) NOT NULL）、`event_key`（VARCHAR(64) NOT NULL）、`exp_amount`（INT NOT NULL，缺省 0）、`created_at`（DATETIME NOT NULL）。
3. THE 迁移脚本 SHALL 为 `growth_events` 建立名为 `uk_growth_events_user_key` 的具名唯一约束，其列为 `(user_id, event_key)`（同一用户同一事件键至多一行）。
4. THE 迁移脚本 SHALL 为 `growth_events` 建立名为 `idx_growth_events_user_type` 的非唯一复合索引（列序恰为 `user_id`、`event_type`，支撑按类型统计与徽章判定），以及名为 `idx_growth_events_user_id` 的非唯一复合索引（列序恰为 `user_id`、`id`，支撑经验明细按 `id` 倒序翻页）；两个索引的全部列 SHALL 为升序，THE 迁移脚本 SHALL 不在任何索引列上声明 `DESC`，且 SHALL 不使用以 `_desc` 结尾的索引名（InnoDB 对 `WHERE user_id = ? ORDER BY id DESC` 反向扫描升序索引即可，无需降序索引；带 `_desc` 后缀的名字会与 `information_schema.statistics` 中该索引两列 `COLLATION` 均为 `A`（升序）的实际方向不符）。
5. THE 迁移脚本 SHALL 建立名为 `ck_growth_events_type` 的具名 CHECK 约束，其表达式 SHALL 写作 `event_type COLLATE utf8mb4_bin IN ('FIRST_RECORD', 'DAILY_RECORD', 'STREAK', 'BUDGET_MET', 'FIRST_INVITE', 'BADGE')`，以把 `event_type` 限制为区分大小写的这六个取值（小写或混合大小写的同名取值 SHALL 被视为非法），写法对齐 `V31__user_invite.sql` 中 `ck_invite_relations_status`（表默认排序规则 `utf8mb4_unicode_ci` 大小写不敏感，不显式 `COLLATE` 时 `first_record` 亦会通过）；THE 迁移脚本 SHALL 使该约束落库后 `information_schema.CHECK_CONSTRAINTS` 中该约束名对应的 `CHECK_CLAUSE` 含 `utf8mb4_bin`，且 SHALL 使 `event_type` 列的 `COLLATION_NAME` 与该表的 `TABLE_COLLATION` 仍为 `utf8mb4_unicode_ci`。
6. THE 迁移脚本 SHALL 建立名为 `ck_growth_events_exp` 的具名 CHECK 约束限制 `exp_amount` 大于或等于 0，以及名为 `ck_user_growth_level` 的具名 CHECK 约束限制 `level` 取值在 1 到 100 之间。
7. IF 向 `growth_events` 插入或更新的 `event_type` 取值不属于第 5 条的取值集合，或 `exp_amount` 为负 THEN THE 数据库 SHALL 以约束违例错误拒绝该写入语句，且 SHALL 保持 `growth_events` 表的行数与全部列取值不变。
8. IF 向 `growth_events` 写入的 `(user_id, event_key)` 组合已存在 THEN THE 数据库 SHALL 以唯一约束违例拒绝该写入语句，且 SHALL 保持 `growth_events` 表的行数与全部列取值为该语句执行前的状态、SHALL 不产生部分写入。
9. THE `user_growth` 表与 `growth_events` 表 SHALL 不含任何指向 `users(id)` 的外键（注销时由服务层显式删除两表中该用户的行，见需求 12），以避免为注销路径追加外键顺序约束。
10. THE `user_growth` 表与 `growth_events` 表 SHALL 使用 InnoDB 引擎、`utf8mb4` 字符集与 `utf8mb4_unicode_ci` 排序规则，且全部列带中文列注释、表本身带中文表注释（对齐 `V27__loan_repayments.sql` 与 `V31__user_invite.sql` 的既有写法）。
11. THE 迁移脚本 SHALL 命名为 `V32__user_growth.sql`（撰写本文档时 `src/main/resources/db/migration` 的最大版本号为 31，`V30` 由 user-feedback-system spec 预占），且 SHALL 不修改、不重命名任何已存在的历史迁移文件。
12. IF 实现本 spec 时 `V32` 已被其它迁移文件或其它 spec 占用 THEN THE 迁移脚本 SHALL 改用大于目录内全部已存在版本号且未被占用的最小版本号，且 SHALL 不与任何已存在迁移文件的版本号相同、SHALL 不修改其它 spec 的迁移文件内容。
13. THE 迁移脚本 SHALL 不修改 `transactions`、`budgets`、`ledgers`、`users` 与 `invite_relations` 五张表的结构，且 SHALL 不回填任何用户的成长档案与成长事件；迁移执行完成后 `user_growth` 与 `growth_events` 两表的行数 SHALL 均为 0（存量用户的成长数据在其首次结算时惰性生成）。
14. WHEN 应用在已执行全部历史迁移的数据库上启动 THEN THE Flyway 迁移 SHALL 成功执行本 spec 新增的迁移脚本，并在 `flyway_schema_history` 中新增一条版本号等于该脚本版本号、状态为成功的记录，且该脚本执行耗时 SHALL 不超过 60 秒。
15. IF 本 spec 新增的迁移脚本执行失败 THEN THE 应用 SHALL 中止启动并输出表明迁移失败的错误，`flyway_schema_history` SHALL 不含该版本号的成功记录，且既有表的业务数据行数与列取值 SHALL 保持迁移前的状态。
16. WHEN 应用在已成功执行本 spec 迁移脚本的数据库上再次启动 THEN THE Flyway 迁移 SHALL 不重复执行该脚本，`flyway_schema_history` 中该版本号的记录数 SHALL 保持为 1，且两张新表的数据 SHALL 不被修改。
17. WHEN 应用以 Hibernate `ddl-auto=validate` 在迁移完成的数据库上启动 THEN THE 应用 SHALL 启动成功且 SHALL 不抛出针对 `user_growth` 表 10 个列与 `growth_events` 表 6 个列的 schema 校验异常。
18. THE 清库脚本 `deploy/reset-db.sql` SHALL 在 `TRUNCATE TABLE users` 之前清空 `growth_events` 与 `user_growth` 两表（因两表不含外键，该清空 SHALL 不依赖 `FOREIGN_KEY_CHECKS` 的取值）；脚本执行后这两表行数 SHALL 均为 0，两表结构与 `flyway_schema_history` 的全部记录 SHALL 保留（脚本 SHALL 不含针对 `flyway_schema_history` 的删除或清空语句）。
19. IF 目标 MySQL 版本拒绝第 5 条表达式内的 `COLLATE utf8mb4_bin` 写法 THEN THE 迁移脚本 SHALL 改为把 `event_type` 列声明为 `VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL`（列级覆盖）并把该约束表达式写作不含 `COLLATE` 的 `event_type IN (...)`，SHALL 保持约束名 `ck_growth_events_type` 与第 5 条的六个取值集合不变、SHALL 保持该表默认排序规则为 `utf8mb4_unicode_ci`、SHALL 使第 7 条的大小写拒绝行为仍然成立，且 SHALL 在设计文档的迁移脚本小节补记该偏差与实测所用的 MySQL 版本号。
20. WHEN 迁移前已存在的某存量用户在迁移完成后首次触发结算 THEN THE 成长体系 SHALL 使 `user_growth` 中该用户 id 的行数由 0 变为 1、SHALL 使该行的 `created_at` 晚于或等于本 spec 迁移脚本在 `flyway_schema_history` 中记录的安装时刻，且 SHALL 保持其余每个尚未触发结算的存量用户在两表中的行数仍为 0（成长数据按用户逐个惰性生成，不存在批量回填）。
21. THE 成长体系 SHALL 使 `growth_events.user_id` 与 `user_growth.user_id` 在任一时刻均不出现 `users.id` 中不存在的取值；THE 成长体系 SHALL 以「两表分别按 `user_id` 反查 `users.id` 不存在的行数」作为对账口径，且 SHALL 使该两个行数在任一次注销事务提交之后均为 0（与 `invite_relations` 刻意允许悬空 id 以留痕的语义相反：成长数据由需求 12 在注销事务内显式删除，故正常运行下不应出现悬空 `user_id`）。

### 需求 12：账号注销与成长数据

**用户故事：** 作为用户，我注销之后成长数据应该一起干净地消失，重新注册就是从 Lv1 开始的新账号。

#### 验收标准

1. WHEN 用户注销账号 THEN THE 成长体系 SHALL 在同一注销事务内、且在删除该用户的 `users` 行之前，按「先 `growth_events`、再 `user_growth`」的顺序硬删除 `growth_events` 中 `user_id` 等于该用户 id 的全部行与 `user_growth` 中该用户的行（两表互不引用且均无外键，见需求 11 第 9 条，故该顺序对结果无影响；固定顺序只为使删除步骤可逐语句断言）。
2. WHEN 注销事务提交 THEN THE 成长体系 SHALL 使 `growth_events` 中 `user_id` 等于该用户 id 的行数为 0、`user_growth` 中该用户的行数为 0，且 SHALL 不写入任何软删除或归档副本（成长数据是用户私有资产，与 `invite_relations` 的历史留痕语义不同）。
3. WHEN 已注销用户以同一邮箱或同一微信身份重新注册 THEN THE 成长体系 SHALL 使该新用户的等级为 1、经验值为 0、累计记账天数为 0、当前连续天数为 0、历史最长连续天数为 0，且 SHALL 使 9 枚徽章均为未点亮。
4. IF 注销过程中成长数据的删除失败 THEN THE 成长体系 SHALL 回滚整个注销事务并返回表明注销失败的错误；回滚后该用户 `users` 行的 `id`、`email`、`wx_openid`、`nickname` 与 `invite_code` SHALL 与注销前相同，其成长档案的全部列取值与其全部成长事件的行数与列取值 SHALL 与注销前相同，且该用户在注销前持有的有效令牌 SHALL 仍可成功请求成长概览接口。
5. IF 注销的前置校验未通过（`AccountDeletionService.requireDeletable` 抛出 `DELETE_BLOCKED_COLLAB`，或 `AccountDeletionService.verifySecondFactor` 的二次验证未通过）THEN THE 成长体系 SHALL 不对 `growth_events` 与 `user_growth` 两表执行任何删除或更新语句，且 SHALL 使两表全部行的列取值保持请求前的状态。
6. WHEN 用户注销账号 THEN THE 成长体系 SHALL 不修改其它任何用户的成长档案与成长事件（成长数据无跨用户引用）。
7. WHEN 某用户注销 AND 该用户曾邀请他人 THEN THE 成长体系 SHALL 不修改 `invite_relations` 表的任何行（该表的注销联动仍完全由 invite-system 的既有逻辑负责）。
8. THE 成长体系 SHALL 把注销路径新增的成长数据删除步骤置于 `AccountDeletionService.deleteAccount` 现有删除序列中「第 12 步：`invite_relations` 置 `INVALID`」之后、「第 13 步：删除 `users` 行」之前，且 SHALL 不改变该方法中既有各步骤之间的相对顺序、过滤条件与影响行数。
9. THE 成长体系 SHALL 使单次注销事务内从 `growth_events` 删除的行数不超过「该用户的累计记账天数 + 其 `BUDGET_MET` 事件条数 + 13」（13 为 `FIRST_RECORD`、两条 `STREAK`、`FIRST_INVITE` 与 9 枚 `BADGE` 的上界；`BUDGET_MET` 条数随账号存续月数增长，不受单次结算 3 个月回看窗口的限制，故按终身条数表述）、从 `user_growth` 删除的行数不超过 1；WHEN 待删除的 `growth_events` 行数不超过 5000 THEN THE 成长体系 SHALL 使这两步删除的服务端耗时合计不超过 1000 毫秒，且 SHALL 使注销接口的端到端服务端耗时不超过 5000 毫秒。
10. IF 成长数据删除的耗时超过第 9 条的阈值 THEN THE 成长体系 SHALL 记录一条含用户 id 与实际耗时的告警日志，且 SHALL 不中止注销事务、SHALL 不改变注销接口的响应字段集与状态码。
11. THE 成长体系 SHALL 使这两条删除语句在该用户两表均无行时安全执行（影响行数为 0 即视为成功），且 SHALL 不在删除前执行任何存在性预查询。

### 需求 13：miniapp 成长页

**用户故事：** 作为用户，我希望在「我的」页面点一下就能看到我的等级、我记了多少笔、坚持了多少天，以及我攒到的徽章。

#### 验收标准

1. WHILE 用户处于已登录状态 THE miniapp SHALL 在「我的」页面展示进入成长页的入口；WHEN 用户点击该入口 THEN THE miniapp SHALL 打开成长页 `pages/growth/growth`。
2. WHERE 成长概览已在「我的」页面加载成功 THE miniapp SHALL 在该入口上展示当前等级文案；IF 该加载失败 THEN THE miniapp SHALL 只展示入口标题与箭头、SHALL 不展示等级文案，且 SHALL 不弹出错误提示、SHALL 不影响「我的」页面其余部分的展示（对齐邀请入口的既有降级写法）。
3. WHEN 成长页加载完成 AND 成长概览请求返回成功 THEN THE miniapp SHALL 在同一屏内展示且仅展示当前等级、经验值、升级进度、累计记账笔数、累计支出金额、累计记账天数与当前连续天数七项统计，各项取值 SHALL 等于成长概览响应中对应字段的取值。
4. THE miniapp SHALL 按下述对应关系消费成长概览响应的 15 项字段：当前等级、经验值、累计记账笔数、累计支出金额、累计记账天数、当前连续天数六项与升级进度共七项对用户可见；当前等级起始经验、下一等级所需经验、本级内已获得经验、升级还需经验、最高等级、是否满级六项仅参与升级进度渲染与满级判定、不单独成项展示；累计收入金额与历史最长连续天数两项本期 SHALL 不在成长页展示。
5. WHERE 用户未达满级 WHEN miniapp 渲染升级进度 THEN THE miniapp SHALL 以「本级内已获得经验」除以「下一等级所需经验减去当前等级起始经验」的比例渲染进度条、SHALL 使该比例落在 0 到 1 的闭区间内，并 SHALL 展示升级还需经验的数值。
6. WHERE 用户处于满级 THE miniapp SHALL 展示满级文案、SHALL 把进度条渲染为满格（比例取 1）、SHALL 不执行第 5 条的比例计算（该状态下「下一等级所需经验」为空值、分母不成立），且 SHALL 不展示升级还需经验的数值、SHALL 不展示任何非数值文本或负数作为进度取值。
7. WHEN 成长页加载完成 THEN THE miniapp SHALL 展示全部 9 枚徽章；WHERE 某枚徽章已点亮 THE miniapp SHALL 以品牌绿图标呈现该枚并展示其解锁时刻、SHALL 不展示其进度文案；WHERE 某枚徽章未点亮 THE miniapp SHALL 以灰度图标呈现该枚并展示「当前值 / 目标值」的进度文案、SHALL 不展示解锁时刻；由此仅凭页面可见文案即可判定任一徽章的点亮状态。
8. IF 成长概览请求返回错误标识，或客户端等待响应超过 10000 毫秒未收到响应 THEN THE miniapp SHALL 展示加载失败的提示文案与重试操作，且 SHALL 不展示任何等级、经验、累计统计与徽章的占位假数据。
9. THE miniapp SHALL 在成长页提供进入**经验明细页** `pages/growthlog/growthlog` 的入口；WHEN 用户点击该入口 THEN THE miniapp SHALL 打开该独立页面；THE miniapp SHALL 不在成长页内展示任何经验明细列表项（经验明细是独立页面而非成长页内的区域）。
10. WHEN 用户进入经验明细页 THEN THE miniapp SHALL 展示首屏至多 20 条经验记录、SHALL 在每次上拉加载时追加至多 20 条、SHALL 在已加载条数等于成长事件总条数后停止发起后续列表请求，并 SHALL 为每条记录展示与其事件类型一一对应的中文文案与经验值。
11. WHEN 当前用户没有任何成长事件 THEN THE miniapp SHALL 在经验明细页展示空状态提示与记账引导文案，且 SHALL 不展示列表区域。
12. IF 经验明细请求返回错误标识，或客户端等待响应超过 10000 毫秒未收到响应 THEN THE miniapp SHALL 在经验明细页展示列表加载失败的提示文案与重试操作，并 SHALL 保留已加载的经验记录不变、SHALL 不影响成长页已展示的等级、经验与徽章。
13. THE miniapp SHALL 在成长相关的全部请求中不发送 `X-Ledger-Id` 头（成长数据与账本无关），并 SHALL 把这些请求收敛到新增的 `miniapp/src/api/growth.js` 一个模块内（对齐 `api/invite.js` 的既有写法）。
14. THE miniapp SHALL 在成长页与经验明细页沿用既有品牌绿 `#12a150` 作为等级、进度条与已点亮徽章的强调色，且 SHALL 不引入新的主色。
15. THE miniapp SHALL 在 `pages.json` 中注册 `pages/growth/growth` 与 `pages/growthlog/growthlog` 两个页面，并 SHALL 不把其中任一页加入 tabBar（成长页由「我的」页面进入，经验明细页由成长页进入）。
16. WHEN 用户在成长页触发下拉刷新 AND 距上一次成长概览请求发出已满 3000 毫秒 THEN THE miniapp SHALL 重新请求成长概览、SHALL 以最新响应更新页面全部展示项，并 SHALL 在收到响应后或 10000 毫秒超时后结束下拉动效。
17. IF 用户在成长页触发下拉刷新 AND 距上一次成长概览请求发出不足 3000 毫秒 THEN THE miniapp SHALL 不发起成长概览请求、SHALL 在 1000 毫秒内结束下拉动效，且 SHALL 保留页面已展示的全部取值不变（客户端节流，避免用户反复下拉造成服务端结算风暴，与需求 10 第 14 条的服务端结算节流呼应）。
