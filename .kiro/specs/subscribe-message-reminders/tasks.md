# Implementation Plan: 预算提醒（Budget_Reminder_System）

## Overview

按设计的两条链路增量落地。先建迁移脚本与领域实体 / 仓库（数据基座），再补齐错误码与文案 / 微信通道等无状态基础件，
随后完成链路 A（同步接口：状态查询 / 偏好更新 / 授权上报），再完成链路 B（afterCommit 评估 → 收件人筛选 → 去重 → 单次发送尝试），
接着做注销集成与 reset-db 清库，最后接上 miniapp 提醒设置页的预算提醒区块。属性测试（jqwik，≥100 次迭代，微信一律 mock）
紧贴对应实现放置，逐条对齐设计的 23 条正确性属性；每个任务标注其校验的需求条款。

实现语言：**Java（Spring Boot）**，与设计文档一致。包根 `com.damien.youyu`；miniapp 为 uni-app / Vue 3。

## Tasks

- [ ] 1. 数据基座：迁移脚本与领域实体 / 仓库
  - [ ] 1.1 编写迁移脚本 `V43__budget_reminder.sql`
    - 在 `src/main/resources/db/migration` 新建 `V43__budget_reminder.sql`，不改任何既有迁移脚本
    - 建 `budget_reminder_settings`（恰 5 列：`user_id` PK / `enabled` TINYINT(1) 缺省 1 / `remaining` INT 缺省 0 / `created_at` / `updated_at`），具名 CHECK `ck_budget_reminder_settings_remaining (remaining >= 0)`
    - 建 `budget_reminder_send_logs`（恰 9 列：`id` 自增 PK / `user_id` / `ledger_id` / `budget_month` VARCHAR(7) / `scope_ref` BIGINT / `level` VARCHAR(8) / `result` VARCHAR(24) / `wx_errcode` INT NULL / `created_at`）
    - 具名唯一约束 `uk_budget_reminder_send_logs_scope (user_id, ledger_id, budget_month, scope_ref, level)`；具名 CHECK `ck_budget_reminder_send_logs_level (level IN ('WARN','OVER'))`
    - 两表 InnoDB + utf8mb4 + utf8mb4_unicode_ci，每表每列写非空中文注释；不建任何外键；不用窗口函数 / CONVERT_TZ / 存储过程 / 触发器
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.10_

  - [ ] 1.2 定义 `BudgetReminderSetting` 与 `BudgetReminderSendLog` 实体
    - 在 `domain` 包创建两个 JPA 实体，字段与列一一对应（`BudgetReminderSetting.userId` 为 `@Id` 无 `@GeneratedValue`；`scopeRef` 为 long，`wxErrcode` 可空）
    - 映射与迁移列名 / 类型严格对齐，保证 `ddl-auto=validate` 可通过
    - _Requirements: 8.2, 8.3, 8.12_

  - [ ] 1.3 创建 `BudgetReminderSettingRepository` 与原子额度更新方法
    - 镜像 `ReminderQuotaRepository`：`addCapped(userId, delta, now)` 走 `INSERT ... ON DUPLICATE KEY UPDATE remaining = LEAST(remaining + delta, 50)`；`decrementFloorZero(userId)` 走 `remaining = remaining - 1 WHERE remaining > 0`；`zeroOut(userId)` 归零
    - 提供按 `userId` 查询设置的方法（供 getStatus / 收件人筛选复用）
    - _Requirements: 6.3, 6.5, 4.2, 4.6_

  - [ ] 1.4 创建 `BudgetReminderSendLogRepository`
    - 提供按唯一键 `(userId, ledgerId, budgetMonth, scopeRef, level)` 存在性查询（幂等预检）、按 `(userId, ledgerId, budgetMonth, scopeRef, 'OVER')` 存在性查询（超支已推判定）、按 `userId` 删除（注销用）
    - _Requirements: 3.2, 3.3, 8.8_

- [ ] 2. 无状态基础件：错误码、文案、微信独立模板通道
  - [ ] 2.1 新增两个本域错误码
    - 在 `ApiException` / `error` 包以工厂方法加入 `BUDGET_REMINDER_PREF_INVALID`（field=`enabled`）与 `BUDGET_REMINDER_GRANT_INVALID`（field=`grantedCount`），HTTP 400，`message` 为 ≤100 字符中文且不含 id / 邮箱 / 令牌
    - 复用既有 `UNAUTHENTICATED`，不新增第三个错误码、不重命名任何既有码；确认经 `GlobalExceptionHandler` 输出 `{code, message, field}`
    - _Requirements: 7.5, 7.6, 9.3_

  - [ ] 2.2 实现 `BudgetReminderMessageResolver.pick(level, scopeRef, categoryNameOrNull)`
    - 范围表述：`scopeRef == 0` → 「月度总预算」；否则用分类当前名称；名称为空 / 不可得 → 「该分类」占位
    - 级别文案：`OVER` → 「{范围}本月已超支」；`WARN` → 「{范围}本月已接近预算上限」；按级别恰好选一条
    - 文案长度落入微信模板字段限制内，不含邮箱 / 令牌 / 他人信息
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [ ]* 2.3 文案属性测试
    - **Property 10: 文案与级别一一对应且体现范围要素** — **Validates: Requirements 5.1, 5.2, 5.3, 5.6**
    - **Property 11: 分类名称缺失的占位健壮性** — **Validates: Requirements 5.4**
    - **Property 12: 文案长度与隐私约束** — **Validates: Requirements 5.5**
    - jqwik ≥100 次迭代，生成 (level, scope, 分类名含 null/超长)；每个属性单独一个 `@Property`，注释标注 `// Feature: subscribe-message-reminders, Property N: ...`

  - [ ] 2.4 扩展 `WeChatClient` 支持独立预算提醒模板
    - 新增 `int sendBudgetSubscribeMessage(accessToken, openid, message)`（或带模板参数的重载并保留旧签名），使用新配置项 `app.wechat.subscribe.budget-template-id`
    - 复用同一 `WeChatAccessTokenProvider` 凭证网关与 40001 强制刷新重试一次逻辑；本地失败（模板未配置 / 凭证空 / 网络异常 / 响应不可解析）返回哨兵 `ERRCODE_LOCAL_FAILURE(-1)`，本方法不抛异常
    - 在配置类 / `application.yml` 增加 `app.wechat.subscribe.budget-template-id` 配置项（可空）
    - _Requirements: 4.7, 4.8_

- [ ] 3. 链路 A：同步接口（偏好 / 额度）
  - [ ] 3.1 实现 `BudgetReminderService`
    - `getStatus(userId)`：读设置，无记录返回缺省 `{enabled=true, remainingQuota=0}`，不建行
    - `updatePreference(userId, enabledRaw)`：`enabled` 为 null / 不可解析布尔 → 抛 `BUDGET_REMINDER_PREF_INVALID` 且偏好不变；合法则 UPSERT 偏好、置 `updated_at` 为服务端当前时刻，返回最新 `{enabled, remainingQuota}`
    - `grantQuota(userId, grantedCountRaw)`：解析 + 校验 1..5，非整数 / <1 / >5 → `BUDGET_REMINDER_GRANT_INVALID` 且额度不变；合法走 `addCapped` 原子上限累加（封顶 50），返回增加后的 remainingQuota
    - _Requirements: 1.2, 1.4, 1.5, 6.2, 6.3, 6.4_

  - [ ]* 3.2 `BudgetReminderService` 属性测试
    - **Property 13: 偏好更新往返** — **Validates: Requirements 1.4**
    - **Property 14: 偏好非法输入拒绝且零副作用** — **Validates: Requirements 1.5**
    - **Property 15: 授权额度净和（带 [0,50] 钳制）** — **Validates: Requirements 6.2, 6.3, 6.5**
    - **Property 16: 授权非法输入拒绝且零副作用** — **Validates: Requirements 6.4**
    - jqwik ≥100 次迭代，生成随机布尔 / 非法原文 / grantedCount 越界 + 授权/扣减操作序列（含 0/1/50 边界与并发净和）

  - [ ] 3.3 实现 `BudgetReminderController`（`/api/budget-reminders`）
    - `GET /api/budget-reminders` → 状态 `{enabled, remainingQuota}`；`PUT /api/budget-reminders/preference` 接收 `{enabled}` 原文；`POST /api/budget-reminders/quota:grant` 接收 `{grantedCount}` 原文
    - 每端点首步 `requireExistingUserId()`：令牌无效 / 用户已注销 → `UNAUTHENTICATED`（先于任何字段校验），零副作用，响应不含任何偏好 / 额度字段值
    - 数据归属只认令牌用户 id，忽略任何指定目标身份的查询参数 / 路径参数 / 请求体字段 / 自定义头；三端点均 `noLedger`、不要求 `X-Ledger-Id`
    - _Requirements: 1.1, 1.3, 6.1, 7.1, 7.2, 7.3, 7.4_

  - [ ]* 3.4 `BudgetReminderController` 属性测试
    - **Property 18: 鉴权优先且零副作用** — **Validates: Requirements 7.2, 7.6**
    - **Property 19: 归属只认令牌用户、忽略目标身份与账本头** — **Validates: Requirements 7.3, 7.4**
    - **Property 20: 本域错误体形态一致** — **Validates: Requirements 7.5**
    - jqwik ≥100 次迭代，生成随机无效令牌 / 伪造目标身份 / 触发错误码的输入

  - [ ]* 3.5 链路 A 接口形态与默认值单元测试
    - EXAMPLE 单测：状态响应恰含 `{enabled, remainingQuota}`、无记录默认 `{true, 0}`、偏好更新返回最新值、授权上限累加至 50（需求 1.1、1.2、6.1）
    - _Requirements: 1.1, 1.2, 1.3, 6.1_

- [ ] 4. Checkpoint - 链路 A 与基础件通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 5. 链路 B：单次发送尝试（DispatchService）
  - [ ] 5.1 实现 `BudgetReminderDispatchService.dispatch(...)`
    - `@Transactional`：先幂等预检唯一键已有记录 → 直接返回；调 `MessageResolver.pick` 选文案
    - `remaining <= 0` → 写 `SKIPPED_NO_QUOTA`、不发、不扣额度；`openid` 空 → 写 `SKIPPED_NO_OPENID`、不发、不扣、不报错
    - 发微信：`WeChatAccessTokenProvider.getToken()` → `sendBudgetSubscribeMessage`；`errcode==0` → 写 `SENT`（撞唯一键则作废不扣额度）后 `decrementFloorZero`；`errcode==43101` → 写 `FAILED` 记 errcode 且额度归零；其它非零 / 本地降级 / 抛异常 → 写 `FAILED`、额度不动、不外抛，记含 userId 不含金额 / 邮箱 / 令牌的告警日志
    - 写记录撞唯一键 → 捕 `DataIntegrityViolationException` 静默放弃，不重复调微信、不报错
    - _Requirements: 3.4, 4.2, 4.3, 4.4, 4.5, 4.6, 4.8_

  - [ ]* 5.2 `DispatchService` 发送状态机属性测试
    - **Property 6: 发送尝试的结果与额度变化状态机** — **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 4.6, 4.8**
    - jqwik ≥100 次迭代，随机 remaining（0/1/50 边界）/ openid（空/非空）/ 模板是否配置 / errcode（0/43101/其它非零）/ 抛异常；`WeChatClient` 与 `WeChatAccessTokenProvider` 一律 mock；断言 remaining 恒落 [0,50]

- [ ] 6. 链路 B：评估 + 收件人筛选 + 去重（EvaluationService）
  - [ ] 6.1 实现 `BudgetReminderEvaluationService.evaluate(ledgerId, occurredMonth)`（求范围级别）
    - `@Transactional(REQUIRES_NEW)` 独立事务；用注入的 `Clock`（`Asia/Shanghai`）判定当前月与发生月，不依赖 JVM / DB / OS 默认时区
    - 月份闸门：`occurredMonth != 当前月` → 直接返回；账本类型闸门：AA 账本 → 直接返回，仅个人 / 协作账本继续
    - 调 `budgetService.overview(ledgerId, 当前月)`：总预算 `hasTotalBudget && status ∈ {WARN,OVER}` → `scopeRef=0`；每个 `CategoryBudgetItem.status ∈ {WARN,OVER}` → `scopeRef=categoryId`；未设 / <=0 预算不出现（OVER 优先 WARN 天然由 status 保证）
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [ ]* 6.2 评估级别与范围属性测试
    - **Property 1: 级别判定与 BudgetService 一致且超支优先** — **Validates: Requirements 2.4, 2.6**
    - **Property 2: 未设或非正预算不产生级别** — **Validates: Requirements 2.5**
    - **Property 3: 评估范围恰为已设范围集合** — **Validates: Requirements 2.1**
    - **Property 4: AA 账本与非当前月零发送尝试** — **Validates: Requirements 2.2, 2.3**
    - jqwik ≥100 次迭代，生成随机 (spent, budget) 在 80%/100% 阈值附近、随机预算配置、随机账本类型与发生月

  - [ ] 6.3 实现收件人筛选
    - 该账本 `ledger_members`（只读 `LedgerMemberRepository`）∩「`budget_reminder_settings.enabled` 为真（无记录视真）且 `users.wx_openid` 非空且 `remaining > 0」`；每名收件人恰生成一次发送尝试
    - _Requirements: 1.6, 4.1_

  - [ ]* 6.4 收件人筛选属性测试
    - **Property 5: 收件人恰为三谓词交集（含偏好为假被排除）** — **Validates: Requirements 1.6, 4.1**
    - jqwik ≥100 次迭代，随机成员集合及各成员 (enabled, openid, remaining)

  - [ ] 6.5 实现逐 (收件人 × 范围) 去重派发
    - `OVER`：该收件人该账本该月该范围已有任一 `OVER` 记录 → 跳过；否则派发 `OVER`
    - `WARN`：同范围同月已有 `OVER` 记录 → 跳过（不补预警）；已有该 `WARN` 记录 → 跳过；否则派发 `WARN`
    - 调用 `DispatchService.dispatch`，每项独立不互相影响
    - _Requirements: 3.1, 3.2, 3.3, 3.5_

  - [ ]* 6.6 去重与跨月属性测试
    - **Property 7: 同键至多一条 SENT 且已存在即短路** — **Validates: Requirements 3.1, 3.2**
    - **Property 8: 超支已推则不补预警** — **Validates: Requirements 3.3**
    - **Property 9: 跨自然月独立计次** — **Validates: Requirements 3.5**
    - jqwik ≥100 次迭代，生成随机重复触发序列与跨月边界序列；微信 mock

- [ ] 7. 链路 B：触发器接入交易写路径
  - [ ] 7.1 实现 `BudgetReminderTrigger.requestEvaluation(ledgerId, occurredMonth)`
    - 照抄 `GrowthSettlementTrigger` afterCommit 范式：同一事务只注册一次回调、待评估 `(ledgerId, occurredMonth)` 去重合并为一轮、只携带不可变值、afterCommit 内逐项 try-catch 调 `evaluationService.evaluate`，异常绝不穿出回调
    - _Requirements: 2.1, 2.8_

  - [ ] 7.2 在 `TransactionService` create/update/delete/restore 成功路径接入触发
    - 仅当交易属账本（`ledgerId != null`）时调 `requestEvaluation`，传发生月的 `YearMonth`；不改交易接口的响应字段集 / 取值 / 状态码 / 错误码
    - _Requirements: 2.1, 2.8, 9.4_

  - [ ]* 7.3 主路径隔离属性测试
    - **Property 21: 主路径隔离不变量** — **Validates: Requirements 2.8, 9.4**
    - jqwik ≥100 次迭代，在评估 / 微信调用 / 额度耗尽 / 模板未配置处注入失败（抛异常 / 非零 errcode），断言交易接口响应逐字节不变、无异常穿回；微信 mock

  - [ ]* 7.4 既有表只读不变量属性测试
    - **Property 22: 既有表只读不变量** — **Validates: Requirements 9.1**
    - jqwik ≥100 次迭代，接口调用 / 评估执行前后对 `budgets`/`category_budgets`/`transactions`/`ledgers`/`ledger_members`/`categories`/`users.wx_openid` 快照逐项比对不变

- [ ] 8. Checkpoint - 链路 B 端到端通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. 注销集成与清库脚本
  - [ ] 9.1 在 `AccountDeletionService` 追加两表删除
    - 在既有单注销事务内、删 `users` 行之前，按 `user_id` 先删 `budget_reminder_send_logs`、再删 `budget_reminder_settings`；任一步失败整个事务回滚，经既有错误码返回失败；不改注销响应字段集 / 状态码 / 错误码
    - _Requirements: 8.8, 8.9_

  - [ ]* 9.2 注销级联删除属性测试
    - **Property 23: 注销级联删除且注销响应不变** — **Validates: Requirements 8.8**
    - jqwik ≥100 次迭代，对两表有任意行的用户注销后断言两表行数为 0 且注销响应不变

  - [ ] 9.3 在 `deploy/reset-db.sql` 追加清空两表
    - 在 `SET FOREIGN_KEY_CHECKS = 0/1` 之间、`TRUNCATE TABLE users` 之前清空 `budget_reminder_settings` 与 `budget_reminder_send_logs`；保留表结构与 `flyway_schema_history`
    - _Requirements: 8.11_

  - [ ]* 9.4 两套提醒互不影响与迁移 / 兼容冒烟测试
    - **Property 17: 两套提醒额度与记录互不影响** — **Validates: Requirements 6.6, 9.6**
    - SMOKE：迁移在 H2 `MODE=MySQL` 与 MySQL 均成功、约束名 / 引擎 / 字符集 / 注释 / 无外键校验、`ddl-auto=validate` 启动、错误码集合仅增两项、发送路径经 `WeChatAccessTokenProvider` 取凭证（需求 8.1~8.7、8.10~8.12、9.3、4.7）
    - INTEGRATION：删两表全部行后既有记账 / 预算 / 登录 / 注销 / 成长 / 成就 / 连续记账 / custom-reminder 代表性请求响应不变（需求 9.2、9.5）
    - _Requirements: 6.6, 9.6, 9.2_

- [ ] 10. miniapp：提醒设置页预算提醒区块
  - [ ] 10.1 新增 `miniapp/src/api/budgetReminder.js`
    - `fetchBudgetReminderStatus()` / `updateBudgetReminderPreference(enabled)` / `grantBudgetReminderQuota(count)`，全部 `noLedger:true`，复用既有 `http` 封装
    - _Requirements: 10.9_

  - [ ] 10.2 在 `pages/reminder/reminder.vue` 新增「预算提醒」区块
    - 与既有记账提醒区块并列、不改其行为；展示预算提醒开关 `enabled` 与剩余订阅次数；不展示任何金额 / 账本名 / 邮箱 / 邀请码
    - onShow：已登录则请求一次状态，返回前占位、返回后渲染；未登录不发请求、展示登录入口
    - 切换开关调更新偏好、成功后就地更新；授权入口调 `wx.requestSubscribeMessage`（新增 `WX_BUDGET_REMINDER_TEMPLATE_ID`），点「允许」后上报；拒绝 / 失败不上报、展示未授权与再授权入口、不进入错误态；剩余为 0 展示再授权引导文案
    - 复用 `withTimeout` 3000ms 超时守卫 + seq 忽略迟到结果；任一请求出错或超时切失败态 + 重试入口、自动重试 0 次、保留其余已加载内容
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_

  - [ ]* 10.3 miniapp 区块渲染与交互单元测试
    - EXAMPLE 单测（mock `wx` 与假计时器）：占位 → 渲染、开关切换、授权成功上报 / 拒绝不上报、剩余为 0 引导、超时失败态 + 重试、未登录不发请求
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_

- [ ] 11. Final checkpoint - 全量测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标 `*` 的子任务为可选（单元 / 属性 / 集成 / 冒烟测试），可为快速 MVP 跳过；核心实现任务绝不标可选。
- 每个任务引用其对应的需求条款，便于追溯。
- 属性测试逐条对齐设计的 23 条正确性属性，每条属性单独一个 jqwik `@Property`（≥100 次迭代），并以
  `// Feature: subscribe-message-reminders, Property N: ...` 注释标注；微信调用（`WeChatClient` / `WeChatAccessTokenProvider`）一律 mock。
- 链路 B 全程就地隔离故障：afterCommit try-catch、评估独立事务、发送写库整体兜异常，绝不阻断记账 / 登录 / 注销 / 结算主路径。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.2", "2.4"] },
    { "id": 1, "tasks": ["1.2", "2.3"] },
    { "id": 2, "tasks": ["1.3", "1.4"] },
    { "id": 3, "tasks": ["3.1", "5.1"] },
    { "id": 4, "tasks": ["3.2", "3.3", "5.2", "6.1"] },
    { "id": 5, "tasks": ["3.4", "3.5", "6.2", "6.3"] },
    { "id": 6, "tasks": ["6.4", "6.5"] },
    { "id": 7, "tasks": ["6.6", "7.1"] },
    { "id": 8, "tasks": ["7.2", "9.1", "10.1"] },
    { "id": 9, "tasks": ["7.3", "7.4", "9.2", "9.3", "10.2"] },
    { "id": 10, "tasks": ["9.4", "10.3"] }
  ]
}
```
