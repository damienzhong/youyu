# Design Document

## Overview

周期记账·自动入账（Recurring_Auto_Post）是 **recurring-transactions** 的**纯增量增强**：给周期规则加一个
「入账方式」维度 `post_mode`（`CONFIRM` 默认 / `AUTO`），并在既有「到期 → 生成待确认项 → 用户确认才落库」
链路旁，为 `AUTO` 规则加一条「到期 → 直接走既有交易创建链路落库 + 告知用户」的路径。核心哲学与既有特性一致：
**复用既有链路、构造性幂等、失败零副作用、可整块摘除**——不新建第二套记账逻辑、不改任何既有接口契约。

设计的四条主线：

- **入账方式是规则上的一个开关。** `recurring_rules` 加一列 `post_mode`（Flyway `V44`，接续当前最大 `V43`），
  默认 `CONFIRM`；存量行视为 `CONFIRM`，行为与现状完全一致（需求 1、6）。改 `post_mode` 只影响之后新处理的
  期次，不回滚历史（需求 1.5）。
- **AUTO 到期直接走既有 `TransactionService.create`。** 复用既有账户加锁 + 单事务原子 + 余额更新，生成一条
  真实流水并把该期次记为 `CONFIRMED` + 回填 `confirmedTransactionId`——与「确认入账」是同一段落库逻辑，
  只是**免去用户手动确认**（需求 2）。
- **幂等仍由既有唯一约束承担。** 沿用 `recurring_pending_items` 的 `uk_recurring_pending_rule_date
  (rule_id, occurrence_date)`：同一期次要么自动入账为一条 `CONFIRMED`，要么降级为一条 `PENDING`，至多其一、
  至多一条（需求 2.4、3.4）。
- **双触发 + 降级兜底 + 告知。** 懒入账（查询周期视图时补齐并入账，事实源）叠加每日 `@Scheduled` 定时任务
  （用户不打开也入账），二者共用同一自动入账逻辑与同一幂等键（需求 4）；目标失效 / 校验不过时降级为 `PENDING`、
  绝不静默丢失（需求 3）；入账成功后经既有微信订阅消息链路告知用户，投递故障隔离（需求 5）。

一句话边界：**在既有周期特性上加一个「入账方式」列 + 一条自动入账路径 + 一个每日定时任务 + 一类告知消息；
摘掉 `post_mode` 列后所有规则一律回退为待确认行为，其余功能原样成立。**

## Architecture

```
miniapp（规则新建/编辑加「入账方式」选择；待确认列表照旧显示降级项）
  │  REST + Authorization(JWT) + X-Ledger-Id
  ▼
RecurringRuleController（规则 CRUD，DTO 加 postMode 字段）
  ▼
RecurringRuleService（校验 postMode 枚举，默认 CONFIRM；其余校验口径不变）
  │
  ▼  懒入账（事实源）                            每日定时任务（兜底/及时）
RecurringPendingItemService.lazyGenerate  ◄──►  RecurringAutoPostScheduler（新增，@Scheduled 每日）
  │  对 AUTO 规则的到期期次不再只生成 PENDING，          │  扫描全部 AUTO 规则的到期期次
  │  改为调用 ↓ 自动入账（或降级）                       │  逐规则/逐期次调用 ↓，就地隔离失败
  ▼                                                     ▼
RecurringAutoPoster（新增：单期次自动入账，REQUIRES_NEW 独立事务）
  │  ①闸门占位（唯一键幂等） ②目标/金额校验→不过则降级 PENDING（需求3）
  │  ③走既有 TransactionService.create（账户加锁+余额更新） ④记 CONFIRMED+回填 txId
  ▼                                                     │ afterCommit（事务边界外）
TransactionService.create（既有，账户加锁 + 单事务原子）   ▼
                                            RecurringAutoPostNotifier（新增：告知「已自动记一笔」）
                                                          │ 复用
                                                          ▼
                                            WeChatClient.sendSubscribeMessage + 订阅额度（既有）
  ▼
Repositories：RecurringRuleRepository / RecurringPendingItemRepository（复用，+ 按 postMode 过滤查询）
  ▼
MySQL（Flyway V44：recurring_rules 加 post_mode 列，默认 'CONFIRM'）
```

复用既有：`OccurrenceCalculator`（期次纯函数计算，不变）、`RecurringPendingItemGenerator`（降级写 PENDING
复用同款 REQUIRES_NEW 单条写入）、`TransactionService.create`（账户加锁 + 余额更新）、`RecurringTemplateValidator`
（金额 / 备注校验）、`LedgerAccountResolver` / `CategoryRepository`（目标可用性）、`Clock`（`Asia/Shanghai`）、
`WeChatClient` + 订阅额度体系（`RecurringReminderNotifier` 同款通道）、`@EnableScheduling` / `SchedulingConfig`
（既有 `ReminderScheduler` 已在用）。

分层职责与既有一致：Controller 只装配请求 / 响应与 `CurrentUser` / `CurrentLedger`；校验与事务下沉 Service；
自动入账的单期次落库封装为独立 bean `RecurringAutoPoster`（`REQUIRES_NEW`，同 `RecurringPendingItemGenerator`
规避 JPA 事务毒化与 Spring 自调用陷阱）；定时任务 `RecurringAutoPostScheduler` 只做扫描与逐条隔离调用；
告知 `RecurringAutoPostNotifier` 在事务边界外发送。

## Data Models

单一 Flyway 增量迁移 `V44__recurring_rule_post_mode.sql`（接续当前最大版本 `V43__budget_reminder.sql`，
不修改 / 不复用任何既有版本号，不新增指向其它表的外键，不改动任何其它表；需求 6.2）。

### recurring_rules 增列（唯一 DDL 变更）

```sql
-- V44__recurring_rule_post_mode.sql
ALTER TABLE recurring_rules
    ADD COLUMN post_mode VARCHAR(16) NOT NULL DEFAULT 'CONFIRM';  -- 'CONFIRM' | 'AUTO'
```

- 列默认 `'CONFIRM'`：存量行与未显式指定的新建规则一律得 `CONFIRM`，行为与现状完全一致（需求 1.2、1.3、6.4）。
- 不加 CHECK 约束（与既有频率子字段同思路，保持迁移简单可摘除）；取值合法性在应用层由枚举校验（需求 1.4）。
- **摘除路径**：删除该列（或将其一律视作 `CONFIRM`）后，周期系统回退为「到期只生成待确认项」的既有行为（需求 6.4）。
- 不改 `recurring_pending_items` 表结构：AUTO 入账成功记录仍是一条 `status=CONFIRMED` 且 `confirmed_transaction_id`
  非空的行，与「用户确认入账」产生的行**结构一致、不可区分来源**（口径统一，需求 2.8）。

### 实体变更

`RecurringRule` 新增字段（与既有频率枚举同款 `@Enumerated(EnumType.STRING)`）：

```java
/** 入账方式：CONFIRM（待确认，默认）/ AUTO（自动入账），以枚举名存储。 */
@Enumerated(EnumType.STRING)
@Column(name = "post_mode", nullable = false, length = 16)
private PostMode postMode = PostMode.CONFIRM;
```

新增枚举 `PostMode { CONFIRM, AUTO }`（同 `RuleStatus` / `Frequency` 风格）。H2 测试表由 Hibernate 依实体生成，
默认值由实体字段初始值 + 服务层显式赋值双重保证。

## 自动入账算法（RecurringAutoPoster，单期次，REQUIRES_NEW 独立事务）

自动入账是本特性核心新增落库逻辑，封装为独立 bean 的单个 `@Transactional(REQUIRES_NEW)` 方法，供懒入账与
定时任务逐期次调用。伪代码：

```
autoPost(rule, occurrenceDate):                                  // REQUIRES_NEW 独立事务
  // A) 构造性幂等占位（需求 2.4、3.4）：先抢占 (rule_id, occurrence_date) 唯一键。
  //    撞键 → 该期次已被处理（另一路径已入账/降级），静默结束，不产生第二条、不改余额。
  try: placeholder = pendingItemRepository.saveAndFlush(snapshotOf(rule, occurrenceDate, status=PENDING))
  catch DataIntegrityViolationException: return ALREADY_PROCESSED   // 幂等：静默

  // B) 目标/金额校验（需求 3.1、3.2）：分类属账本、账户为所有者在账本可用、金额合法。
  if 校验不过:
      // 降级为待确认：占位行保持 PENDING（携原模板快照），交用户处理，不入账、不改余额（需求 3.1、3.2、3.3）。
      return DEGRADED_TO_PENDING

  // C) 走既有交易创建链路（需求 2.1、2.3、2.8）：记账时间 = occurrenceDate 当地 00:00（需求 2.2）。
  tx = transactionService.create(userId, ledgerId, type, amount, accountId, categoryId,
                                 occurrenceDate.atStartOfDay(), note)

  // D) 占位行升为 CONFIRMED + 回填 confirmedTransactionId（与既有确认入账同款）。
  placeholder.status = CONFIRMED; placeholder.confirmedTransactionId = tx.id
  pendingItemRepository.save(placeholder)
  return AUTO_POSTED(tx)         // 供调用方在 afterCommit 触发告知（需求 5）
```

关键点：

- **占位先行 = 构造性幂等（需求 2.4、3.4）。** 先写一条 `PENDING` 占位抢唯一键 `uk_recurring_pending_rule_date`；
  懒入账与定时任务、并发多次触发同一期次时，只有一个能插入成功，其余撞
  `DataIntegrityViolationException` 静默返回「已处理」——**至多一条流水、至多一次余额变动**。占位放在
  `REQUIRES_NEW` 内，撞键只回滚本新事务，不毒化外层（同 `RecurringPendingItemGenerator` 的 JPA 陷阱规避）。
- **校验不过 = 降级为 PENDING（需求 3）。** 占位行本就是 `PENDING`，校验失败时**保留**它（不升 `CONFIRMED`、
  不建交易、不改余额），它自然进入既有待确认列表，与普通待确认项在查询 / 确认 / 修改后确认 / 跳过 / 批量上
  **完全一致**（需求 3.3）。降级判定复用 `RecurringPendingItemService.confirm` 的同款目标校验
  （`categoryRepository.findByIdAndLedgerId` + `accountResolver.selectableAccounts`）与 `RecurringTemplateValidator`
  金额校验。
- **落库复用既有链路（需求 2.1、2.3、2.8）。** `TransactionService.create` 以默认传播加入本 `REQUIRES_NEW`
  事务：建交易 + 账户加锁余额更新 + 占位行升 `CONFIRMED` + 回填 `txId` 全部提交或全部回滚（需求 2.3）；
  产生的流水与手动记账 / 用户确认入账在列表 / 统计 / 余额 / 报表口径完全一致（需求 2.8）。
- **记账时间 = 期次到期日 00:00（`Asia/Shanghai`）（需求 2.2）**，与懒入账 / 定时任务实际执行时刻无关。
- **非预期异常就地隔离（需求 3.5）。** `autoPost` 抛出的运行时异常由调用方（懒入账 / 定时任务）捕获、仅记
  `[RECURRING_AUTOPOST_FAILED]` 告警，不阻断同规则其它期次、同账本其它规则、也不阻断懒入账所在查询主路径返回。

> **降级行 vs 首次即 CONFIRM 规则的 PENDING**：二者都是 `status=PENDING`，进入同一待确认列表。这正是需求 3.3
> 要求的「与普通待确认项完全一致」，无需区分来源——用户改分类 / 账户 / 金额后确认即可。

## 双触发（懒入账 + 每日定时任务）

两条路径共用 `RecurringAutoPoster.autoPost` 与同一幂等键，结果一致、至多一条流水（需求 4.3）。

### 懒入账（事实源，改造 `RecurringPendingItemService.generateForRule`）

既有 `generateForRule` 对每个到期且表中无记录的期次调用 `generator.generate`（写 `PENDING`）。改造为**按规则
入账方式分流**：

```
for occurrenceDate in calculator.occurrencesUpTo(spec, today):
    if occurrenceDate < generationLowerBound(rule): continue
    if pendingItemRepository.existsByRuleIdAndOccurrenceDate(rule.id, occurrenceDate): continue
    if rule.postMode == AUTO:
        result = autoPoster.autoPost(rule, occurrenceDate)     // 入账 或 降级 PENDING（都幂等）
        if result == AUTO_POSTED: notifier.notifyAfterCommitOrEnqueue(rule, result.tx)  // 事务边界外告知
    else:  // CONFIRM
        generator.generate(rule, occurrenceDate)               // 既有：写 PENDING（不变）
```

- `CONFIRM` 分支**完全不变**（需求 2.7）；`AUTO` 分支走自动入账 / 降级。
- 撞唯一键静默、单规则失败 `try/catch` 就地隔离仅记日志——沿用既有 `lazyGenerate` 的失败隔离结构（需求 3.5、4.1）。
- 告知在自动入账事务**提交之后**触发（见「告知」小节），故懒入账查询主路径不因告知成败改变返回（需求 5.3）。

### 每日定时任务（`RecurringAutoPostScheduler`，新增，仿 `ReminderScheduler`）

```java
@Scheduled(cron = "0 30 0 * * *", zone = "Asia/Shanghai")   // 每日 00:30，Asia/Shanghai
public void scan() {
    LocalDate today = LocalDate.now(clock);                  // 不依赖 JVM 默认时区（需求 4.4）
    for (RecurringRule rule : ruleRepository.findByStatusAndPostMode(ACTIVE, AUTO)) {  // 全部账本的 AUTO 规则
        try {
            for (LocalDate d : calculator.occurrencesUpTo(toRuleSpec(rule), today)) {
                if (d < generationLowerBound(rule)) continue;
                if (pendingItemRepository.existsByRuleIdAndOccurrenceDate(rule.getId(), d)) continue;
                try {
                    AutoPostResult r = autoPoster.autoPost(rule, d);
                    if (r.autoPosted()) notifier.notify(rule, r.transaction());   // 事务已提交，直接发
                } catch (Exception e) {
                    log.warn("[RECURRING_AUTOPOST_FAILED] ruleId={}, date={}", rule.getId(), d, e); // 期次级隔离
                }
            }
        } catch (Exception e) {
            log.warn("[RECURRING_AUTOPOST_RULE_FAILED] ruleId={}", rule.getId(), e);  // 规则级隔离
        }
    }
}
```

- **扫描全部账本的 `AUTO` 规则**（不像懒入账限定当前账本），新增仓库方法
  `findByStatusAndPostMode(RuleStatus.ACTIVE, PostMode.AUTO)`（需求 4.2）。
- **`Asia/Shanghai` 判定「今天」**（需求 4.4）；每日 00:30 触发，避开跨零点边界抖动。
- **双层 try/catch 就地隔离**（需求 4.5）：单期次失败不拖垮同规则其余期次，单规则失败不拖垮整轮扫描。
- **主路径事务边界之外**（需求 4.6）：定时线程独立，不参与记账 / 登录事务，其成败不改任何主路径返回。
- 与懒入账**共用** `autoPoster.autoPost` 与 `(rule_id, occurrence_date)` 幂等键：谁先到谁入账，另一路径撞键
  静默（需求 4.3）。

## 告知（RecurringAutoPostNotifier，事务边界外）

复用既有微信一次性订阅消息链路（`WeChatClient.sendSubscribeMessage` + 订阅额度体系，与
`RecurringReminderNotifier` 同款通道，不新建投递通道；需求 5）。

- **触发时机（需求 5.3）**：自动入账事务**提交之后**发送。懒入账路径经
  `TransactionSynchronizationManager.registerSynchronization(afterCommit)`（或收集入账结果、在懒入账循环外
  统一发送）确保「提交后再发」；定时任务路径因 `autoPost` 已是独立 `REQUIRES_NEW` 事务、返回即已提交，直接发。
- **内容（需求 5.1）**：一条「已自动记一笔」摘要，含金额、分类或备注等**非敏感**信息；不含账户余额 / 令牌 /
  邮箱等敏感字段。
- **额度与降级（需求 5.2、5.4）**：所有者持有有效订阅额度才发送并消费一次额度（同
  `ReminderDispatchService` 口径）；无额度不发送但入账照常完成。投递失败 / 微信异常 / 超 5 秒未返回 / 额度耗尽
  由 `WeChatClient`（内含超时与故障归一）+ 本组件 `try/catch` 就地捕获，仅记 `[RECURRING_AUTOPOST_NOTIFY_FAILED]`
  告警，**绝不回滚已完成入账、不改任何流水 / 余额 / 期次状态 / 幂等键**（需求 5.2、5.3、5.5）。
- **不改判定（需求 5.5）**：告知的任何结果都不影响期次是否已入账的判定与幂等键——告知纯粹是入账完成后的
  旁路通知。

## Components and Interfaces

新增组件：

- `PostMode`（枚举 `CONFIRM` / `AUTO`）。
- `RecurringAutoPoster`（`@Component`，`autoPost(rule, occurrenceDate)` 为 `@Transactional(REQUIRES_NEW)`，
  返回 `AutoPostResult`：`AUTO_POSTED(tx)` / `DEGRADED_TO_PENDING` / `ALREADY_PROCESSED`）。
- `RecurringAutoPostScheduler`（`@Component`，每日 `@Scheduled` 扫描全部 `AUTO` 规则）。
- `RecurringAutoPostNotifier`（`@Component`，事务边界外发送告知，仿 `RecurringReminderNotifier`）。

改造既有：

- `RecurringRule` + `PostMode` 字段；`RecurringRuleService` 创建 / 编辑校验并落 `postMode`（默认 `CONFIRM`，
  非法值 → `RECURRING_POST_MODE_INVALID`）。
- `RecurringPendingItemService.generateForRule`：按 `postMode` 分流（`AUTO` 走自动入账 / 降级，`CONFIRM` 不变）。
- `RecurringRuleRepository`：新增 `findByStatusAndPostMode(RuleStatus, PostMode)`（定时任务扫描全账本 AUTO 规则）。
- DTO：`RecurringRuleController` 的创建 / 编辑请求与响应 DTO 增加 `postMode` 字段（默认 `CONFIRM`）。

### 接口契约（不新增端点，仅扩字段；需求 6.3）

- `POST /api/recurring/rules` / `PUT /api/recurring/rules/{id}`：请求体可选 `postMode`（`CONFIRM` / `AUTO`，
  缺省 `CONFIRM`）；非法值 → `RECURRING_POST_MODE_INVALID`（400，`field=postMode`）。响应体回显 `postMode`。
- `GET /api/recurring/rules` / `GET /api/recurring/rules/{id}`：响应体含 `postMode`。
- 待确认项相关端点（`/api/recurring/pending-items` 全部）**契约不变**：降级产生的 `PENDING` 项在既有列表 /
  确认 / 跳过 / 批量中与普通待确认项一致（需求 3.3、7.3、7.4）。
- 交易 / 账本 / 分类 / 账户 / 预算 / 报表六组既有接口的请求 / 响应 / 错误码集合**不变**（需求 6.3）。

## 事务边界（原子性）

- **自动入账（单期次）**：`RecurringAutoPoster.autoPost` 在一个 `REQUIRES_NEW` 事务内 = ①占位 `saveAndFlush`
  抢唯一键 → ②目标 / 金额校验（不过则保留占位为 `PENDING` 并提交，降级）→ ③`TransactionService.create`
  （加锁 + 余额更新）→ ④占位升 `CONFIRMED` + 回填 `txId`。步骤 ③④ 任一失败整体回滚，不生成流水、不改余额、
  不留 `CONFIRMED`（需求 2.3）。占位撞键回滚本新事务、静默（需求 2.4、3.4）。
- **懒入账**：不开外层大事务；对每个 `AUTO` 期次调用独立 `REQUIRES_NEW` 的 `autoPost`，单期次失败不毒化其余
  （沿用既有 `lazyGenerate` 无 `@Transactional` + 逐条独立事务的结构）。
- **定时任务**：定时线程逐规则逐期次调用 `autoPost`（各自 `REQUIRES_NEW`），双层 try/catch 就地隔离；在记账 /
  登录主路径事务边界之外（需求 4.6）。
- **告知**：在 `autoPost` 事务**提交之后**执行，失败仅记日志，不回滚入账（需求 5.2、5.3）。

## Correctness Properties

*属性（property）是应在系统所有合法执行下都成立的特征或行为——一个关于「系统应当做什么」的形式化陈述。*

下列属性由需求验收标准经 prework 分析、消冗归并后得到，均可「for all 输入」表达，适合以属性测试（jqwik）覆盖。

### Property 1: 入账方式默认与向后兼容

*对任意*创建 / 编辑周期规则请求：未显式指定入账方式时落库 `post_mode` 恒为 `CONFIRM`；显式指定 `CONFIRM` /
`AUTO` 时落库为对应值；指定非 `CONFIRM`/`AUTO` 的任意值时拒绝且规则表零变更；且入账方式的引入不改变
recurring-transactions 既有创建 / 编辑对频率 / 模板字段 / 开始日期 / 结束条件的校验口径与错误码。

**Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.6**

### Property 2: 改入账方式只影响之后期次

*对任意*规则与任意 `post_mode` 变更序列，变更前已 `CONFIRMED`（含自动入账）或已 `SKIPPED` 的期次与其历史
流水、账户余额均保持不变、不被回滚；新入账方式仅对变更时刻之后新处理的期次生效。

**Validates: Requirements 1.5**

### Property 3: AUTO 到期自动入账账户守恒且与手动记账口径一致

*对任意*启用且 `AUTO` 的规则的任一到期期次，当表中尚无该 `(rule_id, occurrence_date)` 记录且目标 / 金额合法时，
自动入账后：恰生成一条真实流水、对应账户余额恰变动 `+amount`（收入）/ `−amount`（支出）、该期次恰有一条
`CONFIRMED` 记录且 `confirmed_transaction_id` 指向该流水；流水记账时间等于该期次到期日 `Asia/Shanghai` 00:00；
对相同 `(type, amount, account, category)`，自动入账与手动记账对余额影响与流水关键字段一致；金额全程
`BigDecimal` 2 位小数（HALF_UP）。

**Validates: Requirements 2.1, 2.2, 2.3, 2.8, 6.5**

### Property 4: 自动入账幂等（构造性 Σ）

*对任意*规则与任意次数、任意交错的懒入账 / 定时任务触发，每个 `(rule_id, occurrence_date)` 组合至多产生
一条流水、至多一次账户余额变动、在待确认生成项表中至多一条记录；重复 / 并发触发的其余尝试判定为「已处理」
并静默结束、不返回错误、不产生第二条。

**Validates: Requirements 2.4, 3.4, 4.3**

### Property 5: 生命周期与结束/开始/暂停边界

*对任意*规则与任意「今天」，`PAUSED` 规则不自动入账任何期次；不为早于开始日期的期次自动入账；`UNTIL_DATE`
过期后 / `COUNT` 达 N 后不再自动入账其后期次（计数不区分状态）；`CONFIRM` 规则维持既有行为（到期只生成
`PENDING`，不自动入账）。

**Validates: Requirements 2.5, 2.6, 2.7**

### Property 6: 目标失效 / 金额非法降级为待确认

*对任意* `AUTO` 规则的到期期次，当其模板分类在当前账本已不存在、或账户不属当前用户在当前账本可用集、或金额
不满足既有校验时，系统改为该期次生成一条 `PENDING` 待确认项（携原模板快照），且不生成任何流水、不改任何
账户余额；该降级项与普通待确认项在查询 / 确认 / 修改后确认 / 跳过 / 批量上完全一致；同一期次要么一条
`CONFIRMED`、要么一条 `PENDING`，至多其一、至多一条。

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

### Property 7: 自动入账失败就地隔离

*对任意*自动入账过程中的非预期运行时异常，该期次的失败被就地隔离、仅记告警日志，不阻断同规则其它期次、
同账本其它规则的处理，也不阻断懒入账所在查询主路径的返回，不改任何已提交流水 / 余额。

**Validates: Requirements 3.5, 4.5, 4.6**

### Property 8: 告知隔离

*对任意*告知发送结果（成功 / 失败 / 超时 / 无额度）与任意次数触发，自动入账已生成的流水、账户余额、期次
状态、幂等键均不因告知链路的任何结果而改变；无有效订阅额度时不发送但入账照常完成；告知在入账事务提交之后
执行。

**Validates: Requirements 5.2, 5.3, 5.4, 5.5**

### Property 9: 账本与用户隔离、可摘除与金额口径

*对任意*多用户多账本的 `AUTO` / `CONFIRM` 规则数据：自动入账生成的流水、期次、余额变动只归属规则所有者与
所属账本，跨账本 / 跨用户不可见、不可操作，越权返回 `NOT_FOUND`；将全部规则视作 `CONFIRM`（摘除 `post_mode`）
后周期系统回退为「到期只生成待确认项」的既有行为，交易 / 账本 / 分类 / 账户 / 预算 / 报表六组接口对相同请求
返回与摘除前一致；所有金额以 `BigDecimal` 2 位小数（HALF_UP）存储与计算。

**Validates: Requirements 6.1, 6.3, 6.4, 6.5**

## Error Handling

沿用 `ApiException` 统一错误体 `{code, message, field}` 与 `GlobalExceptionHandler` 映射。仅新增一个错误码，
其余复用既有：

- `RECURRING_POST_MODE_INVALID`（400，`field=postMode`）：入账方式取值不在 `CONFIRM`/`AUTO` 内（需求 1.4）。
- 降级不是错误：目标失效 / 金额非法在**自动入账路径**改为生成 `PENDING`（需求 3），不对外抛错；仅当**用户
  确认**该降级项时才可能因目标仍缺失返回既有 `RECURRING_ITEM_TARGET_MISSING`（沿用既有确认口径）。
- 越权 / 不存在复用 `NOT_FOUND`；未认证复用 `UNAUTHENTICATED`（需求 6.1）。
- 金额越界 / 小数位复用 `AMOUNT_INVALID`（用于用户确认降级项时，需求 3.2）。

失败零副作用：`post_mode` 校验前置于任何写操作；自动入账占位撞键、目标校验不过均不生成流水、不改余额；
定时任务与告知的任何异常在其边界内就地捕获，不外泄错误码、不污染主路径。

## Testing Strategy

**双轨测试**：属性测试覆盖通用性质，单元 / 集成测试覆盖具体示例、边界、错误与外部依赖。

**属性测试（jqwik，本仓库既有 PBT 框架，见 `.jqwik-database`）**：
- 实现上文 9 条 Correctness Properties，每条以**单个**属性测试实现，最少 **100** 次迭代（`@Property`）。
- 标签格式：`Feature: recurring-auto-post, Property {number}: {property_text}`。
- 涉持久化 / 余额 / 隔离的属性走 `@SpringBootTest` + H2（`MODE=MySQL`），复用既有测试基座与
  `TransactionService` 真实链路（Property 3、4、6、7、9）；纯配置 / 边界属性可轻量化（Property 1、2、5）。
- 生成器覆盖：随机 `post_mode`（含非法值）、随机频率 / 开始 / 结束、随机「今天」（跨平 / 闰年、跨月末、跨年）、
  随机金额（含边界与超小数位）、随机目标失效场景（删分类 / 删账户）、随机多次 / 交错的懒入账与定时触发、
  随机多用户多账本数据。

**单元 / 示例测试**：
- `post_mode` 默认与非法值（1.2、1.4）、改 `post_mode` 只影响之后期次（1.5）。
- 自动入账事务原子性回滚（2.3）：注入 `TransactionService.create` 失败，断言无流水、无余额变动、无 `CONFIRMED`。
- 降级具体场景（3.1、3.2）：删分类 / 删账户 / 金额越界各生成一条 `PENDING`。

**集成测试**：
- 告知衔接（5.1、5.2、5.4）：mock `WeChatClient`，断言入账成功后发送一次、各类故障不阻断入账、无额度不发送。
- 定时任务（4.2、4.4、4.5、4.6）：注入固定 `Clock`，断言扫描全账本 AUTO 规则、`Asia/Shanghai` 判定、单条失败
  不拖垮整轮、不影响主路径。
- 双触发一致性（4.3）：懒入账与定时任务对同一期次至多一条流水。
- 兼容回归（6.3、6.4）：六组既有接口引入前后响应字段集与错误码集一致；`post_mode` 一律 `CONFIRM` 时回退为
  既有待确认行为。
- 迁移冒烟（6.2）：`V44` 迁移成功、既有脚本未改、无新增指向其它表的外键。

## Glossary

见 `requirements.md` 术语表。本设计另定：

- **PostMode**：规则入账方式枚举 `CONFIRM`（待确认，默认）/ `AUTO`（自动入账），映射 `recurring_rules.post_mode`。
- **RecurringAutoPoster**：单期次自动入账的独立 bean（`REQUIRES_NEW`）；占位抢唯一键构造幂等、目标 / 金额
  校验不过则降级 `PENDING`、否则走 `TransactionService.create` 落库并记 `CONFIRMED`。
- **RecurringAutoPostScheduler**：每日 `@Scheduled`（`Asia/Shanghai`）扫描全部 `ACTIVE`+`AUTO` 规则并对到期
  未处理期次自动入账的兜底任务，仿 `ReminderScheduler` 的双层失败隔离。
- **RecurringAutoPostNotifier**：自动入账事务提交后经既有微信订阅消息链路发送「已自动记一笔」告知的组件，
  失败仅记 `[RECURRING_AUTOPOST_NOTIFY_FAILED]` 日志。
- **降级为待确认（Degrade_To_Pending）**：AUTO 到期时目标失效 / 金额非法，占位行保留为 `PENDING` 交用户处理。
