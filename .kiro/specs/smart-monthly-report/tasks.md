# Implementation Plan: Smart Monthly Report (智能月报)

## Overview

本计划把智能月报设计（`design.md`）拆解为一系列增量式编码任务。所有任务遵循「纯只读、纯增量」边界：
后端新增一个只读组合器 `MonthlyDigestService`、一个响应 DTO `MonthlyDigestResponse`（复用既有嵌套 record）、
在既有 `ReportController` 上新增单个只读端点 `GET /api/reports/monthly-digest`；miniapp 新增 `api/report.js#monthlyDigest`
与 `pages/report/report.vue` 月报区块 + canvas 海报（静默降级）。不新增数据库表、不新增/改动 Flyway 脚本、
不改动任何既有接口契约。

实现语言沿用仓库既有技术栈：后端 Java 17 + Spring Boot（服务/DTO），测试用 jqwik + `@DataJpaTest` + H2（MODE=MySQL）；
前端 miniapp uni-app / Vue 3（JavaScript）。

每个任务在前一步基础上增量推进，最终把后端与前端串联起来。属性测试对应设计文档 Correctness Properties 的
10 条属性，标注格式为 `Feature: smart-monthly-report, Property {n}: {text}`，并保留 `Validates: Requirements X.Y`。

## Tasks

- [x] 1. 新增月报响应 DTO `MonthlyDigestResponse`（复用既有嵌套 record）
  - 在 `src/main/java/com/damien/youyu/api/dto/` 新建 `MonthlyDigestResponse.java`
  - 顶层字段：`month`、`monthStatus`、`income`、`expense`、`netBalance`、`trend`、`categoryRanking`、`budget`、`largestExpense`、`mostFrugalWeek`
  - `trend` 复用 `RangeReportResponse.DayPoint`；`categoryRanking` 复用 `CategoryReportResponse.CategoryShare`；不改动这些既有 record 的字段集
  - 定义内嵌 record：`BudgetDigest`（`hasBudget/totalBudget/spent/remaining/usedPercent/status/forecast`，`forecast` 复用 `BudgetOverviewResponse.BudgetHealth`）、`LargestExpense`（`amount/categoryName/date/note`）、`FrugalWeek`（`startDate/endDate/expense`）
  - 补充 Javadoc 说明空/缺省语义（空月零值、未设预算字段为 null）
  - _Requirements: 1.1, 5.3, 6.2, 7.3, 11.3_

- [x] 2. 实现月报组合器 `MonthlyDigestService`
  - [x] 2.1 搭建服务骨架与月状态/边界计算
    - 在 `src/main/java/com/damien/youyu/service/` 新建 `MonthlyDigestService.java`，标注 `@Service`
    - 注入 `ReportService`、`BudgetService`、`TransactionRepository`、`CategoryRepository`、`Clock`
    - `digest(Long ledgerId, YearMonth month)` 标注 `@Transactional(readOnly = true)`
    - 计算月状态：`month.isBefore(YearMonth.now(clock)) ? "final" : "partial"`
    - 计算结束边界：`final` → 月末日；`partial` → `LocalDate.now(clock)`
    - _Requirements: 1.1, 1.3, 1.4, 11.1_

  - [x] 2.2 组合收支结余、消费趋势、分类排行、预算情况
    - 收支：调用 `reportService.monthlyReport(ledgerId, month)` 抽取 income/expense，`netBalance = income - expense`
    - 趋势：调用 `reportService.rangeReport(ledgerId, monthStart, endBoundary)`，对有计入交易的月做稠密化（`[monthStart, endBoundary]` 逐日补 0.00、升序、无缺日），空月返回空列表
    - 分类排行：调用 `reportService.categoryReport(ledgerId, monthStart, endBoundary)`，对名称为空的分类项套用回退名 `"已删除分类"`
    - 预算：调用 `budgetService.overview(ledgerId, month)` 抽取字段；`forecast`（health）仅在 overview 返回非空时携带
    - _Requirements: 1.6, 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 5.1, 5.2, 5.3, 5.4, 5.5, 11.5_

  - [x] 2.3 实现最大单笔消费与最省钱的一周（月内交易内存计算）
    - 一次性读取月内交易 `transactionRepository.findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(ledgerId, monthStart, nextMonthStart)`，过滤 `type=expense`
    - 最大单笔：按 amount 取最大，tie-break `occurred_at` 更晚优先、再 `id` 更大优先；携带金额/分类名（回退名同 2.2）/日期/备注（缺省空串）
    - 最省钱的一周：自 1 日起每 7 自然日一段（`k` 段覆盖 `7k+1..7k+7`），仅 `7k+7 ≤ 当月天数` 的完整段参评；`partial` 追加要求 `7k+7 ≤ 当前日`；取支出合计最低段，tie-break 起始日期更早优先
    - 无支出 → `largestExpense = null`；无完整周分段 → `mostFrugalWeek = null`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 2.4 组装 `MonthlyDigestResponse` 并处理空数据语义
    - 将 2.1–2.3 结果打包为 `MonthlyDigestResponse`
    - 空月：income/expense/netBalance 为 `0.00`、trend 空列表、categoryRanking 空列表、largestExpense/mostFrugalWeek 为 null，且不抛错
    - _Requirements: 1.7, 3.6, 4.6, 6.4, 7.5_

  - [x]* 2.5 编写属性测试 Property 1（月报打包完整性与月状态正确）
    - **Property 1: 月报打包完整性与月状态正确**
    - **Validates: Requirements 1.1, 1.3, 1.4, 2.5, 9.1**
    - 新建 `MonthlyDigestServicePropertyTest`（`@DataJpaTest` + H2 + 真实 Repository），随机化目标月与固定注入 `Clock`（Asia/Shanghai）覆盖 partial/final；≥100 次迭代；Javadoc 标注 `Feature: smart-monthly-report, Property 1: ...`

  - [x]* 2.6 编写属性测试 Property 2（收支结余同口径且结余为差，模型对照）
    - **Property 2: 收支结余同口径且结余为差**
    - **Validates: Requirements 1.6, 2.1, 2.2, 2.3, 2.4, 11.5**
    - 以 `ReportService.monthlyReport` 为参照实现，断言 digest 分量逐值相等；≥100 次迭代

  - [x]* 2.7 编写属性测试 Property 4（消费趋势稠密、升序、双值且窗口正确）
    - **Property 4: 消费趋势稠密、升序、双值且窗口正确**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**
    - 生成至少含一笔计入交易的月，断言升序、无缺日、缺日为 0.00、窗口按月状态（final=月末/partial=当前日）；≥100 次迭代

  - [x]* 2.8 编写属性测试 Property 5（分类排行同口径、确定性与占比守恒，模型对照）
    - **Property 5: 分类排行同口径、确定性与占比守恒**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 11.5**
    - 以 `ReportService.categoryReport` 为参照；断言金额降序 + id 升序 tie-break、缺失分类回退名不丢项、占比合计 100.00；≥100 次迭代

  - [x]* 2.9 编写属性测试 Property 6（预算情况同口径且前瞻按月状态给出，模型对照）
    - **Property 6: 预算情况同口径且前瞻按月状态给出**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 11.5**
    - 以 `BudgetService.overview` 为参照，覆盖已设/未设预算；断言字段逐值一致，`forecast` 非空 ⟺ partial 且已设预算；≥100 次迭代

  - [x]* 2.10 编写属性测试 Property 7（最大单笔消费选择与确定性 tie-break）
    - **Property 7: 最大单笔消费选择与确定性 tie-break**
    - **Validates: Requirements 6.1, 6.2, 6.3**
    - 随机生成含并列最大金额的支出集；断言选中最大金额且 tie-break（occurred_at 晚 → id 大）唯一；≥100 次迭代

  - [x]* 2.11 编写属性测试 Property 8（最省钱的一周分段、选择与窗口）
    - **Property 8: 最省钱的一周分段、选择与窗口**
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.6**
    - 覆盖 2 月恰 4 段、7 月末 3 日不成段、partial 当前日不足 7 天、并列最低取起始更早；≥100 次迭代

  - [x]* 2.12 编写属性测试 Property 9（空数据优雅返回）
    - **Property 9: 空数据优雅返回**
    - **Validates: Requirements 1.7, 3.6, 4.6, 6.4, 7.5**
    - 空月/无支出/无完整周分段均不抛错并返回零值/空列表/null；≥100 次迭代

- [x] 3. 在 `ReportController` 新增只读端点 `GET /api/reports/monthly-digest`
  - 注入 `MonthlyDigestService`（构造器新增参数）
  - 新增 `@GetMapping("/monthly-digest")` 方法，`month` 参数可选
  - `ledgerId = currentLedger.requireLedgerId()`（未认证/账本不可访问在此抛既有错误）
  - `month` 缺省 → `YearMonth.now(clock)`；否则复用既有 `parseMonth(month, "month")`（非法格式抛 `REPORT_PARAM_INVALID`）
  - 返回 `ResponseEntity.ok(digestService.digest(ledgerId, ym))`
  - 不新增任何错误码、不改动既有端点
  - _Requirements: 1.2, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_

- [x]* 4. 编写控制器契约测试（`ReportControllerTest`，MockMvc）
  - 缺省 `month` 取当前月（需求 1.2）
  - 无/坏令牌 → `UNAUTHENTICATED`，响应无月报字段（需求 9.2）
  - 越权 `X-Ledger-Id` → `LEDGER_NOT_ACCESSIBLE`（需求 9.3）
  - 非法 `month` → `REPORT_PARAM_INVALID`（需求 9.4）
  - _Requirements: 1.2, 9.2, 9.3, 9.4_

- [x]* 5. 编写服务层边界单元测试（`MonthlyDigestServiceTest`，`@DataJpaTest`）
  - 具体示例：partial/final 月状态、稠密趋势填零、最大单笔 tie-break、周分段（2 月恰 4 段 / 7 月末 3 日不成段 / partial 当前日不足 7 天返回 null）、未设预算与已设预算前瞻、已删除分类回退名
  - 账本隔离示例（Property 3 的定点验证：A 账本月报不含 B 账本交易）
  - _Requirements: 1.5, 1.9, 3.4, 4.5, 5.3, 5.4, 6.3, 7.1, 7.5, 9.5_

- [x] 6. Checkpoint - 确保后端所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. miniapp 新增月报 API 方法
  - 在 `miniapp/src/api/report.js` 新增 `monthlyDigest(month)`，`http.get('/reports/monthly-digest?month=' + month)`
  - 补充 JSDoc 说明返回九模块数据包结构
  - 沿用 `utils/request.js` 网络层（自动带 Authorization 与 X-Ledger-Id；401 清 token；LEDGER_NOT_ACCESSIBLE 清账本重试）
  - _Requirements: 9.1, 10.3_

- [x] 8. `report.vue` 月报数据加载与静默降级
  - 在既有 `load()` 中：当 `!ledgerStore.isAll` 且已登录（有 token）时并行请求 `monthlyDigest(month.value)`
  - 用独立响应式状态 `digest`/`digestVisible`，与其它报表相互独立
  - 5000ms 超时（`Promise.race` + 定时器）；请求失败或超时 → `digestVisible=false` 静默隐藏，不弹阻断性错误
  - 未登录不发起请求也不展示；全部账本聚合视图（`ledgerStore.isAll`）不展示月报区块
  - 切换账本或目标月时重新请求并刷新月报区块
  - _Requirements: 1.8, 1.9, 10.1, 10.2, 10.3, 10.4_

- [x] 9. `report.vue` 月报区块渲染
  - 展示目标月标识 `YYYY-MM` 与月状态徽标（进行中/已完结）
  - 展示收入/支出/结余、趋势迷你图、分类排行 Top、预算情况、最大单笔、最省钱的一周
  - 空模块按空/缺省语义渲染（不报错）
  - _Requirements: 2.5, 3.3, 4.2, 5.1, 6.2, 7.3_

- [x] 10. `report.vue` 月报配图（canvas 海报）生成、保存与分享
  - 月报数据加载成功后展示「生成月报配图」入口
  - 点击后用 `<canvas>` + `uni.createCanvasContext` / `uni.canvasToTempFilePath` 渲染卡片，至少含目标月、收入、支出、结余
  - 卡片只绘制来自 `digest` 的当前账本字段，不含任何邮箱/令牌/其它账本数据
  - `uni.saveImageToPhotosAlbum` 保存 / `uni.showShareMenu` 分享
  - 相册授权被拒 → `uni.showModal` 提示去授权，页面不进入错误态
  - 出图失败 → `uni.showToast` 提示失败，月报数据与其余内容保持正常展示
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

- [x]* 11. 编写属性测试 Property 3（账本隔离）与 Property 10（纯只读不写库）
  - **Property 3: 账本隔离**
  - **Validates: Requirements 1.5, 9.5**
  - 两账本 A/B 随机交易集，断言 A 的月报与「仅存在 A 交易」时逐值相同；≥100 次迭代
  - **Property 10: 纯只读不写库**
  - **Validates: Requirements 11.1**
  - 调用前后对 `transactions/categories/budgets/category_budgets` 及全表清单做行数与内容快照，断言完全一致；≥100 次迭代

- [x]* 12. 编写前端降级与海报隔离校验（miniapp，组件级 / mock）
  - 月报请求失败或 5000ms 超时时静默隐藏区块、其余报表取值不变；未登录不请求；全部账本视图不展示
  - 海报仅绘制当前账本字段（含目标月、收入、支出、结余），不含邮箱/令牌/其它账本数据
  - _Requirements: 8.3, 10.1, 10.2, 10.3, 10.4_

- [x] 13. Final Checkpoint - 确保全部测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选（测试相关），可为更快的 MVP 跳过；核心实现任务从不标记为可选。
- 每个任务引用具体需求子条款以保证可追溯性。
- 属性测试对应 `design.md` 的 Correctness Properties（Property 1–10），Property 2/5/6 采用模型对照（以既有 `ReportService`/`BudgetService` 为参照实现）。
- 后端属性测试沿用仓库既有 jqwik + `@DataJpaTest` + H2（MODE=MySQL）+ 真实 Repository 范式，不使用 mock；固定注入 `Clock`（Asia/Shanghai）获得确定性。
- 本 spec 纯只读、纯增量：不新增数据库表、不新增/改动 Flyway 脚本、不改动任何既有接口契约。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3"] },
    { "id": 3, "tasks": ["2.4"] },
    { "id": 4, "tasks": ["2.5", "2.6", "2.7", "2.8", "2.9", "2.10", "2.11", "2.12", "3"] },
    { "id": 5, "tasks": ["4", "5", "7", "11"] },
    { "id": 6, "tasks": ["8"] },
    { "id": 7, "tasks": ["9"] },
    { "id": 8, "tasks": ["10"] },
    { "id": 9, "tasks": ["12"] }
  ]
}
```
