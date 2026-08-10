# Design Document

## Overview

周期记账（Recurring_Transaction_System）在既有「账本 / 账户 / 交易」体系上新增两类独立数据：**周期规则
（recurring_rules）** 与 **待确认生成项（recurring_pending_items）**，并把「到期 → 生成待确认项 → 用户确认才落库」
的链路挂在既有交易创建之上。核心哲学与 AA 结算建议、record-suggestion 完全一致：**派生优先、用户确认才动余额**——
规则到期系统只生成一条 `PENDING` 建议，绝不静默入账。

设计的三条主线：

- **懒生成为事实源（关键决策）。** 期次由频率配置在时间轴上确定，是一个**纯函数**；系统不依赖后台定时任务
  作为唯一事实源，而是在用户打开相关视图 / 查询待确认项时，按规则**惰性计算**出「已到期且表中尚无任何状态记录」
  的期次并补齐待确认项。可选的定时任务仅作**提前生成 + 触发提醒**的兜底，其正确性不影响事实。
- **确认复用既有记账链路。** 确认待确认项时走既有 `TransactionService.create` 的账户加锁
  （`findForUpdateByIdAndUserId` 同款）与单事务原子写法；在同一事务内完成「建交易 + 更新余额 + 待确认项置
  `CONFIRMED`」。修改后确认对改后的金额 / 分类 / 账户重跑与需求 1 一致的校验。
- **纯增量、账本隔离、可整块摘除。** 以 Flyway `V38`（接续 `V37__aa_ledger.sql`）新建两张独立表；不对既有表加列 /
  加约束 / 建外键；删除 `V38` 两表并下线接口即可整块摘除，其余功能原样成立。金额一律 `BigDecimal(18,2)`、HALF_UP。

一句话边界：**周期规则负责「按规则算出应记的账 + 生成待确认项」，落库仍走既有交易创建链路。**

## Architecture

```
miniapp（周期规则列表/新建/编辑、待确认项列表(批量确认/跳过)、首页「待确认」入口提示）
  │  REST + Authorization(JWT) + X-Ledger-Id
  ▼
RecurringRuleController          RecurringPendingItemController
  （规则 CRUD + 暂停/恢复）        （查询/确认/修改后确认/跳过/批量）
  ▼
RecurringRuleService            RecurringPendingItemService        RecurringReminderNotifier
 （规则校验/生命周期）           （懒生成/确认入账/跳过/批量）        （提醒衔接，事务边界外）
        │                                │  确认入账复用                     │  复用
        │                                ▼                                   ▼
        │                        TransactionService.create           既有微信一次性订阅消息链路
        │                                │ （账户加锁 + 单事务原子）        （WeChatClient / 额度）
        ▼                                ▼
  OccurrenceCalculator（纯函数：期次计算，Asia/Shanghai 自然日口径）
  ▼
Repositories：RecurringRuleRepository / RecurringPendingItemRepository
            + 复用 Transaction / Account / Category / Ledger
  ▼
MySQL（Flyway V38 新增 recurring_rules / recurring_pending_items 两表）
```

复用既有：`CurrentUser` / `CurrentLedger`（`X-Ledger-Id` 解析 + 默认账本兜底，越权 `NOT_FOUND`）、
`TransactionService` 的账户加锁与余额更新、`ApiException` + `GlobalExceptionHandler` 统一错误体、
`Clock`（`Clock.system(ZoneId.of("Asia/Shanghai"))`，见 `TimeConfig`）、`WeChatClient` 订阅消息链路。

分层职责与 AA 特性一致：Controller 只做请求 / 响应装配与 `CurrentUser` / `CurrentLedger` 解析；全部校验、
归属判定与事务下沉到 Service；期次计算是无副作用的纯组件 `OccurrenceCalculator`，便于属性测试独立覆盖。

## Data Models

Flyway 增量迁移脚本 `V38__recurring_transactions.sql`（接续当前最大版本 `V37`，不修改 / 不复用任何既有版本号，
不对既有表加列 / 加约束 / 建外键；需求 9.1、9.2）。金额列 `DECIMAL(18,2)` 与既有交易口径一致。

### recurring_rules（周期规则表）

```sql
CREATE TABLE recurring_rules (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,                 -- 规则所有者
    ledger_id       BIGINT NOT NULL,                 -- 归属账本（账本隔离）
    type            VARCHAR(16) NOT NULL,            -- 'expense' | 'income'（不含 transfer）
    amount          DECIMAL(18,2) NOT NULL,          -- 模板金额，0.01–999999999.99
    category_id     BIGINT NOT NULL,                 -- 模板分类（须属当前账本）
    account_id      BIGINT NOT NULL,                 -- 模板账户（须为当前用户在当前账本可用账户）
    note            VARCHAR(200) NULL,               -- 模板备注，≤200

    frequency       VARCHAR(16) NOT NULL,            -- 'DAILY'|'WEEKLY'|'MONTHLY'|'YEARLY'
    weekly_days     VARCHAR(16) NULL,                -- WEEKLY：星期几集合，如 '1,3,5'（1=周一..7=周日）
    month_day       INT NULL,                        -- MONTHLY：指定日 1–31（month_end=0 时必填）
    month_end       TINYINT NOT NULL DEFAULT 0,      -- MONTHLY：1=「月末」标记（此时忽略 month_day）
    year_month      INT NULL,                        -- YEARLY：月 1–12
    year_day        INT NULL,                        -- YEARLY：日 1–31

    start_date      DATE NOT NULL,                   -- 开始日期（Asia/Shanghai 自然日）
    end_condition   VARCHAR(16) NOT NULL,            -- 'NEVER'|'UNTIL_DATE'|'COUNT'
    until_date      DATE NULL,                       -- UNTIL_DATE：结束日期（不早于 start_date）
    count_n         INT NULL,                        -- COUNT：总期次数 1–9999

    status          VARCHAR(16) NOT NULL,            -- 'ACTIVE' | 'PAUSED'
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recurring_rules_ledger_status (ledger_id, status),
    KEY idx_recurring_rules_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

> 说明：频率子字段以「按 `frequency` 取值必填其一组」的形式存储（应用层校验，见需求 1.8 / 2.10），
> 不建 CHECK 强约束以保持迁移简单可摘除；`weekly_days` 以稳定升序的逗号分隔串存储，规范化后写入。
> 不对 `category_id` / `account_id` / `ledger_id` / `user_id` 建数据库外键（需求 9.2）——归属与存在性
> 在应用层校验，删除本表不牵动既有表。

### recurring_pending_items（待确认生成项表）

```sql
CREATE TABLE recurring_pending_items (
    id                       BIGINT NOT NULL AUTO_INCREMENT,
    rule_id                  BIGINT NOT NULL,        -- 来源规则
    ledger_id                BIGINT NOT NULL,        -- 冗余账本 id，便于账本隔离查询（避免回表规则）
    occurrence_date          DATE NOT NULL,          -- 期次到期自然日（Asia/Shanghai）
    status                   VARCHAR(16) NOT NULL,   -- 'PENDING'|'CONFIRMED'|'SKIPPED'

    -- 生成时快照的模板字段（确认入账的初始值；规则后续被编辑不影响已生成项，需求 6.4）
    type                     VARCHAR(16) NOT NULL,
    amount                   DECIMAL(18,2) NOT NULL,
    category_id              BIGINT NOT NULL,
    account_id               BIGINT NOT NULL,
    note                     VARCHAR(200) NULL,

    confirmed_transaction_id BIGINT NULL,            -- 确认后指向真实流水（transactions.id）
    created_at               TIMESTAMP NOT NULL,
    updated_at               TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_recurring_pending_rule_date UNIQUE (rule_id, occurrence_date),  -- 构造性幂等（需求 3.3、9.3）
    KEY idx_recurring_pending_ledger_status_date (ledger_id, status, occurrence_date),
    KEY idx_recurring_pending_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

> **构造性幂等**：`uk_recurring_pending_rule_date` 对 `(rule_id, occurrence_date)` 施加唯一约束，
> 保证同一规则同一期次到期日至多一条记录（无论 `PENDING`/`CONFIRMED`/`SKIPPED`）。重复 / 并发生成
> 撞唯一键时，服务层捕获 `DataIntegrityViolationException` 并静默视为「该期次已生成」，既不新增第二条、
> 也不向查询等主路径返回错误（需求 3.4、9.4）——与 `ReminderDispatchService` 处理发送记录唯一键冲突同款。

> **快照字段**：待确认项持有生成时刻的模板快照，因此：①编辑规则只对之后新生成的项生效，既有 `PENDING`
> 项保留原值（需求 6.3、6.4）；②确认 / 修改后确认读取的是本项快照（或用户改后的值），不回读规则。

## 期次计算算法（OccurrenceCalculator，纯函数）

期次计算是本特性的核心纯算法，全部按 `Asia/Shanghai`（UTC+08:00）自然日口径，无副作用、可被属性测试完整覆盖。
核心方法签名（概念）：

```
List<LocalDate> occurrencesUpTo(RuleSpec rule, LocalDate today)
    // 返回该规则「到期日 ≤ today 且满足开始/结束条件」的全部期次到期日，按到期日升序、去重。
boolean isDue(RuleSpec rule, LocalDate occurrenceDate, LocalDate today)  // occurrenceDate ≤ today 且属于该规则期次序列
```

其中 `today` 取 `LocalDate.now(clock)`（clock 为 Asia/Shanghai）。

### 各频率的期次序列

- **DAILY**：自 `start_date`（含）起每个连续自然日各一期，相邻期次相差 1 日（需求 2.1）。
- **WEEKLY**：`weekly_days` 为 1–7（周一至周日）的非空集合；自 `start_date`（含）起，星期几落在集合内的每个
  自然日各一期；`start_date` 当天星期几在集合内则为首期（需求 2.2）。集合为空或含 1–7 之外取值 → 频率非法
  （需求 2.10）。
- **MONTHLY（指定日 D，1–31）**：自 `start_date` 所在自然月（含）起，每月第 D 日一期；`start_date` 所在月
  仅当第 D 日不早于 `start_date` 时才生成该月期次（需求 2.3）。**月末落点**：若某月不存在第 D 日
  （如 D=31 落 4 月、D=29/30 落平年 2 月），落该月最后一日（4 月 → 30，平年 2 月 → 28，闰年 2 月 → 29；需求 2.4）。
- **MONTHLY（月末标记 month_end=1）**：每月按 `Asia/Shanghai` 口径的实际最后一日为到期日（平年 2 月 28、
  闰年 2 月 29、4/6/9/11 月 30、其余 31；需求 2.5）。
- **YEARLY（year_month + year_day）**：每年该月该日一期；若目标日在该年该月不存在（如 2/29 遇平年）落该月
  最后一日，存在（2/29 遇闰年）则落目标日（需求 2.6）。

月末落点统一实现：目标日 `min(D, lengthOfMonth(year, month))`，`month_end` 等价于 `D=31` 的特例
（恒取当月最后一日）；借助 `java.time.YearMonth.lengthOfMonth()` 天然处理平 / 闰年，不手写闰年判断。

### 开始 / 结束边界（对全部频率统一施加）

- **开始日期**：不生成到期日早于 `start_date` 的期次（需求 2.11、3.6）；未指定 `start_date` 时以创建当日
  （`Asia/Shanghai`）为开始日期（需求 1.5）。
- **UNTIL_DATE**：生成到期日 ≤ `until_date` 的期次（等于结束日期仍生成），不生成晚于结束日期的期次
  （需求 2.8）；创建时要求 `until_date ≥ start_date`（需求 1.6）。
- **COUNT**：按到期日升序对该规则期次计数，累计达 `count_n` 后不再生成新期次；计数**不区分**待确认项状态
  （`PENDING`/`CONFIRMED`/`SKIPPED` 均计入，需求 2.9）；`count_n` 须为 1–9999（需求 1.7）。

### 「已到期」判定与暂停 / 恢复

- 某期次「已到期」当且仅当当前 `Asia/Shanghai` 时刻不早于其到期自然日 00:00:00，即 `occurrenceDate ≤ today`
  （`today = LocalDate.now(clock)`；需求 2.7、3.1）。
- 恢复（PAUSED→ACTIVE）后仅为到期日 ≥ 恢复当日的期次生成待确认项，**不补生成**暂停区间内的期次（需求 6.2）。
  实现上恢复时记录 `updated_at`；懒生成对 ACTIVE 规则以 `max(start_date, 恢复当日)`（若发生过暂停）为
  下界扫描——为保持 MVP 简单且可摘除，恢复动作对已到期未生成期次的「不回补」通过懒生成的**生成下界**推进实现
  （见下「懒生成算法」）。

### 懒生成算法（事实源）

当用户打开周期记账视图或查询待确认项时，对当前账本下每条 `ACTIVE` 规则执行：

```
today = LocalDate.now(clock)
for rule in activeRulesOf(currentLedger):
    try:
        for d in calculator.occurrencesUpTo(rule, today):        // 已到期期次（升序）
            if d < generationLowerBound(rule): continue           // 恢复/暂停不回补（需求 6.2）
            if pendingRepo.existsByRuleIdAndOccurrenceDate(rule.id, d): continue  // 已有任一状态记录则跳过
            try:
                pendingRepo.save(snapshotOf(rule, d))            // status=PENDING，写入模板快照
            catch DataIntegrityViolationException:                // 并发/重复：唯一键构造性幂等
                ignore                                            // 视为已生成，不报错（需求 3.4、9.4）
    catch Exception e:
        log.warn("[RECURRING_GEN_FAILED] ruleId={}", rule.id, e)  // 就地隔离，不阻断其余规则与已有项返回（需求 3.8）
```

- **不阻断**：单条规则补齐失败仅记日志，不影响同账本其余规则的补齐，也不影响已有待确认项的返回（需求 3.8）。
- **暂停不生成**：`PAUSED` 规则不进入扫描集合（需求 3.5、6.1）。
- 生成时**不创建任何交易、不改任何账户余额**（需求 3.2）。

## Components and Interfaces

服务组件：`RecurringRuleService`（规则创建 / 编辑 / 暂停 / 恢复 / 删除 + 校验）、`RecurringPendingItemService`
（懒生成 / 查询 / 确认 / 修改后确认 / 跳过 / 批量）、`OccurrenceCalculator`（纯函数期次计算）、
`RecurringReminderNotifier`（提醒衔接，事务边界外）。控制器按下方划分，均由 `CurrentLedger` 依 `X-Ledger-Id`
隔离、`CurrentUser` 解析身份；越权 / 不存在返回 `NOT_FOUND`（需求 8）；响应金额一律字符串化 `BigDecimal(2dp)`，
与既有 `TransactionResponse` 风格一致。

### 规则接口（RecurringRuleController，`/api/recurring/rules`）

- `POST   /api/recurring/rules` — 创建规则；校验模板字段（需求 1.2–1.4）、频率配置（1.8 / 2.10）、开始日期
  （1.5）、结束条件（1.6 / 1.7）；成功 201 返回规则（含 id、初始 `ACTIVE`）。
- `GET    /api/recurring/rules` — 列出当前账本当前用户的规则（含 `ACTIVE`/`PAUSED`）。
- `GET    /api/recurring/rules/{id}` — 规则详情；越权 `NOT_FOUND`。
- `PUT    /api/recurring/rules/{id}` — 编辑频率配置 / 模板字段；仅对编辑后新生成项生效，既有 `PENDING` 保留
  快照（需求 6.3、6.4）。
- `DELETE /api/recurring/rules/{id}` — 删除规则；级联移除其全部 `PENDING` 待确认项，保留 `CONFIRMED` 历史流水
  与 `SKIPPED` 记录（需求 6.5、6.6）。
- `POST   /api/recurring/rules/{id}/pause` — 暂停（ACTIVE→PAUSED）；既有 `PENDING` 保持不变（需求 6.1）。
- `POST   /api/recurring/rules/{id}/resume` — 恢复（PAUSED→ACTIVE）；仅生成恢复当日及之后期次（需求 6.2）。

### 待确认项接口（RecurringPendingItemController，`/api/recurring/pending-items`）

- `GET    /api/recurring/pending-items` — **先触发懒生成**（需求 3.7），再返回当前账本 `PENDING` 列表；
  每项含来源规则 id、`occurrenceDate` 与模板快照字段；排序按 `occurrence_date` 升序 → 规则 `created_at` 升序
  → 待确认项 id 升序，保证可复现（需求 5.1、5.2、5.3）。无 `PENDING` 返回空列表不报错。
- `POST   /api/recurring/pending-items/{id}/confirm` — 确认入账；可携带修改后的字段（`amount`/`categoryId`/
  `accountId`/`note`/`occurredAt`）实现「修改后确认」；走既有交易创建链路，单事务原子（需求 4.1–4.3、4.8）。
- `POST   /api/recurring/pending-items/{id}/skip` — 跳过本期（PENDING→SKIPPED），不生成流水、不改余额（需求 4.4）。
- `POST   /api/recurring/pending-items/batch-confirm` — 批量确认；入参 `{ ids:[...] }`，逐条**各自独立事务**
  处理，逐条返回结果与成功 / 失败计数，部分失败可逐条判定（需求 5.4、5.6）。
- `POST   /api/recurring/pending-items/batch-skip` — 批量跳过；仅将其中 `PENDING` 置 `SKIPPED`，已处理条目
  在结果中标记失败而不影响其余（需求 5.5、5.6）。

确认 / 跳过对已处于 `CONFIRMED`/`SKIPPED` 的项再次操作 → `RECURRING_ITEM_ALREADY_PROCESSED`（需求 4.5、4.9）；
确认时快照分类 / 账户在当前账本已不存在 → `RECURRING_ITEM_TARGET_MISSING`，保持 `PENDING`（需求 4.6）。

## 事务边界（原子性）

- **懒生成**：每条期次的 `PENDING` 写入是一次 `save`；唯一键冲突捕获后静默。生成不涉及账户 / 交易，故不与
  余额事务耦合。批量补齐**逐条独立**，单条失败不回滚其余（需求 3.8）。
- **确认入账（单条）**：单事务内 = ①按（改后或快照的）type/amount/account/category 走 `TransactionService.create`
  （内部 `accountResolver.lockUsableAccount` 加锁 + 余额更新）→ ②回填 `confirmed_transaction_id` 并将待确认项
  置 `CONFIRMED`。任一步失败整体回滚，待确认项保持 `PENDING`、不生成流水、不改余额（需求 4.1、4.2、4.8）。
  并发 / 重复确认：置 `CONFIRMED` 时对 `status=PENDING` 做条件更新（乐观），仅一条成功，其余得
  `RECURRING_ITEM_ALREADY_PROCESSED`，至多生成一条流水、至多更新一次余额（需求 4.9）。
- **批量确认 / 跳过**：外层不开大事务，**逐条**在各自事务内按单条口径处理，某条失败仅回滚该条并继续其余
  （需求 5.4、5.5）。
- **提醒发送**：在上述所有主路径的事务边界**之外**执行（`afterCommit` 或调度线程），失败只记日志（见下）。

## 提醒衔接（RecurringReminderNotifier）

复用 custom-reminder 的微信一次性订阅消息链路（`WeChatClient.sendSubscribeMessage` + 订阅额度）。

- 当某规则存在到期且未处理的 `PENDING` 项且所有者持有有效订阅额度时，向所有者发送一条「存在待确认周期记账」
  提醒（需求 7.1）。
- **同用户同账本同自然日至多一条**（需求 7.5）：以 `(user_id, ledger_id, natural_date)` 为去重键做发送前预检，
  与 `ReminderDispatchService` 的 `(reminder_id, trigger_date)` 幂等预检同款思路。
- 发送在主路径**事务边界之外**执行，任何投递失败 / 微信异常 / 超 5 秒未返回 / 额度耗尽都就地捕获、仅记告警日志，
  绝不向生成 / 查询 / 确认 / 跳过 / 登录抛错（需求 7.2、7.6）。发送成败不改任何待确认项状态、流水与余额（需求 7.3）。
- 未授予有效额度则不发送，但懒生成与呈现照常（需求 7.4）。

## 前端（miniapp，uni-app / Vue 3）

新增页面（沿用既有目录命名风格，如 `aahome`/`reminder`）：

- `pages/recurring/`（规则列表）：列出 `ACTIVE`/`PAUSED` 规则，展示频率摘要（如「每月 5 日 · 支出 ¥3000」）、
  暂停 / 恢复 / 编辑 / 删除入口。
- `pages/recurringedit/`（新建 / 编辑）：类型（支出 / 收入）、金额、分类、账户（复用 `AccountBadge`）、备注、
  频率选择（每天 / 每周星期几多选 / 每月指定日或月末 / 每年月+日）、开始日期、结束条件（永不 / 到某日 / 共 N 次）。
- `pages/recurringpending/`（待确认项列表）：按到期日升序分组呈现，支持单条确认 / 修改后确认 / 跳过，
  与多选批量确认 / 批量跳过；批量结果按逐条成功 / 失败反馈。
- 首页 / 记账相关位置的「待确认」入口提示：展示当前账本 `PENDING` 待确认项数量角标，点击进入待确认列表。

新增 API 封装 `miniapp/src/api/recurring.js`，与既有 `aa.js`/`reminder.js` 风格一致，统一带 `X-Ledger-Id`。
金额展示用 `tabular-nums`，复用既有分类图标与 `AccountBadge`。

<!-- 期次计算是纯函数、生成幂等、确认账户守恒等均为可「for all 输入」表达的通用性质，PBT 适用。
     以下在写 Correctness Properties 前先做 prework 分析。 -->

## Correctness Properties

*属性（property）是应在系统所有合法执行下都成立的特征或行为——一个关于「系统应当做什么」的形式化陈述。
属性是人类可读规格与机器可验证正确性保证之间的桥梁。*

下列属性由需求验收标准经 prework 分析、消冗归并后得到，均可「for all 输入」表达，适合以属性测试（jqwik）覆盖。

### Property 1: 期次计算确定性与月末 / 年边界

*对任意*频率配置（DAILY / WEEKLY 星期几集合 / MONTHLY 指定日或月末 / YEARLY 月+日）、任意开始日期与任意
「今天」，`OccurrenceCalculator` 产出的期次序列满足：按到期日升序且无重复；每个 MONTHLY / YEARLY 期次的到期日
等于 `min(指定日, 该月自然天数)`（月末标记等价指定日=31，平年 2 月落 28、闰年落 29）；每个 WEEKLY 期次的
星期几都落在集合内且集合内应到日期无遗漏；DAILY 相邻期次恰差 1 个自然日；且**所有**期次到期日均不早于开始日期。

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.11, 3.6**

### Property 2: 已到期判定与结束条件边界

*对任意*规则与任意「今天」，某期次被判定为「已到期」当且仅当其到期自然日 ≤ 今天（`Asia/Shanghai` 口径）；
且当结束条件为 `UNTIL_DATE` 时全部生成期次的到期日 ≤ 结束日期、无一晚于结束日期；当结束条件为 `COUNT` 时
按到期日升序生成的期次总数不超过 N（计数不区分 `PENDING`/`CONFIRMED`/`SKIPPED`）。

**Validates: Requirements 2.7, 2.8, 2.9, 3.6**

### Property 3: 模板字段与频率配置校验（零副作用）

*对任意*违反模板字段约束（类型非 expense/income、金额越界或小数位超 2、分类不属当前账本、账户不可用、备注超 200）
或违反频率 / 结束条件约束（频率枚举外、WEEKLY 集合为空或含 1–7 之外值、MONTHLY 缺日、YEARLY 缺月日、
UNTIL_DATE 早于开始日期、COUNT 的 N 不在 1–9999）的创建请求，系统都拒绝创建并返回指示对应无效字段的错误，
且规则表零新增、任何数据不变；反之任意满足全部约束的请求都成功创建且初始状态为 `ACTIVE`。

**Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.6, 1.7, 1.8, 2.10**

### Property 4: 生成幂等（构造性 Σ）

*对任意*规则与任意次数、任意交错的懒生成，每个 `(rule_id, occurrence_date)` 组合在待确认生成项表中的记录数
至多为 1；重复 / 并发生成尝试不新增第二条、不改动既有记录、不向查询等主路径抛出异常或返回错误。

**Validates: Requirements 3.3, 3.4, 9.3, 9.4**

### Property 5: 懒生成补齐且生成期不触账，快照不可变

*对任意*当前账本下的 `ACTIVE` 规则集合与任意「今天」，一次懒生成后：每个「到期日 ≤ 今天、≥ 生成下界且表中尚无
任何状态记录」的期次恰有一条 `PENDING`，其模板快照字段等于生成时规则的模板字段；生成过程不创建任何交易、
不改变任何账户余额；`PAUSED` 规则不产生任何新待确认项；随后编辑规则不改变已生成 `PENDING` 项的快照字段。

**Validates: Requirements 3.1, 3.2, 3.5, 3.7, 6.1, 6.3, 6.4**

### Property 6: 确认账户守恒且与手动记账口径一致

*对任意* `PENDING` 待确认项，确认（含修改后确认）后：恰生成一条真实流水、对应账户余额恰变动 `+amount`（收入）
或 `−amount`（支出）、该项状态置 `CONFIRMED`；修改后确认时流水字段取用户改后的值，而原规则模板字段与该项的
`occurrence_date` 及唯一约束键 `(rule_id, occurrence_date)` 保持不变；对相同 `(type, amount, account, category)`，
经确认入账与经手动记账对账户余额的影响与流水关键字段一致；全程金额以 `BigDecimal` 保留 2 位小数（HALF_UP）。

**Validates: Requirements 4.1, 4.3, 4.7, 9.7**

### Property 7: 跳过守恒

*对任意* `PENDING` 待确认项，跳过后其状态置为 `SKIPPED`，且不生成任何流水、不改变任何账户余额。

**Validates: Requirements 4.4**

### Property 8: 确认 / 跳过状态机幂等（含并发）

*对任意*待确认项与任意次数、任意交错的确认 / 跳过操作，至多生成一条流水、至多对账户余额更新一次；对已处于
`CONFIRMED` 或 `SKIPPED` 的项再次确认 / 跳过一律返回「该项已处理」错误且无任何副作用；改后值不满足需求 1 校验
的确认被拒且该项保持 `PENDING`、零副作用。

**Validates: Requirements 4.5, 4.8, 4.9**

### Property 9: 待确认项查询过滤、排序确定性与批量隔离

*对任意*混合状态、跨规则跨账本的待确认项数据，当前账本的查询结果恰为归属当前账本且状态为 `PENDING` 的项集合，
每项携带来源规则标识、到期日与模板字段；结果严格按「到期日升序 → 规则创建时间升序 → 项 id 升序」排列，任意
两次查询对相同数据返回完全一致的顺序；批量确认 / 跳过逐条独立处理，成功条改为目标状态、失败条（含已处理条与
处理异常条）保持原状态且不影响其余，返回的成功 / 失败计数等于逐条结果的聚合。

**Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6**

### Property 10: 生命周期历史不可变

*对任意*规则与任意暂停 / 恢复 / 编辑 / 删除操作序列，已 `CONFIRMED` 的历史流水取值、已发生的账户余额变动、
已 `SKIPPED` 的期次记录均保持不变、不被回滚；删除规则后其全部 `PENDING` 项从查询中消失，而其 `CONFIRMED`
流水与 `SKIPPED` 记录仍保留；恢复后仅为到期日 ≥ 恢复当日的期次生成待确认项，暂停区间内的期次不被补生成。

**Validates: Requirements 6.2, 6.5, 6.6**

### Property 11: 账本与用户隔离

*对任意*多用户、多账本的规则与待确认项数据，当前用户在当前账本的查询仅返回归属当前用户且归属当前账本的规则
与待确认项，绝不泄漏其它用户或其它账本的数据；以规则 / 待确认项标识对不属于当前用户或当前账本的对象执行确认 /
跳过 / 暂停 / 恢复 / 编辑 / 删除时一律返回 `NOT_FOUND`，且不改动任何数据、不生成任何流水、不改变任何账户余额。

**Validates: Requirements 6.7, 8.1, 8.4, 8.5**

### Property 12: 提醒隔离与同日去重

*对任意*提醒发送结果（成功 / 失败 / 超时 / 无额度）与任意次数的触发，待确认项状态、真实流水与账户余额均不因
提醒链路的任何结果而改变；且对同一 `(所有者, 账本, 自然日)`（`Asia/Shanghai`）至多实际发送一条「存在待确认
周期记账」的提醒。

**Validates: Requirements 7.3, 7.5**

## Error Handling

新增错误码，沿用 `ApiException` 统一错误体 `{code, message, field}` 与 `GlobalExceptionHandler` 映射；越权
一律复用既有 `notFound()`（对外 `NOT_FOUND`，不泄漏存在性），鉴权复用既有 `unauthenticated()`（`UNAUTHENTICATED`）。
金额 / 备注等既有校验尽量复用既有码（如 `AMOUNT_INVALID`、`NOTE_TOO_LONG`），仅在语义确为周期记账特有时新增码。

- `RECURRING_RULE_INVALID`（400）：模板字段非法（类型非 expense/income、分类不属当前账本、账户不可用等），
  携带具体 `field`（需求 1.2、1.4、8）。
- `RECURRING_FREQUENCY_INVALID`（400）：频率配置非法（枚举外、WEEKLY 集合空或越界、MONTHLY 缺日、
  YEARLY 缺月日），`field=frequency`（需求 1.8、2.10）。
- `RECURRING_END_CONDITION_INVALID`（400）：结束条件非法（`UNTIL_DATE` 早于开始日期、`COUNT` 的 N 不在
  1–9999），`field=endCondition`（需求 1.6、1.7）。
- `RECURRING_ITEM_ALREADY_PROCESSED`（409）：对已 `CONFIRMED`/`SKIPPED` 的待确认项再次确认 / 跳过，或并发
  确认的落败者（需求 4.5、4.9）。
- `RECURRING_ITEM_TARGET_MISSING`（409）：确认时快照分类或账户在当前账本已不存在，须重选（需求 4.6）。
- 金额越界 / 小数位超限复用 `AMOUNT_INVALID`；备注超长复用 `NOTE_TOO_LONG`（含创建与修改后确认，需求 1.3、
  1.4、4.8）。
- 越权（他人 / 他账本的规则或待确认项）复用 `NOT_FOUND`；未认证复用 `UNAUTHENTICATED`（需求 6.7、8.2、8.3、8.5）。

失败一律零副作用：校验前置于任何写操作与余额变更之前，拒绝即不落库、不生成流水、不改余额。生成幂等的唯一键
冲突（`DataIntegrityViolationException`）在服务层就地捕获，不外抛（需求 3.4、9.4）。提醒链路的任何异常在事务
边界外捕获并仅记 `[RECURRING_REMIND_FAILED]` 告警日志，不对外暴露错误码（需求 7.2、7.6）。

## Testing Strategy

**双轨测试**：属性测试覆盖通用性质，单元 / 集成测试覆盖具体示例、边界、错误与外部依赖。

**属性测试（jqwik，本仓库既有 PBT 框架，见 `.jqwik-database`）**：
- 实现上文 12 条 Correctness Properties，每条以**单个**属性测试实现，最少 **100** 次迭代（`@Property`）。
- 每个属性测试以注释标注对应设计属性，标签格式：
  `Feature: recurring-transactions, Property {number}: {property_text}`。
- 生成器覆盖：随机频率配置（各类型 + 星期几集合 + 指定日 1–31 / 月末 + 年月日）、随机开始 / 结束条件、
  随机「今天」（跨平 / 闰年、跨小月、跨年）、随机金额（含边界 0.01 / 999999999.99 与超小数位）、
  随机模板字段与非法字段、随机多用户多账本数据、随机混合状态待确认项、随机批量与并发确认序列。
- `OccurrenceCalculator` 为纯函数，属性测试直接对其运行（Property 1、2），无需数据库，快速高覆盖。
- 确认 / 生命周期 / 隔离等涉及持久化与余额的属性走 `@SpringBootTest` + H2（`MODE=MySQL`），复用既有测试
  基座与 `TransactionService` 真实链路（Property 4–11）。

**单元 / 示例测试**：
- 开始日期缺省（1.5）、`COUNT` 与星期几边界（1.7、2.10）、快照目标缺失（4.6）等具体边界。
- 确认事务原子性回滚（4.2）、懒生成单规则失败隔离（3.8）：注入失败，断言状态保持与不阻断。

**集成测试**：
- 提醒衔接（7.1、7.2、7.4、7.6）：mock `WeChatClient`，断言满足条件发送一次、各类故障不阻断主路径、无额度不发送。
- 兼容回归（9.5、9.6）：对交易创建 / 账本 / 分类 / 账户 / 预算 / 报表六组接口断言引入前后响应字段集与错误码集
  一致；下线接口 + 删除 `V38` 两表后回归六组接口一致。
- 迁移冒烟（9.1、9.2）：`V38` 迁移成功、既有脚本未改、无指向既有表的外键。

## Glossary

见 `requirements.md` 术语表（周期规则 / 记账模板字段 / 频率配置 / 开始日期 / 结束条件 / 期次 / 待确认项 /
待确认项状态 / 确认入账 / 跳过 / 懒生成 / 暂停恢复 / 月末边界 / 当前账本 / 时区口径 等）。本设计另定：

- **recurring_rules**：周期规则表；一条规则含模板字段（type/amount/category/account/note）、频率子字段
  （weekly_days / month_day / month_end / year_month / year_day）、start_date、end_condition 及参数、status。
- **recurring_pending_items**：待确认生成项表；含 rule_id、冗余 ledger_id、occurrence_date、status、模板快照
  字段、confirmed_transaction_id；对 `(rule_id, occurrence_date)` 施加唯一约束 `uk_recurring_pending_rule_date`。
- **OccurrenceCalculator**：无副作用的期次计算纯组件，按 `Asia/Shanghai` 自然日口径计算期次序列与「已到期」判定，
  是懒生成的事实源。
- **生成下界（generationLowerBound）**：懒生成对某规则扫描期次的最早日，取 `max(start_date, 恢复当日)`，用于
  实现恢复后不回补暂停区间（需求 6.2）。
- **RecurringReminderNotifier**：在主路径事务边界之外复用微信一次性订阅消息链路发送「存在待确认周期记账」提醒的
  组件，同 `(user, ledger, 自然日)` 至多一条，失败仅记 `[RECURRING_REMIND_FAILED]` 日志。
