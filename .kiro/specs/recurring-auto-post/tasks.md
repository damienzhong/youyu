# Implementation Plan

## Overview

按「迁移 + 实体 → 规则入账方式校验 → 单期次自动入账核心 → 双触发（懒入账改造 + 每日定时任务） → 告知 →
接口/前端 → 联调回归」增量实现，每步可独立编译 / 测试。核心新增落库逻辑是单期次自动入账
`RecurringAutoPoster`：占位抢唯一键 `uk_recurring_pending_rule_date` **构造性幂等**，目标 / 金额校验不过则
**降级为 PENDING**，否则复用既有 `TransactionService.create`（账户加锁 + 单事务原子 + 余额更新）落库并记
`CONFIRMED`。懒入账与每日定时任务**共用**该方法与同一幂等键。告知在入账事务提交之后、主路径事务边界之外
执行，失败仅记日志。全部为单一增量迁移（Flyway `V44` 给 `recurring_rules` 加 `post_mode` 列），不改其它表、
不新增指向其它表的外键，摘除 `post_mode` 即回退为既有待确认行为。

## Task Dependency Graph

```
1 迁移 V44 + PostMode 枚举 + RecurringRule.postMode 字段
├─ 2 规则入账方式校验（创建/编辑默认 CONFIRM、非法值报错）+ PBT(P1,P2)（依赖 1）
├─ 3 RecurringAutoPoster 单期次自动入账（占位幂等/降级/落库）+ PBT(P3,P4,P6)（依赖 1）
│   └─ 4 懒入账改造：generateForRule 按 postMode 分流 + PBT(P5,P7)（依赖 3）
│       └─ 5 每日定时任务 RecurringAutoPostScheduler（依赖 3,4）
├─ 6 检查点（后端核心全绿）
├─ 7 告知 RecurringAutoPostNotifier（事务边界外）+ PBT(P8)（依赖 3）
├─ 8 控制器/DTO/错误码（postMode 字段 + RECURRING_POST_MODE_INVALID）+ PBT(P9 隔离/摘除)（依赖 2）
├─ 9 前端 miniapp（入账方式选择）（依赖 8）
└─ 10 端到端联调 + 兼容回归 + 迁移冒烟（依赖 5,7,8,9）
```

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["2.1", "3.1"] },
    { "id": 2, "tasks": ["2.2", "3.2", "3.3"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.2", "4.3"] },
    { "id": 5, "tasks": ["5.1", "7.1"] },
    { "id": 6, "tasks": ["7.2", "8.1", "8.2"] },
    { "id": 7, "tasks": ["8.3"] },
    { "id": 8, "tasks": ["9.1", "9.2"] },
    { "id": 9, "tasks": ["10.1", "10.2", "10.3"] }
  ]
}
```

## Tasks

- [x] 1. 数据模型与迁移（Flyway V44）
  - [x] 1.1 新增 Flyway 迁移 `V44__recurring_rule_post_mode.sql`（接续 `V43__budget_reminder.sql`）
    - `ALTER TABLE recurring_rules ADD COLUMN post_mode VARCHAR(16) NOT NULL DEFAULT 'CONFIRM'`
    - 不加 CHECK 约束；不修改 / 不复用既有版本号；不改其它表、不新增指向其它表的外键；不改 `recurring_pending_items` 结构
    - _Requirements: 1.2, 1.3, 6.2, 6.4_

  - [x] 1.2 新增 `PostMode` 枚举与 `RecurringRule.postMode` 字段
    - 新增枚举 `PostMode { CONFIRM, AUTO }`（同 `RuleStatus` / `Frequency` 风格）
    - `RecurringRule` 加 `@Enumerated(EnumType.STRING) @Column(name="post_mode", nullable=false, length=16) private PostMode postMode = PostMode.CONFIRM;` + getter/setter
    - _Requirements: 1.1, 1.2, 1.3_

- [x] 2. 规则入账方式校验（RecurringRuleService 创建 / 编辑）
  - [x] 2.1 创建 / 编辑落 postMode
    - 创建 / 编辑未显式指定 → 默认 `CONFIRM`；显式 `CONFIRM`/`AUTO` 落对应值；非法值 → `RECURRING_POST_MODE_INVALID`（`field=postMode`），拒绝且规则表零变更
    - 编辑 postMode 仅对之后新处理期次生效，不回滚已 `CONFIRMED`（含自动入账）/ `SKIPPED` 期次与历史流水；不改 recurring-transactions 既有校验口径与错误码
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [ ]* 2.2 编写属性测试：入账方式默认与向后兼容、改入账方式只影响之后期次
    - **Property 1: 入账方式默认与向后兼容**（标签 `Feature: recurring-auto-post, Property 1: ...`，≥100 次）
    - **Property 2: 改入账方式只影响之后期次**（标签 `Feature: recurring-auto-post, Property 2: ...`，≥100 次）
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6**

- [x] 3. 单期次自动入账核心（RecurringAutoPoster，REQUIRES_NEW 独立事务）
  - [x] 3.1 实现 `RecurringAutoPoster.autoPost(rule, occurrenceDate)`
    - `@Transactional(REQUIRES_NEW)`：①`saveAndFlush` 写一条 `PENDING` 占位抢唯一键 `(rule_id, occurrence_date)`，撞 `DataIntegrityViolationException` → 返回 `ALREADY_PROCESSED` 静默；②目标 / 金额校验（分类属账本、账户为所有者在账本可用、金额合法）不过 → 保留占位为 `PENDING` 返回 `DEGRADED_TO_PENDING`（不建交易、不改余额）；③走 `TransactionService.create`（记账时间 = `occurrenceDate.atStartOfDay()`，`Asia/Shanghai`）；④占位升 `CONFIRMED` + 回填 `confirmedTransactionId`，返回 `AUTO_POSTED(tx)`
    - 步骤 ③④ 任一失败整体回滚；复用 `RecurringTemplateValidator` + `categoryRepository.findByIdAndLedgerId` + `accountResolver.selectableAccounts`
    - 定义返回值 `AutoPostResult`（`AUTO_POSTED`/`DEGRADED_TO_PENDING`/`ALREADY_PROCESSED` + 可选 tx）
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4_

  - [ ]* 3.2 编写属性测试：自动入账账户守恒且口径一致、幂等、降级
    - **Property 3: AUTO 到期自动入账账户守恒且与手动记账口径一致**（≥100 次，`@SpringBootTest`+H2）
    - **Property 4: 自动入账幂等（构造性 Σ）**（≥100 次；覆盖多次 / 交错触发）
    - **Property 6: 目标失效 / 金额非法降级为待确认**（≥100 次；删分类 / 删账户 / 金额越界）
    - 标签 `Feature: recurring-auto-post, Property {3,4,6}: ...`
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.8, 3.1, 3.2, 3.3, 3.4, 4.3, 6.5**

  - [ ]* 3.3 编写单元测试：自动入账事务原子性回滚
    - 注入 `TransactionService.create` 失败，断言无流水、无余额变动、无 `CONFIRMED`（占位事务整体回滚）
    - _Requirements: 2.3_

- [x] 4. 懒入账改造（RecurringPendingItemService.generateForRule 按 postMode 分流）
  - [x] 4.1 改造 `generateForRule` 分流
    - `AUTO` 规则的到期期次：调用 `autoPoster.autoPost`（入账或降级，均幂等）；`AUTO_POSTED` 则登记待提交后告知（见任务 7）；`CONFIRM` 规则分支完全不变（仍 `generator.generate` 写 `PENDING`）
    - 沿用既有 `lazyGenerate` 失败隔离结构：撞唯一键静默、单规则 `try/catch` 就地隔离仅记 `[RECURRING_AUTOPOST_FAILED]` / `[RECURRING_GEN_FAILED]`，不阻断其余规则与已有项返回，不阻断查询主路径
    - 注入 `RecurringAutoPoster`（同款循环依赖用构造 / `@Lazy` 处理，与既有 `self` 代理风格一致）
    - _Requirements: 2.7, 3.5, 4.1, 4.3_

  - [ ]* 4.2 编写属性测试：生命周期边界、失败隔离
    - **Property 5: 生命周期与结束/开始/暂停边界**（≥100 次；PAUSED / 早于开始 / UNTIL_DATE / COUNT / CONFIRM 维持既有）
    - **Property 7: 自动入账失败就地隔离**（≥100 次；注入非预期异常）
    - 标签 `Feature: recurring-auto-post, Property {5,7}: ...`
    - **Validates: Requirements 2.5, 2.6, 2.7, 3.5, 4.5, 4.6**

  - [ ]* 4.3 编写单元测试：AUTO 与 CONFIRM 分流示例
    - AUTO 规则到期生成 `CONFIRMED`+流水；CONFIRM 规则到期仅生成 `PENDING`（既有行为不变）
    - _Requirements: 2.7_

- [x] 5. 每日定时任务（RecurringAutoPostScheduler，仿 ReminderScheduler）
  - [x] 5.1 实现 `RecurringAutoPostScheduler`
    - `@Scheduled(cron = "0 30 0 * * *", zone = "Asia/Shanghai")`；`today = LocalDate.now(clock)` 不依赖 JVM 默认时区
    - 新增仓库方法 `RecurringRuleRepository.findByStatusAndPostMode(ACTIVE, AUTO)`，扫描**全部账本**的 AUTO 规则
    - 逐规则逐期次调用 `autoPoster.autoPost`（各自 REQUIRES_NEW）；`AUTO_POSTED` 直接调 notifier（事务已提交）；双层 try/catch 就地隔离（期次级 + 规则级），单条失败不拖垮整轮；在记账 / 登录主路径事务边界之外
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 6. 检查点 — 后端核心（校验 / 自动入账 / 双触发）全绿
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. 告知（RecurringAutoPostNotifier，事务边界外）
  - [x] 7.1 实现 `RecurringAutoPostNotifier`
    - 复用 `WeChatClient.sendSubscribeMessage` + 订阅额度体系（同 `RecurringReminderNotifier` 通道）；内容为「已自动记一笔」摘要（金额 / 分类或备注等非敏感字段，不含余额 / 令牌 / 邮箱）
    - 有效额度才发送并消费一次；无额度不发送但入账照常；投递失败 / 微信异常 / 超 5 秒 / 额度耗尽就地捕获仅记 `[RECURRING_AUTOPOST_NOTIFY_FAILED]`，不回滚入账、不改流水 / 余额 / 期次状态 / 幂等键
    - 触发在入账事务提交之后：懒入账路径用 `afterCommit` 同步（或循环外统一发），定时任务路径 autoPost 返回即已提交直接发
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [ ]* 7.2 编写属性测试 + 集成测试：告知隔离
    - **Property 8: 告知隔离**（标签 `Feature: recurring-auto-post, Property 8: ...`，≥100 次；成功 / 失败 / 超时 / 无额度 × 任意次数触发）
    - 集成测试：mock `WeChatClient`，断言入账成功后发送一次、各类故障不阻断入账、无额度不发送、提交后才发
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5**

- [x] 8. 控制器、DTO 与错误码
  - [x] 8.1 `RecurringRuleController` 创建 / 编辑 / 响应 DTO 增加 `postMode` 字段
    - 请求体可选 `postMode`（缺省 `CONFIRM`）；响应体回显 `postMode`；不新增端点、不改其它字段
    - _Requirements: 1.1, 1.2, 6.3_

  - [x] 8.2 新增错误码 `RECURRING_POST_MODE_INVALID`(400)
    - 接入 `GlobalExceptionHandler`，`field=postMode`；复用既有 `NOT_FOUND` / `UNAUTHENTICATED` / `AMOUNT_INVALID` / `RECURRING_ITEM_TARGET_MISSING`
    - _Requirements: 1.4, 6.1_

  - [ ]* 8.3 编写属性测试：账本与用户隔离、可摘除与金额口径
    - **Property 9: 账本与用户隔离、可摘除与金额口径**（标签 `Feature: recurring-auto-post, Property 9: ...`，≥100 次）
    - 覆盖多用户多账本 AUTO/CONFIRM 数据、越权 `NOT_FOUND`、全 CONFIRM 回退既有行为、六组接口契约不变、金额 2 位小数
    - **Validates: Requirements 6.1, 6.3, 6.4, 6.5**

- [x] 9. 前端（miniapp，uni-app / Vue 3）
  - [x] 9.1 `pages/recurringedit/` 新增「入账方式」选择
    - 新建 / 编辑规则加「自动入账 / 待确认」选择，默认「待确认」（与后端一致）；对「自动入账」给出说明文案（到期自动记账并通知；目标失效会转为待确认）；API 封装 `recurring.js` 请求体带 `postMode`
    - _Requirements: 7.1, 7.2_

  - [x] 9.2 待确认列表兼容降级项 + 构建
    - 降级产生的 `PENDING` 项在既有「待确认记账」列表照常显示 / 确认 / 跳过 / 批量，无需特殊处理；不改既有交互与语义；H5 与 mp-weixin 构建通过
    - _Requirements: 7.3, 7.4_

- [ ] 10. 端到端联调、兼容回归与迁移冒烟
  - [ ]* 10.1 端到端全流程集成测试
    - 建 AUTO 规则 → 懒入账 / 定时任务自动入账 → 断言流水 / 余额 / CONFIRMED / 告知；删目标后到期 → 降级 PENDING → 用户确认；双触发对同一期次至多一条流水
    - _Requirements: 2, 3, 4, 5_

  - [ ]* 10.2 兼容回归测试
    - 交易 / 账本 / 分类 / 账户 / 预算 / 报表六组接口引入前后响应字段集与错误码集逐一相同；全部规则视作 `CONFIRM` 时回退为「到期只生成待确认项」的既有行为
    - _Requirements: 6.3, 6.4_

  - [ ]* 10.3 迁移冒烟测试
    - 断言 `V44` 迁移成功、既有迁移脚本未被修改、无新增指向其它表的外键、`post_mode` 默认 `CONFIRM`
    - _Requirements: 6.2_

- [x] 11. 最终检查点 — 全量测试与前端构建通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选测试（单元 / 属性 / 集成），可为快速 MVP 跳过；顶层任务不加 `*`，核心实现任务不可跳过。
- 迁移版本号接续现有最大版本 `V43`，取 `V44`；仅给 `recurring_rules` 加 `post_mode` 列（默认 `CONFIRM`），删除该列即整块摘除（需求 6.2、6.4）。
- 金额一律 `BigDecimal(18,2)`、HALF_UP；自动入账复用 `TransactionService.create`（账户加锁 `findForUpdateByIdAndUserId` + 单事务原子），与手动记账 / 用户确认入账同口径。
- 自动入账幂等由既有唯一约束 `uk_recurring_pending_rule_date` 构造性保证：占位抢键、撞键静默；懒入账与每日定时任务共用 `RecurringAutoPoster.autoPost` 与同一幂等键。
- 告知在自动入账事务提交之后、主路径事务边界之外发送，失败仅记 `[RECURRING_AUTOPOST_NOTIFY_FAILED]` 日志，不影响入账结果。
- 属性测试用 jqwik（见 `.jqwik-database`），每条 Property 单独一个属性测试、≥100 次迭代，标签格式 `Feature: recurring-auto-post, Property {n}: {text}`；涉持久化 / 余额 / 隔离的 Property 3/4/6/7/9 走 `@SpringBootTest` + H2(MODE=MySQL)。
- 测试策略与属性映射见 design.md「Testing Strategy」与「Correctness Properties」Property 1–9。
