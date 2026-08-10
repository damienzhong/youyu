# Implementation Plan

## Overview

按「后端数据与算法 → 接口 → 前端 → 联调」增量实现，每步可独立编译 / 测试。期次计算（`OccurrenceCalculator`）是本特性的核心确定性纯算法，**先行落地并以 jqwik 属性测试（Property 1、2）门控**，再在其上构建懒生成与确认入账。确认入账复用既有 `TransactionService`（账户加锁 `findForUpdateByIdAndUserId` + 单事务原子）；生成幂等由 `recurring_pending_items` 的唯一约束 `uk_recurring_pending_rule_date` 构造性保证；提醒衔接在主路径事务边界之外执行。全部为增量迁移（Flyway `V38`），不改既有表 / 不建指向既有表的外键，可整块摘除。

## Task Dependency Graph

```
1 数据模型/迁移（V38 两表 + 实体/Repository）
├─ 2 OccurrenceCalculator 纯算法 + PBT(P1,P2)（依赖 1 的 RuleSpec 概念，可与 1.2 并行）
│   └─ 3 RecurringRuleService 校验/生命周期 + PBT(P3)（依赖 1,2）
│       └─ 4 懒生成（幂等/失败隔离/快照）+ PBT(P4,P5)（依赖 1,2,3）
│           └─ 5 确认/修改后确认/跳过/批量 + PBT(P6-P10)（依赖 3,4；确认复用 TransactionService）
├─ 6 检查点
├─ 7 控制器/DTO/错误码 + PBT(P11 隔离)（依赖 3,4,5）
├─ 8 提醒衔接 RecurringReminderNotifier + PBT(P12)（依赖 4,5，事务边界外）
├─ 9 前端 miniapp（依赖 7）
└─ 10 端到端联调 + 兼容回归 + 迁移冒烟（依赖 7,8,9）
```

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "3.1"] },
    { "id": 3, "tasks": ["3.2", "3.4", "3.5"] },
    { "id": 4, "tasks": ["3.3"] },
    { "id": 5, "tasks": ["4.1"] },
    { "id": 6, "tasks": ["4.2", "4.3", "4.4", "5.1"] },
    { "id": 7, "tasks": ["5.2"] },
    { "id": 8, "tasks": ["5.3"] },
    { "id": 9, "tasks": ["5.4"] },
    { "id": 10, "tasks": ["5.5", "5.6", "5.7", "5.8", "5.9", "5.10"] },
    { "id": 11, "tasks": ["7.1", "7.2", "7.3"] },
    { "id": 12, "tasks": ["7.4", "7.5", "8.1"] },
    { "id": 13, "tasks": ["8.2", "8.3"] },
    { "id": 14, "tasks": ["9.1"] },
    { "id": 15, "tasks": ["9.2", "9.3", "9.4", "9.5"] },
    { "id": 16, "tasks": ["9.6"] },
    { "id": 17, "tasks": ["10.1", "10.2", "10.3"] }
  ]
}
```

## Tasks

- [x] 1. 数据模型与迁移（Flyway V38）
  - [x] 1.1 新增 Flyway 迁移 `V38__recurring_transactions.sql`（接续 `V37__aa_ledger.sql`）
    - 新建 `recurring_rules`（模板字段 type/amount/category_id/account_id/note、频率子字段 frequency/weekly_days/month_day/month_end/year_month/year_day、start_date/end_condition/until_date/count_n、status、created_at/updated_at；索引 `idx_recurring_rules_ledger_status`、`idx_recurring_rules_user`）
    - 新建 `recurring_pending_items`（rule_id、冗余 ledger_id、occurrence_date、status、模板快照字段、confirmed_transaction_id、时间戳；唯一约束 `uk_recurring_pending_rule_date (rule_id, occurrence_date)`；索引 `idx_recurring_pending_ledger_status_date`、`idx_recurring_pending_rule`）
    - 金额列 `DECIMAL(18,2)`；不修改 / 不复用既有版本号，不对既有表加列 / 加约束，不建指向既有表的外键
    - _Requirements: 9.1, 9.2, 9.3, 9.7_

  - [x] 1.2 实体与 Repository
    - `RecurringRule`（含 `RuleStatus` ACTIVE/PAUSED、`Frequency`、`EndCondition` 枚举）、`RecurringPendingItem`（含 `PendingStatus` PENDING/CONFIRMED/SKIPPED）实体
    - `RecurringRuleRepository`（按 ledger+status 列出 ACTIVE 规则、按 id+user+ledger 归属查询）
    - `RecurringPendingItemRepository`（`existsByRuleIdAndOccurrenceDate`、按 ledger+status+occurrence_date 排序查询、按 rule 级联删除 PENDING）
    - _Requirements: 9.2, 9.3, 8.4_

- [x] 2. 期次计算核心（OccurrenceCalculator，纯函数，先行可测）
  - [x] 2.1 实现 `RuleSpec` 值对象与 `OccurrenceCalculator`
    - `occurrencesUpTo(RuleSpec, today)`：按到期日升序去重返回全部已到期且满足开始 / 结束条件的期次
    - DAILY（连续自然日相差 1）、WEEKLY（weekly_days ∈ 1–7 非空集合，start_date 当天在集合内则首期）、MONTHLY（指定日 D 落 `min(D, YearMonth.lengthOfMonth())`；start_date 所在月仅当第 D 日不早于 start_date 才生成）、MONTHLY 月末标记（等价 D=31）、YEARLY（month+day，不存在落当月最后一日）
    - `isDue(RuleSpec, occurrenceDate, today)`：`occurrenceDate ≤ today` 且属于期次序列；全部按 `Asia/Shanghai` 自然日口径（`LocalDate.now(clock)`）
    - 统一开始 / 结束边界：不生成早于 start_date；UNTIL_DATE 含端点 ≤ until_date；COUNT 按升序累计至 N 停止（不区分状态）
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.11, 3.6_

  - [x]* 2.2 编写属性测试：期次计算确定性与月末 / 年边界
    - **Property 1: 期次计算确定性与月末 / 年边界**
    - 标签 `Feature: recurring-transactions, Property 1: ...`，`@Property` ≥ 100 次迭代；生成器覆盖各频率 + 星期几集合 + 指定日 1–31 / 月末 + 年月日、跨平 / 闰年 / 小月 / 跨年的随机 today
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.11, 3.6**

  - [x]* 2.3 编写属性测试：已到期判定与结束条件边界
    - **Property 2: 已到期判定与结束条件边界**
    - 标签 `Feature: recurring-transactions, Property 2: ...`，≥ 100 次迭代
    - **Validates: Requirements 2.7, 2.8, 2.9, 3.6**

- [x] 3. 周期规则服务（RecurringRuleService，校验 + 生命周期）
  - [x] 3.1 规则创建与校验
    - 校验模板字段（类型仅 expense/income、金额 0.01–999999999.99 且 2 位小数 HALF_UP、分类属当前账本、账户为当前用户在当前账本可用、备注 ≤200）、频率配置（枚举、WEEKLY 集合非空且 1–7、MONTHLY 有日、YEARLY 有月日）、结束条件（UNTIL_DATE ≥ start_date、COUNT 1–9999）
    - 开始日期缺省取创建当日（Asia/Shanghai）；`weekly_days` 规范化为稳定升序逗号串；归属当前用户 / 当前账本，初始状态 ACTIVE
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 2.10_

  - [x] 3.2 规则查询与编辑
    - 列出当前账本当前用户规则（含 ACTIVE / PAUSED）、详情（越权 `NOT_FOUND`）
    - 编辑频率配置 / 模板字段仅对编辑后新生成项生效，既有 PENDING 保留生成时快照，不改任何 CONFIRMED 历史流水
    - _Requirements: 6.3, 6.4, 6.7, 8.4, 8.5_

  - [x] 3.3 规则暂停 / 恢复 / 删除
    - 暂停（ACTIVE→PAUSED，既有 PENDING 保持不变）、恢复（PAUSED→ACTIVE，`generationLowerBound = max(start_date, 恢复当日)`，不回补暂停区间期次）
    - 删除（不再生成、级联移除全部 PENDING，保留 CONFIRMED 历史流水与 SKIPPED 记录）；越权 `NOT_FOUND`；均不回滚已发生余额变动
    - _Requirements: 6.1, 6.2, 6.5, 6.6, 6.7, 8.5_

  - [x]* 3.4 编写属性测试：模板字段与频率配置校验（零副作用）
    - **Property 3: 模板字段与频率配置校验（零副作用）**
    - 标签 `Feature: recurring-transactions, Property 3: ...`，≥ 100 次迭代；生成器覆盖合法与各类非法字段
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.6, 1.7, 1.8, 2.10**

  - [x]* 3.5 编写单元测试：创建边界示例
    - 开始日期缺省（1.5）、COUNT 与星期几集合边界（1.7、2.10）
    - _Requirements: 1.5, 1.7, 2.10_

- [x] 4. 懒生成（事实源）
  - [x] 4.1 实现 `RecurringPendingItemService.lazyGenerate`
    - 对当前账本每条 ACTIVE 规则：`occurrencesUpTo` 逐期次，`d < generationLowerBound` 跳过，`existsByRuleIdAndOccurrenceDate` 已存在则跳过，否则 `save` 一条 PENDING 并写入模板快照
    - 撞唯一键 `DataIntegrityViolationException` 就地捕获静默（视为已生成，不报错）；单规则异常 try/catch 就地隔离仅记 `[RECURRING_GEN_FAILED]` 日志，不阻断其余规则与已有项返回；生成过程不创建交易、不改账户余额；PAUSED 规则不进入扫描
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7, 3.8, 9.4_

  - [x]* 4.2 编写属性测试：生成幂等（构造性 Σ）
    - **Property 4: 生成幂等（构造性 Σ）**
    - 标签 `Feature: recurring-transactions, Property 4: ...`，≥ 100 次迭代；`@SpringBootTest` + H2(MODE=MySQL)，覆盖多次 / 交错懒生成
    - **Validates: Requirements 3.3, 3.4, 9.3, 9.4**

  - [x]* 4.3 编写属性测试：懒生成补齐且生成期不触账、快照不可变
    - **Property 5: 懒生成补齐且生成期不触账，快照不可变**
    - 标签 `Feature: recurring-transactions, Property 5: ...`，≥ 100 次迭代
    - **Validates: Requirements 3.1, 3.2, 3.5, 3.7, 6.1, 6.3, 6.4**

  - [x]* 4.4 编写单元测试：单规则补齐失败隔离
    - 注入某规则补齐失败，断言不阻断同账本其余规则补齐与已有项返回
    - _Requirements: 3.8_

- [x] 5. 待确认项确认 / 修改后确认 / 跳过 / 批量
  - [x] 5.1 待确认项查询
    - 先触发懒生成再返回当前账本 PENDING 列表，每项含来源规则 id、occurrence_date 与模板快照字段
    - 排序：occurrence_date 升序 → 规则 created_at 升序 → 项 id 升序（可复现）；无 PENDING 返回空列表不报错
    - _Requirements: 3.7, 5.1, 5.2, 5.3, 8.4_

  - [x] 5.2 确认入账（单条，含修改后确认）
    - 单事务内：按（改后或快照的）type/amount/account/category 走 `TransactionService.create`（账户加锁 + 余额更新）→ 回填 `confirmed_transaction_id` 并置 CONFIRMED；任一步失败整体回滚，保持 PENDING、不生成流水、不改余额
    - 修改后确认重跑需求 1 校验（金额 / 分类 / 账户 / 备注）；置 CONFIRMED 对 `status=PENDING` 条件更新（乐观并发，仅一条成功）
    - 已处理 → `RECURRING_ITEM_ALREADY_PROCESSED`；快照分类 / 账户在当前账本已不存在 → `RECURRING_ITEM_TARGET_MISSING` 保持 PENDING
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 4.6, 4.7, 4.8, 4.9, 9.7_

  - [x] 5.3 跳过本期
    - PENDING→SKIPPED，不生成流水、不改余额；对已 CONFIRMED / SKIPPED 再次操作 → `RECURRING_ITEM_ALREADY_PROCESSED`
    - _Requirements: 4.4, 4.5_

  - [x] 5.4 批量确认 / 批量跳过
    - 逐条在各自独立事务内按单条口径处理；某条失败仅回滚该条并保持原状态、继续其余；返回逐条结果与成功 / 失败计数
    - _Requirements: 5.4, 5.5, 5.6_

  - [x]* 5.5 编写属性测试：确认账户守恒且与手动记账口径一致
    - **Property 6: 确认账户守恒且与手动记账口径一致**
    - 标签 `Feature: recurring-transactions, Property 6: ...`，≥ 100 次迭代
    - **Validates: Requirements 4.1, 4.3, 4.7, 9.7**

  - [x]* 5.6 编写属性测试：跳过守恒
    - **Property 7: 跳过守恒**
    - 标签 `Feature: recurring-transactions, Property 7: ...`，≥ 100 次迭代
    - **Validates: Requirements 4.4**

  - [x]* 5.7 编写属性测试：确认 / 跳过状态机幂等（含并发）
    - **Property 8: 确认 / 跳过状态机幂等（含并发）**
    - 标签 `Feature: recurring-transactions, Property 8: ...`，≥ 100 次迭代；覆盖任意次数 / 交错的确认 / 跳过序列
    - **Validates: Requirements 4.5, 4.8, 4.9**

  - [x]* 5.8 编写属性测试：待确认项查询过滤、排序确定性与批量隔离
    - **Property 9: 待确认项查询过滤、排序确定性与批量隔离**
    - 标签 `Feature: recurring-transactions, Property 9: ...`，≥ 100 次迭代；覆盖混合状态、跨规则跨账本数据
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6**

  - [x]* 5.9 编写属性测试：生命周期历史不可变
    - **Property 10: 生命周期历史不可变**
    - 标签 `Feature: recurring-transactions, Property 10: ...`，≥ 100 次迭代；覆盖任意暂停 / 恢复 / 编辑 / 删除序列，断言 CONFIRMED 流水 / 余额 / SKIPPED 记录不变、删除后 PENDING 消失、恢复不回补
    - **Validates: Requirements 6.2, 6.5, 6.6**

  - [x]* 5.10 编写单元测试：确认事务原子性回滚
    - 注入确认事务某步失败，断言保持 PENDING、不生成流水、不改余额
    - _Requirements: 4.2_

- [x] 6. 检查点 — 后端核心（算法 / 生成 / 确认）全绿
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. 控制器、DTO 与错误码
  - [x] 7.1 `RecurringRuleController` + DTOs（`/api/recurring/rules`）
    - POST / GET / GET{id} / PUT / DELETE / `{id}/pause` / `{id}/resume`；`CurrentUser` + `CurrentLedger` 解析隔离；响应金额字符串化 `BigDecimal(2dp)`
    - _Requirements: 1.1, 6.1, 6.2, 6.3, 6.5, 8.1, 8.2, 8.3_

  - [x] 7.2 `RecurringPendingItemController` + DTOs（`/api/recurring/pending-items`）
    - GET（先懒生成）/ `{id}/confirm`（可携改后字段）/ `{id}/skip` / `batch-confirm` / `batch-skip`
    - _Requirements: 4.1, 4.4, 5.1, 5.4, 5.5, 5.6, 8.1, 8.2, 8.3_

  - [x] 7.3 新增错误码与统一映射
    - `RECURRING_RULE_INVALID`(400)、`RECURRING_FREQUENCY_INVALID`(400)、`RECURRING_END_CONDITION_INVALID`(400)、`RECURRING_ITEM_ALREADY_PROCESSED`(409)、`RECURRING_ITEM_TARGET_MISSING`(409)，接入 `GlobalExceptionHandler`；复用既有 `AMOUNT_INVALID` / `NOTE_TOO_LONG` / `NOT_FOUND` / `UNAUTHENTICATED`
    - _Requirements: 1.4, 1.6, 1.7, 4.5, 4.6, 8.2, 8.3_

  - [x]* 7.4 编写属性测试：账本与用户隔离
    - **Property 11: 账本与用户隔离**
    - 标签 `Feature: recurring-transactions, Property 11: ...`，≥ 100 次迭代；覆盖多用户多账本数据与跨界确认 / 跳过 / 暂停 / 恢复 / 编辑 / 删除返回 `NOT_FOUND`
    - **Validates: Requirements 6.7, 8.1, 8.4, 8.5**

  - [x]* 7.5 编写集成测试：端点鉴权 / 越权 / 账本隔离
    - 无 / 失效令牌 `UNAUTHENTICATED`、越权或跨账本 `NOT_FOUND`、`X-Ledger-Id` 缺省走默认账本
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 8. 提醒衔接（RecurringReminderNotifier）
  - [x] 8.1 实现 `RecurringReminderNotifier`
    - 主路径事务边界之外（`afterCommit` / 调度线程）复用 `WeChatClient` 一次性订阅消息；`(user_id, ledger_id, 自然日)` 去重预检至多一条；无有效订阅额度不发送；投递失败 / 异常 / 超 5 秒 / 额度耗尽就地捕获仅记 `[RECURRING_REMIND_FAILED]` 日志，不阻断生成 / 查询 / 确认 / 跳过 / 登录，不改任何状态 / 流水 / 余额
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x]* 8.2 编写属性测试：提醒隔离与同日去重
    - **Property 12: 提醒隔离与同日去重**
    - 标签 `Feature: recurring-transactions, Property 12: ...`，≥ 100 次迭代；覆盖成功 / 失败 / 超时 / 无额度与任意次数触发
    - **Validates: Requirements 7.3, 7.5**

  - [x]* 8.3 编写集成测试：提醒衔接
    - mock `WeChatClient`：满足条件发送一次、各类故障不阻断主路径、无额度不发送
    - _Requirements: 7.1, 7.2, 7.4, 7.6_

- [x] 9. 前端（miniapp，uni-app / Vue 3）
  - [x] 9.1 新增 API 封装 `miniapp/src/api/recurring.js`
    - 规则 CRUD / 暂停 / 恢复、待确认项查询 / 确认 / 跳过 / 批量；统一带 `X-Ledger-Id`，风格对齐 `aa.js` / `reminder.js`
    - _Requirements: 8.1_

  - [x] 9.2 `pages/recurring/`（规则列表）
    - 列出 ACTIVE / PAUSED 规则与频率摘要（如「每月 5 日 · 支出 ¥3000」）、暂停 / 恢复 / 编辑 / 删除入口
    - _Requirements: 6.1, 6.2, 6.5_

  - [x] 9.3 `pages/recurringedit/`（新建 / 编辑）
    - 类型 / 金额 / 分类 / 账户（复用 `AccountBadge`）/ 备注、频率选择（每天 / 每周星期几多选 / 每月指定日或月末 / 每年月+日）、开始日期、结束条件（永不 / 到某日 / 共 N 次）
    - _Requirements: 1.1, 1.5, 1.6, 1.7, 1.8, 6.3_

  - [x] 9.4 `pages/recurringpending/`（待确认项列表）
    - 按到期日升序分组，单条确认 / 修改后确认 / 跳过，多选批量确认 / 批量跳过，按逐条成功 / 失败反馈
    - _Requirements: 4.1, 4.3, 4.4, 5.1, 5.3, 5.4, 5.5, 5.6_

  - [x] 9.5 首页「待确认」入口角标
    - 展示当前账本 PENDING 数量角标，点击进入待确认列表
    - _Requirements: 5.1_

  - [x] 9.6 视觉对齐与构建
    - 复用既有分类图标与 `AccountBadge`、金额 `tabular-nums`；H5 与 mp-weixin 构建通过
    - _Requirements: 5.1_

- [x] 10. 端到端联调、兼容回归与迁移冒烟
  - [x] 10.1 端到端全流程自动化集成测试
    - 建规则 → 懒生成待确认 → 确认 / 修改后确认 / 跳过 → 批量确认 / 跳过 → 暂停 / 恢复 / 编辑 / 删除；断言 Property 4–11 在真实链路成立、金额闭合
    - _Requirements: 全部_

  - [x] 10.2 兼容回归测试
    - 对交易创建 / 账本 / 分类 / 账户 / 预算 / 报表六组接口断言引入前后响应字段集与错误码集逐一相同；下线周期记账接口 + 删除 `V38` 两表后回归六组接口一致
    - _Requirements: 9.5, 9.6_

  - [x] 10.3 迁移冒烟测试
    - 断言 `V38` 迁移成功、既有迁移脚本未被修改、无指向既有表的外键
    - _Requirements: 9.1, 9.2_

- [x] 11. 最终检查点 — 全量测试与前端构建通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选测试（单元 / 属性 / 集成），可为快速 MVP 跳过；顶层任务不加 `*`，核心实现任务不可跳过。
- 迁移版本号接续现有最大版本 `V37`，取 `V38`；不改既有表、不建指向既有表的外键，删除 `V38` 两表即整块摘除（需求 9.1、9.2）。
- 金额一律 `BigDecimal(18,2)`、HALF_UP；账户写操作复用 `accountRepository.findForUpdateByIdAndUserId`（本人账户加锁），与既有转账 / 余额调整同口径。
- 确认入账在单事务内保证「建交易 + 更新余额 + 待确认项置 CONFIRMED」全部提交或全部回滚；批量逐条各自独立事务。
- 生成幂等由唯一约束 `uk_recurring_pending_rule_date` 构造性保证，撞键静默；提醒发送在主路径事务边界之外，失败仅记日志。
- 属性测试用 jqwik（见 `.jqwik-database`），每条 Property 单独一个属性测试、≥100 次迭代，标签格式 `Feature: recurring-transactions, Property {n}: {text}`；纯算法 Property 1、2 直接对 `OccurrenceCalculator` 运行，涉持久化 / 余额的 Property 4–12 走 `@SpringBootTest` + H2(MODE=MySQL)。
- 测试策略与属性映射见 design.md「Testing Strategy」与「Correctness Properties」Property 1–12。
