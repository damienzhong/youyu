# Implementation Plan: 分享卡片（Share Card）

## Overview

本计划把分享卡片设计（`design.md`）拆解为一系列增量式编码任务。所有任务严守「纯只读、纯增量」边界：
后端新增一组只读响应 DTO（`ShareCardResponse` + 内嵌 `ShareCardCore`）、两个内部只读辅助类型
（`ShareCardType` 六值枚举 + `ShareCardQuery` 周期/标识载体）、一个可配置品牌名/Logo 比例载体
`ShareCardProperties`、一个纯函数中文文案渲染组件 `ShareCardNarrator`、一个只读组合器
`ShareCardService`，并**新增独立控制器** `ShareCardController` 承载单个只读端点 `GET /api/share-cards`；
miniapp 新增 `api/shareCard.js`、`utils/shareCard.js` 纯逻辑模块与 `pages/share/share.vue` 卡片渲染页
（canvas 出图、保存到相册、转发分享、静默降级）。**不新增数据库表、不新增/改动 Flyway 脚本、不新增任何
错误码、不改动任何既有接口契约、不新增任何 repository 查询**——6 类卡片的原始指标全部复用既有
`MonthlyDigestService.digest`、`ReportService.trendReport/categoryReport/monthlyReport`、
`BudgetService.overview` 与成长域**只读判定件**（`StreakJudgment`/`StreakMilestones`/`GrowthLevelCurve`/
`GrowthBadgeCatalog`/`AchievementSnapshotService`）。

**严格只读（核心约束）**：连续/成就/成长的既有「概览类 GET」（`/api/streak`、`/api/achievements`、
`/api/growth`）是**写入型 GET**——它们在返回前会主动触发一次成长结算（可能写 `growth_events` /
`user_growth`）。分享卡片为满足需求 13.1「写语句数量为 0」，**绝不复用这些会触发结算的 overview 方法**，
而是直接复用它们的只读判定件与只读仓库读取，取到的是与源子系统 overview「先结算后读」同口径同值的**已持久化
状态**。相关服务任务会显式点明这一约束。

实现语言沿用仓库既有技术栈：后端 Java 17 + Spring Boot（控制器 / 服务 / DTO / `@ConfigurationProperties`），
测试用 jqwik + `@DataJpaTest` + H2（`MODE=MySQL`）+ 真实 Repository + 固定注入 `Asia/Shanghai` 的 `Clock`，
以真实 `MonthlyDigestService`/`ReportService`/`BudgetService` 与成长域只读件做模型对照；前端 miniapp
uni-app / Vue 3（JavaScript），测试用 vitest。

每个任务在前一步基础上增量推进，最终把后端与前端串联起来。属性测试对应设计文档 Correctness Properties 的
17 条属性，标注格式为 `Feature: share-card, Property {n}: {text}`，并保留 `Validates: Requirements X.Y`。

## Tasks

- [x] 1. 新增分享卡片响应 DTO（`ShareCardResponse` + 内嵌 `ShareCardCore`）
  - 在 `src/main/java/com/damien/youyu/api/dto/` 新建 `ShareCardResponse.java`（`record`）
  - 顶层字段：`cardType`、`available`、`unavailableReason`、`nickname`、`avatarSeed`、`label`、`narrative`、`brandName`、`core`（`ShareCardCore`）
  - 内嵌 `record ShareCardCore`，承载 6 类卡片异构、未用字段以 null 表达的字段：`milestone`/`currentStreakDays`/`maxStreakDays`、`month`/`monthStatus`/`income`/`expense`/`balance`/`topCategoryName`/`topCategoryPercent`、`year`/`yearStatus`/`annualIncome`/`annualExpense`/`annualBalance`/`topExpenseMonth`、`badgeName`/`badgeDescription`/`unlockedDate`、`totalBudget`/`usedAmount`/`remaining`/`usedPercent`/`budgetStatus`、`level`/`exp`/`expInCurrentLevel`/`maxLevelReached`/`nextLevelExp`/`expToNextLevel`（金额用 `BigDecimal`、经验用 `Long`、天数/等级用 `Integer`）
  - 补充 Javadoc 明确各字段 null 语义（`available=true` ⟺ `core` 非空、`narrative` 非空、`unavailableReason=null`；`available=false` ⟺ `core=null`、`narrative=null`、`unavailableReason` 非空；`label` 无来源时省略为 null；满级时 `nextLevelExp`/`expToNextLevel` 为 null）；DTO 字段集即隐私白名单，绝不含 email / 任何令牌 / `plan` / `wx_openid` / `wx_unionid` / 邀请码 / 其它账本数据 / `external_id` / 原始备注全文 / 商户原始标识 / 附件内容或链接
  - _Requirements: 1.1, 1.2, 2.1, 6.6, 10.3, 12.3, 12.4, 13.3_

- [x] 2. 新增内部只读辅助类型 `ShareCardType` 与 `ShareCardQuery`
  - 在 `src/main/java/com/damien/youyu/service/` 新建 `ShareCardType.java`：恰好 6 个枚举值 `STREAK_MILESTONE`/`MONTHLY_SUMMARY`/`ANNUAL_BILL`/`ACHIEVEMENT_BADGE`/`BUDGET_ACHIEVED`/`LEVEL_UP`（区分大小写）
  - `ShareCardType.parse(String)`：非 6 种取值之一抛 `ApiException.reportParamInvalid("type", ...)`（复用既有错误码，不新增）
  - `ShareCardType.isLedgerScoped()`：`MONTHLY_SUMMARY`/`ANNUAL_BILL`/`BUDGET_ACHIEVED` 为账本相关（true），其余三类为账本无关（false）
  - 新建 `ShareCardQuery.java`：`of(cardType, month, year, code, milestone, clock)` 静态工厂，仅解析当前 `type` 相关的可选参数、忽略无关参数；账本相关卡片 `month`（`YYYY-MM`）/ `year`（`YYYY`）解析沿用 `ReportController.parseMonth` 同款逻辑（非法格式/月份非 01–12/非 4 位年份或超范围 → `ApiException.reportParamInvalid`）；`month`/`year` 缺省取 `Asia/Shanghai` 当前自然月/自然年
  - _Requirements: 1.1, 1.7, 1.8, 4.2, 4.7, 5.2, 5.7, 7.2, 7.6, 10.2, 10.5, 10.7, 10.9_

- [x] 3. 新增可配置品牌名/Logo 比例载体 `ShareCardProperties`（`@ConfigurationProperties`）
  - 在 `src/main/java/com/damien/youyu/config/` 新建 `ShareCardProperties.java`，前缀 `youyu.share-card`，镜像既有 `AiInsightProperties` / `PersonalityTagProperties` 的 JavaBean 绑定风格
  - `brandName`（默认「有余」，去空白后为空回退「有余」）
  - `logoMaxAreaRatio`（默认 `0.05`，钳制 0.00–1.00，越界回退 0.05）
  - 提供 `brandNameOrDefault()` 与 `logoMaxAreaRatioClamped()`，非法取值回退默认、不报错；补充 Javadoc 标注对应需求条款
  - _Requirements: 1.2, 2.5, 2.6_

- [x] 4. 实现中文文案渲染组件 `ShareCardNarrator`（纯函数）
  - [x] 4.1 实现 `ShareCardNarrator.render(ShareCardType, ShareCardCore)`
    - 在 `src/main/java/com/damien/youyu/service/` 新建 `ShareCardNarrator.java`，纯函数、无任何 I/O、不注入任何 HTTP 客户端或外部服务/LLM（镜像既有 `TagNarrator`）
    - 为 6 类卡片各实现中文模板（采用设计中的正向/中性模板：连续里程碑、本月总结、年度账单、获得徽章、预算达成、成长升级）
    - 内置一份**可逐条枚举核对**的「负面/评判/羞辱/警示词汇表」（如「超支、警告、挥霍、剁手、后悔、失控、乱花、败家」等），提供 `containsForbiddenWord(text)`，保证文案中每个词均零命中（仅正向或中性措辞）
    - 文案中每个数值直接取自 `core` 机器字段并按同口径格式化（金额 2dp HALF_UP、占比 2dp、天数/笔数/等级/年月为整数），保证「文案数值 == 机器字段」逐一相等；每段文案至少含核心数据中一项关键数值（里程碑/连续天数/金额/等级/成就名之一）；长度 1..60 个中文字符
    - 生成失败兜底：关键数值缺失 → 返回该类型内置默认文案（正向、含类型名、1..60 字符），不抛错、不使请求失败
    - _Requirements: 2.8, 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_

  - [ ]* 4.2 编写 `ShareCardNarrator` 单元测试
    - 逐类型断言数值一致（文案内数值 == 机器字段，金额 2dp、占比 2dp）、长度 1..60、禁用词零命中
    - 覆盖关键数值缺失 → 内置默认文案兜底分支
    - _Requirements: 9.3, 9.5, 9.6, 9.7_

- [x] 5. 搭建只读组合器 `ShareCardService` 骨架（鉴权后编排 + 昵称/头像种子 + 账本语义分派）
  - 在 `src/main/java/com/damien/youyu/service/` 新建 `ShareCardService.java`，标注 `@Service`
  - 注入 `MonthlyDigestService`、`ReportService`、`BudgetService`、`UserGrowthRepository`、`StreakMilestones`、`GrowthLevelCurve`、`AchievementSnapshotService`、`GrowthBadgeCatalog`、`PersonalityTagService`、`UserRepository`、`ShareCardProperties`、`ShareCardNarrator`、`Clock`（仅编排既有服务与只读件，**不新增任何 repository 查询**）
  - `card(Long userId, Long ledgerId, ShareCardQuery query)` 标注 `@Transactional(readOnly = true)`，全过程仅 SELECT
  - 昵称与头像种子：读 `userRepository.findById(userId)`；`nickname = 去空白后非空 ? 原值 : "有余用户"`；`avatarSeed =` 该 `nickname` 首个 Unicode 码点（与 `pages/me/me.vue` 的 `nickname.slice(0,1)` 同口径，不引入头像图片上传/外链）
  - 骨架按 `query.cardType()` 分派到各卡片评估器（任务 6 实现），产出 `core`（可用时非空）与 `available`/`unavailableReason`；本任务先搭建分派与打包流程与 `nickname`/`avatarSeed`/`brandName` 恒在场字段，评估器细节留待任务 6
  - _Requirements: 1.2, 1.3, 2.2, 2.3, 10.1_

- [x] 6. 实现 6 类卡片核心数据来源与可用条件评估器（`ShareCardService` 内部，全部只读、不触发结算）
  - [x] 6.1 实现 `STREAK_MILESTONE`（连续记账里程碑，账本无关）评估器
    - **只读、不结算**：`profile = userGrowthRepository.findById(userId)`；`maxStreakDays = profile?.maxStreakDays`、`currentStreak = StreakJudgment.currentStreakDays(profile?.lastRecordDate, profile?.currentStreakDays, LocalDate.now(clock))`；里程碑集合取 `streakMilestones` 派生自成就清单 `MAX_STREAK` 门槛（7/30/100/365），**不在本 spec 写死**
    - 已达成里程碑 = 里程碑集合中 ≤ `maxStreakDays` 的取值；核心里程碑 = 已达成里程碑的最大取值
    - 可用当且仅当存在至少一个已达成里程碑；携带 `milestone`（核心里程碑）、`currentStreakDays`、`maxStreakDays`；否则不可用（`NO_MILESTONE_ACHIEVED`），不抛错
    - `milestone` 参数：属于已达成里程碑则以其为核心里程碑；未达成或不属于集合则回退核心里程碑
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 6.2 实现 `MONTHLY_SUMMARY`（本月总结，账本相关）评估器
    - `digest = monthlyDigestService.digest(ledgerId, month)`（复用月报数据包，同口径、`Asia/Shanghai`、排除 transfer）；核心数据携带 `month`（`YYYY-MM`）、`monthStatus`、`income`、`expense`、`balance`（=`digest.netBalance`）、`topCategoryName`/`topCategoryPercent`（取 `digest.categoryRanking` 首项，可空）
    - 月状态：`monthStatus = month.isBefore(YearMonth.now(clock)) ? "final" : "partial"`；`partial` 月核心数据基于截至当前时刻的数据
    - 可用当且仅当目标月存在至少一笔计入统计的交易（`income > 0 或 expense > 0`）；否则不可用（`NO_TRANSACTIONS`），不抛错
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [x] 6.3 实现 `ANNUAL_BILL`（年度账单，账本相关）评估器
    - `trendReport(ledgerId, YearMonth(year,1), YearMonth(year,12))` 得 12 月点：`annualIncome`/`annualExpense` = 各月点之和、`annualBalance` = 收入 − 支出、`topExpenseMonth` = expense 最大月（并列取月份小者）；`categoryReport(ledgerId, year-01-01, year-12-31, EXPENSE)` 首项为 `topCategoryName`（可空）
    - 年状态：`yearStatus = year < currentYear ? "final" : "partial"`；核心数据携带 `year`（`YYYY`）、`yearStatus`、`annualIncome`、`annualExpense`、`annualBalance`（+ 可空 `topExpenseMonth`/`topCategoryName`）
    - 可用当且仅当目标年存在至少一笔计入统计的交易（`annualIncome > 0 或 annualExpense > 0`）；否则不可用（`NO_TRANSACTIONS`），不抛错
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [x] 6.4 实现 `ACHIEVEMENT_BADGE`（获得徽章，账本无关）评估器
    - **只读、不结算**：`snapshot = achievementSnapshotService.snapshot(userId)` + `badgeCatalog.badges()`；仅以已解锁成就为候选
    - `code` 参数：命中清单且已解锁 → 该成就为核心成就；否则不可用（`BADGE_NOT_UNLOCKED`），不抛错
    - `code` 缺省 → 取解锁时刻最新的一枚已解锁成就（按对应 `BADGE` 事件 `created_at`）；无任何已解锁成就 → 不可用（`NO_UNLOCKED_ACHIEVEMENT`），不抛错
    - 核心数据仅携带 `badgeName`（展示名称）、`badgeDescription`（中文描述）、`unlockedDate`（`YYYY-MM-DD`）；**不下发成就编码之外的内部标识**
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [x] 6.5 实现 `BUDGET_ACHIEVED`（预算达成，账本相关）评估器
    - `ov = budgetService.overview(ledgerId, month)`；核心数据携带 `month`、`totalBudget`、`usedAmount`（=`ov.spent`）、`remaining`、`usedPercent`（2dp，据同一 `spent`/`totalBudget` 计算）、`budgetStatus`（OK/WARN/OVER）
    - 可用当且仅当 `ov.hasBudget 且 totalBudget > 0.00 且 spent > 0.00 且 spent ≤ totalBudget`（即 `budgetStatus != OVER`）；否则不可用（`NO_BUDGET_OR_OVER`），不抛错
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x] 6.6 实现 `LEVEL_UP`（成长升级，账本无关）评估器
    - **只读、不结算**：`profile = userGrowthRepository.findById(userId)`；`level = profile?.level ?? 1`、`exp = profile?.exp ?? 0`、`currentLevelExp = growthLevelCurve.threshold(level)`、`expInCurrentLevel = exp − currentLevelExp`、`maxLevelReached = level >= GrowthLevelCurve.MAX_LEVEL(100)`——与 `GrowthQueryService` 等级换算同一实现同值
    - 可用当且仅当 `level ≥ 2`；核心数据携带 `level`、`exp`、`expInCurrentLevel`；`level == 1` 不可用（`LEVEL_TOO_LOW`），不抛错
    - 满级（`level == 100`）时 `maxLevelReached=true` 且 `nextLevelExp`/`expToNextLevel` 置 null；未满级时 `nextLevelExp = threshold(level+1)`、`expToNextLevel = nextLevelExp − exp`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

- [x] 7. 实现标签来源解析、`ShareCardResponse` 组装与隐私净化
  - 标签来源（可省，无来源 → `label=null`，绝不阻断出卡）：账本相关卡片取 `personalityTagService.tags(ledgerId, month/当年当月)` 首枚标签标题；账本无关卡片（`STREAK_MILESTONE`/`LEVEL_UP`）取 `snapshot` 中最近解锁成就名称；`ACHIEVEMENT_BADGE` 取该成就分类中文名（`AchievementCategory.label()`）；解析失败一律降级为 null
  - 组装 `ShareCardResponse`：可用时 `core`/`narrative` 非空、`unavailableReason=null`；不可用时 `core=null`/`narrative=null`/`unavailableReason` 非空，仍返回 `nickname`/`avatarSeed`/`brandName`（`brandName` 取 `props.brandNameOrDefault()`）
  - 隐私净化：DTO 字段集本身即白名单；返回前做防御式净化，确保不含 email / 任何令牌 / `plan` / `wx_openid` / `wx_unionid` / 邀请码 / 其它账本数据 / `external_id` / 原始备注全文 / 商户原始标识；检测到任一被禁字段则移除后照常返回其余合法字段、不改其取值、不中断请求
  - _Requirements: 2.4, 2.7, 12.3, 12.4, 12.5_

- [x] 8. 将 `ShareCardNarrator` 接入 `ShareCardService`
  - 卡片可用时调用 `narrator.render(cardType, core)` 生成 `narrative`；关键数值缺失 → 取该类型内置默认文案兜底、保留核心数据、整体不报错
  - 卡片不可用时 `narrative=null`
  - _Requirements: 9.1, 9.7_

- [x] 9. 新增 `ShareCardController` 与只读端点 `GET /api/share-cards`
  - 在 `src/main/java/com/damien/youyu/api/` 新建 `ShareCardController.java`（`@RestController`、`@RequestMapping("/api/share-cards")`），注入 `CurrentUser`、`UserRepository`、`CurrentLedger`、`ShareCardService`、`Clock`
  - `@GetMapping` 方法参数：`type`（必填）、`month`/`year`/`code`/`milestone`（可选）
  - 固定错误优先级顺序（满足需求 10.6 与 1.7）：① 鉴权 `currentUser.requireUserId()` + `userRepository.findById` 存在校验 → `UNAUTHENTICATED`（与 `GrowthController`/`StreakController` 同构）；② `ShareCardType.parse(type)` 路由校验 → 非 6 种 `REPORT_PARAM_INVALID`；③ 账本解析仅账本相关卡片 `currentLedger.requireLedgerId()` → 不可访问 `LEDGER_NOT_ACCESSIBLE`、无头回退默认账本，账本无关卡片**完全不读取 `X-Ledger-Id`**；④ `ShareCardQuery.of(...)` 周期参数校验 → 非法 `REPORT_PARAM_INVALID`
  - 返回 `ResponseEntity.ok(shareCardService.card(userId, ledgerId, query))`；忽略请求中任何指定他人身份的多余参数/头且不因携带而报错；**不新增任何错误码、不改动任何既有端点**
  - _Requirements: 1.7, 1.8, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_

- [ ] 10. 后端属性测试（对应 Correctness Properties 1–15）
  - [ ]* 10.1 编写属性测试 Property 1（响应完整性与可用/不可用语义）
    - **Feature: share-card, Property 1: 响应完整性与可用/不可用语义**
    - **Validates: Requirements 1.2, 2.1, 2.4, 9.1**
    - 新建 `ShareCardServicePropertyTest`（`@DataJpaTest` + H2（MODE=MySQL）+ 真实 Repository + 固定 `Clock` + 真实 `MonthlyDigestService`/`ReportService`/`BudgetService`），随机化用户/账本/卡片类型/底层数据；断言 `cardType`/`available`/`nickname`/`avatarSeed`/`narrative`/`core`/`brandName` 五项恒在场、`label` 可 null；可用时 `core`/`narrative` 非空且 `unavailableReason=null`，不可用时 `core`/`narrative` 为 null 且 `unavailableReason` 非空；每次迭代独立 `userId`/`ledgerId`；≥100 次迭代

  - [ ]* 10.2 编写属性测试 Property 2（昵称与文字头像种子映射）
    - **Feature: share-card, Property 2: 昵称与文字头像种子映射**
    - **Validates: Requirements 2.2, 2.3**
    - 随机化 `nickname`（含空白/纯空白/缺省），断言去空白为空取「有余用户」、否则原昵称，`avatarSeed` 恒等于该 `nickname` 首个 Unicode 码点，不引入头像图片；≥100 次迭代

  - [ ]* 10.3 编写属性测试 Property 3（同口径一致，模型对照）
    - **Feature: share-card, Property 3: 同口径一致（模型对照）**
    - **Validates: Requirements 1.5, 1.10, 3.1, 4.1, 5.1, 6.1, 7.1, 8.1, 8.6, 13.5**
    - 以真实 `MonthlyDigestService.digest`、`ReportService.trendReport/categoryReport/monthlyReport`、`BudgetService.overview`、`StreakJudgment`/`StreakMilestones`、`GrowthLevelCurve`、`AchievementSnapshotService`（或就地暴力实现）为参照，逐类型断言核心数据逐值相等（`isEqualByComparingTo`）；年度聚合以「12 次 monthlyReport 之和」为参照；全部排除 transfer、按 `Asia/Shanghai`、金额 2dp；≥100 次迭代

  - [ ]* 10.4 编写属性测试 Property 4（账本隔离与账本无关免疫）
    - **Feature: share-card, Property 4: 账本隔离与账本无关免疫**
    - **Validates: Requirements 1.3, 1.7, 1.8, 2.7, 3.6, 4.6, 5.6, 6.5, 7.5, 8.5, 10.7**
    - 两账本 A/B 随机数据，断言账本相关卡片 A 的结果与「仅存在 A 数据」时逐值相同；账本无关卡片结果不随 `X-Ledger-Id`（缺失/合法/不可访问）改变且跨用户全部账本取数；忽略指定他人身份参数/头且不改变结果；≥100 次迭代

  - [ ]* 10.5 编写属性测试 Property 5（金额与占比 2 位小数）
    - **Feature: share-card, Property 5: 金额与占比 2 位小数**
    - **Validates: Requirements 1.4**
    - 断言 `core` 中所有金额字段（`income`/`expense`/`balance`/`annualIncome`/`annualExpense`/`annualBalance`/`totalBudget`/`usedAmount`/`remaining`）2dp HALF_UP、占比字段（`topCategoryPercent`/`usedPercent`）有定义时 2dp；≥100 次迭代

  - [ ]* 10.6 编写属性测试 Property 6（确定性可复现）
    - **Feature: share-card, Property 6: 确定性可复现**
    - **Validates: Requirements 1.6**
    - 同一时钟时刻、同一用户/账本/卡片类型/周期与固定底层数据多次请求，断言 `available`/`unavailableReason` 与全部核心数据取值完全一致；≥100 次迭代

  - [ ]* 10.7 编写属性测试 Property 7（STREAK_MILESTONE 门控、里程碑算术与参数回退）
    - **Feature: share-card, Property 7: STREAK_MILESTONE 门控、里程碑算术与参数回退**
    - **Validates: Requirements 3.2, 3.3, 3.4, 3.5**
    - 随机历史最长连续天数，断言已达成里程碑 = 集合中 ≤ maxStreakDays 者、核心里程碑 = 其最大值；覆盖无已达成里程碑不可用分支、`milestone` 参数命中/未命中/非集合值回退；≥100 次迭代

  - [ ]* 10.8 编写属性测试 Property 8（MONTHLY_SUMMARY 门控与月状态）
    - **Feature: share-card, Property 8: MONTHLY_SUMMARY 门控与月状态**
    - **Validates: Requirements 4.3, 4.4, 4.5**
    - 随机目标月与当前时刻相对位置覆盖 `partial`/`final`；断言授予当且仅当收入 > 0 或支出 > 0（转账已排除）、携带 `month`/`monthStatus`/`income`/`expense`/`balance`；空月/只含转账月不可用不抛错；≥100 次迭代

  - [ ]* 10.9 编写属性测试 Property 9（ANNUAL_BILL 门控、年状态与年度聚合）
    - **Feature: share-card, Property 9: ANNUAL_BILL 门控、年状态与年度聚合**
    - **Validates: Requirements 5.1, 5.3, 5.4, 5.5**
    - 构造跨月交易，断言年度收入/支出 = 12 月点之和、结余为差、支出最高月为最大 expense 月（并列取小）；年状态 `final` ⟺ 目标年早于当前年；授予当且仅当存在计入交易；空年不可用不抛错；≥100 次迭代

  - [ ]* 10.10 编写属性测试 Property 10（ACHIEVEMENT_BADGE 门控与最近解锁选取）
    - **Feature: share-card, Property 10: ACHIEVEMENT_BADGE 门控与最近解锁选取**
    - **Validates: Requirements 6.2, 6.3, 6.4**
    - 随机成就解锁集合与 `BADGE` 事件时刻，断言仅已解锁为候选；`code` 命中且已解锁 → 携带名称/描述/解锁日期；`code` 未知或未解锁不可用不抛错；缺省取最近解锁一枚、无任何解锁不可用不抛错；≥100 次迭代

  - [ ]* 10.11 编写属性测试 Property 11（BUDGET_ACHIEVED 门控）
    - **Feature: share-card, Property 11: BUDGET_ACHIEVED 门控**
    - **Validates: Requirements 7.3, 7.4**
    - 随机预算/支出集合覆盖未设预算/预算为 0/已用为 0/超支/达成分支；断言授予当且仅当「已设预算 且 总预算 > 0.00 且 已用 > 0.00 且 已用 ≤ 总预算」、携带 `month`/`totalBudget`/`usedAmount`/`remaining`/`usedPercent`(2dp)；否则不可用不抛错；≥100 次迭代

  - [ ]* 10.12 编写属性测试 Property 12（LEVEL_UP 门控与满级语义）
    - **Feature: share-card, Property 12: LEVEL_UP 门控与满级语义**
    - **Validates: Requirements 8.2, 8.3, 8.4**
    - 随机等级/经验（含 1/2/100），断言授予当且仅当等级 ≥ 2、携带 `level`/`exp`/`expInCurrentLevel`；等级 1 不可用不抛错；满级 `maxLevelReached=true` 且 `nextLevelExp`/`expToNextLevel` 为 null，未满级二者非空；≥100 次迭代

  - [ ]* 10.13 编写属性测试 Property 13（一句 AI 文案正确性：数值一致、禁用词与长度）
    - **Feature: share-card, Property 13: 一句 AI 文案正确性（数值一致、禁用词与长度）**
    - **Validates: Requirements 2.8, 9.3, 9.4, 9.5, 9.6, 9.7**
    - 断言可用卡片文案至少含一项核心数值、文案内每个数值 == 核心字段（金额/占比 2dp、天数/等级/年月整数）、长度 1..60、对 `ShareCardNarrator` 可枚举禁用词表零命中；覆盖关键数值缺失 → 内置默认文案兜底；≥100 次迭代

  - [ ]* 10.14 编写属性测试 Property 14（隐私白名单：数据包不含被禁字段）
    - **Feature: share-card, Property 14: 隐私白名单（数据包不含被禁字段）**
    - **Validates: Requirements 6.6, 12.3, 12.4, 12.5**
    - 将响应序列化为 JSON，断言字段名集合为白名单子集，不含 email/令牌样式取值、`plan`/`wx_openid`/`wx_unionid`/邀请码、`external_id`/原始 `note`/商户原始标识；`ACHIEVEMENT_BADGE` 核心仅名称/描述/解锁日期；≥100 次迭代

  - [ ]* 10.15 编写属性测试 Property 15（纯只读不写库）
    - **Feature: share-card, Property 15: 纯只读不写库**
    - **Validates: Requirements 10.1, 13.1, 13.6**
    - 调用前后对 `transactions`/`categories`/`merchants`/`budgets`/`growth_events`/`user_growth`/`streak_segments` 及全表清单做行数与内容快照，断言完全一致（零写入、零 DDL、**未触发结算**：`growth_events` 无新增）；账本无关卡片多次调用同样零写入；异常隔离后数据不变；≥100 次迭代

- [ ] 11. 编写服务层边界单元测试、组件单元测试与控制器契约测试
  - [ ]* 11.1 编写 `ShareCardServiceTest`（`@DataJpaTest`）服务层边界单元测试
    - 各卡片典型场景与边界：STREAK 恰好达成/差一天里程碑、`milestone` 命中/未命中/非集合值（3.5）；MONTHLY 空月/只含转账月不可用、`partial`/`final`（4.3、4.5）；ANNUAL 跨月聚合、支出最高月并列取小者、空年（5.4、5.5）；ACHIEVEMENT 指定/缺省/未解锁/未知 code 与最近解锁选取（6.2–6.4）；BUDGET 未设预算/已用为 0/超支/达成四分支（7.3、7.4）；LEVEL 等级 1/2/100（8.2–8.4）；文案缺关键数值兜底（9.7）；非法配置回退默认（2.5）
    - _Requirements: 3.5, 4.3, 4.5, 5.4, 5.5, 6.2, 6.3, 6.4, 7.3, 7.4, 8.2, 8.3, 8.4, 9.7, 2.5_

  - [ ]* 11.2 编写 `ShareCardNarratorTest` 单元测试
    - 逐类型模板断言数值一致、禁用词零命中、缺关键数值默认文案分支（与任务 4.2 合并或独立均可，覆盖 6 类模板）
    - _Requirements: 9.3, 9.4, 9.5, 9.6, 9.7_

  - [ ]* 11.3 编写 `ShareCardPropertiesTest` 单元测试
    - 断言 `brandNameOrDefault()`（空白回退「有余」）、`logoMaxAreaRatioClamped()`（越界/负值回退 0.05）
    - _Requirements: 2.5, 2.6_

  - [ ]* 11.4 编写 `ShareCardControllerTest`（MockMvc）契约测试
    - 6 种合法 `cardType` 均可返回、非法 `cardType` → `REPORT_PARAM_INVALID`（1.1、10.5）；无/坏令牌 → `UNAUTHENTICATED` 且响应无卡片数据（10.3）；账本相关卡片越权 `X-Ledger-Id` → `LEDGER_NOT_ACCESSIBLE`（10.4）、账本无关卡片带坏 `X-Ledger-Id` 仍正常返回（1.7）；账本相关卡片非法 `month`/`year` → `REPORT_PARAM_INVALID`（4.7、5.7、7.6）、无 `X-Ledger-Id` → 默认账本（1.8）；缺省 `month`/`year` 取当前月/年（4.2、5.2、7.2）；多错误并存按「鉴权 → cardType → 账本 → 参数」优先级（10.6）；携带指定他人身份的多余参数/头被忽略且不报错（10.7）
    - _Requirements: 1.1, 1.7, 1.8, 4.2, 4.7, 5.2, 5.7, 7.2, 7.6, 10.3, 10.4, 10.5, 10.6, 10.7_

  - [ ]* 11.5 编写契约不回归与依赖不可用兜错测试
    - 既有报表/预算/月报/连续/成就/成长/交易接口现有测试保持通过、字段集与错误码集合不变（13.3、13.4）；确认未新增/改动任何 Flyway 脚本、未新建任何表、未新增任何 repository 方法（13.2）；`ShareCardService`/`ShareCardNarrator` 无任何 HTTP 客户端/外部依赖注入（12.1、12.2）；注入使内部聚合抛错的场景，断言既有接口不受影响、数据库零写入（13.6）
    - _Requirements: 12.1, 12.2, 13.2, 13.3, 13.4, 13.6_

- [x] 12. Checkpoint - 确保后端所有测试通过
  - 确保后端所有测试通过，ask the user if questions arise.

- [x] 13. miniapp 新增分享卡片 API 方法 `api/shareCard.js`
  - 在 `miniapp/src/api/` 新建 `shareCard.js`，`shareCard(type, params = {})`：拼接 `type` 与该类型可选周期/标识参数（`month=YYYY-MM` / `year=YYYY` / `code` / `milestone`），`http.get('/share-cards?' + qs, opts)`
  - 账本无关卡片集合 `LEDGER_INDEPENDENT = {STREAK_MILESTONE, ACHIEVEMENT_BADGE, LEVEL_UP}` → 带 `{ noLedger: true }` 不发送 `X-Ledger-Id`（对齐 `api/streak.js` 写法）；账本相关卡片默认带 `X-Ledger-Id`
  - 补充 JSDoc；沿用 `utils/request.js` 网络层（自动带 Authorization；401 清 token 跳登录；LEDGER_NOT_ACCESSIBLE 清本地账本重试一次）
  - _Requirements: 1.7, 10.2_

- [x] 14. 新增 `miniapp/src/utils/shareCard.js` 纯逻辑模块
  - [x] 14.1 实现降级决策、展示映射与布局纯函数
    - 在 `miniapp/src/utils/` 新建 `shareCard.js`，镜像 `utils/personalityTags.js` / `utils/insights.js`
    - 常量 `SHARE_CARD_TIMEOUT_MS = 5000`；`LEDGER_SCOPED_TYPES` / `isLedgerScoped(type)`：账本相关卡片集合判定
    - `shouldFetchCard(isLoggedIn, isAll, cardType)`：已登录才请求；账本相关卡片在「全部账本」聚合视图下不请求不展示
    - `raceWithTimeout(promise, ms)`：`Promise.race` + 定时器超时包装
    - `resolveCardState({ isLoggedIn, isAll, cardType, fetchCard, timeoutMs, isStale })`：返回 `{ requested, stale, card, cardVisible }`——未登录/聚合视图（账本相关）不请求不展示；失败或 5000ms 超时静默隐藏；成功但 `available=false` → 隐藏出图/保存/分享入口并展示「暂不可用」；过期响应丢弃；从不触碰其它页面状态
    - `cardToDisplay(card)`：白名单式映射（`avatarSeed`/`nickname`/`label`/`narrative`/按类型选取的核心数值展示串/`brandName`），优先展示 `narrative`；绝不引用邮箱/令牌/其它账本数据
    - `computeCardLayout(canvasWidth, canvasHeight, logoMaxAreaRatio)`：为品牌 Logo 计算包围盒使其面积 ≤ 可见区域 `logoMaxAreaRatio`（默认 0.05）、置于卡片一角（不落入视觉中心）；返回的布局元素集合不含任何促销/下载引导/二维码元素
    - _Requirements: 1.9, 2.5, 2.6, 11.7, 11.8, 11.9, 12.6_

  - [ ]* 14.2 编写属性测试 Property 16（前端静默降级，纯逻辑）
    - **Feature: share-card, Property 16: 前端静默降级（`utils/shareCard.js` 纯逻辑）**
    - **Validates: Requirements 1.9, 11.7, 11.8, 11.9, 12.6**
    - vitest 覆盖：未登录不请求且不可见；账本相关卡片在聚合视图不请求不展示；失败或 5000ms 超时 `cardVisible=false`；`available=false` 关闭出图/保存/分享入口；`stale`（请求期间切换账本/周期）跳过应用；决策只产出分享卡片自身状态、从不返回或改动其它页面状态

  - [ ]* 14.3 编写属性测试 Property 17（前端品牌 Logo 布局约束，纯逻辑）
    - **Feature: share-card, Property 17: 前端品牌 Logo 布局约束（`utils/shareCard.js` / 布局纯函数）**
    - **Validates: Requirements 2.5, 2.6**
    - vitest 对任意画布尺寸断言 `computeCardLayout` 为 Logo 计算的面积占比 ≤ `logoMaxAreaRatio`、Logo 包围盒不落入视觉中心区域、布局元素集合不含促销/下载引导/二维码元素

  - [ ]* 14.4 编写 `cardToDisplay` 字段隔离单测
    - 断言展示映射只取白名单字段（`avatarSeed`/`nickname`/`label`/`narrative`/按类型的核心数值/`brandName`），绝不引用邮箱/令牌/其它账本数据
    - _Requirements: 12.6_

- [x] 15. 新增 `pages/share/share.vue`（卡片渲染 + 保存 + 转发 + 降级）
  - 在 `miniapp/src/pages/share/` 新建 `share.vue`，从报表页/连续/成长/成就页以 `cardType`（+ 可选周期/编码）跳入；账本相关卡片在「全部账本」聚合视图下不提供入口
  - 数据加载：已登录且（账本相关卡片非聚合视图）时经 `api/shareCard.js` 请求，复用 `utils/shareCard.js` 的 `shouldFetchCard`/`resolveCardState`/`raceWithTimeout`（5000ms 超时静默降级，不弹阻断性弹窗，不阻断当前页其余交互）；请求期间切换账本/周期使响应过期 → 丢弃不覆盖
  - 渲染六元素（复用 `utils/digest.js` canvas 绘制范式）：文字头像（`avatarSeed`，不含头像图片）、昵称、标签（有则显示）、一句 AI 文案、核心数据（主视觉）、小尺寸品牌 Logo（按 `computeCardLayout` 置于一角、面积 ≤ 5%、不置视觉中心）；不绘制任何促销/下载引导/二维码
  - 保存/转发：复用 `canvasToTempFilePath` 出图、`saveImageToPhotosAlbum` 保存、`showShareImageMenu`/`onShareAppMessage` 转发；相册授权未授予先请求授权，拒绝 → 展示需授权提示、不写入、停留当前页、不进入错误态；写入成功 → 展示成功提示并停留
  - 出图/保存 3000ms 超时 → 结束本次操作、展示失败提示、停留当前页、允许再次触发；`available=false` 不提供出图/保存/分享入口，触发相关入口 → 展示「暂不可用」、不发起 canvas 绘制/写相册/转发；未登录 → 不请求不展示，展示登录入口
  - _Requirements: 1.9, 2.1, 2.5, 2.6, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9, 12.6_

- [x] 16. Final Checkpoint - 确保后端与前端全部测试通过
  - 确保后端与前端全部测试通过，ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选（测试相关），可为更快的 MVP 跳过；核心实现任务从不标记为可选。
- 每个任务引用具体需求子条款以保证可追溯性。
- 属性测试对应 `design.md` 的 Correctness Properties（Property 1–17），每条属性为独立子任务；同口径/门控类（3、7–12）采用模型对照（以真实 `MonthlyDigestService`/`ReportService`/`BudgetService` 与成长域只读件或就地暴力实现为参照），年度聚合以「12 次 monthlyReport 之和」为参照。
- 后端属性测试沿用仓库既有 jqwik + `@DataJpaTest` + H2（MODE=MySQL）+ 真实 Repository 范式，不使用 mock；固定注入 `Clock`（Asia/Shanghai）获得确定性；每次迭代使用独立 `userId`/`ledgerId` 隔离随机数据。
- **严格只读**：连续/成就/成长三类账本无关卡片绝不调用会触发结算的 overview 方法，只复用只读判定件与只读仓库读取，写语句数恒为 0（Property 15 坐实）。
- 前端 `utils/shareCard.js` 为纯逻辑单一事实源，配合 vitest 覆盖降级决策（Property 16）、Logo 布局约束（Property 17）与字段隔离。
- 本 spec 纯只读、纯增量：不新增数据库表、不新增/改动 Flyway 脚本、不新增任何错误码、不新增任何 repository 查询、不改动任何既有接口契约、不产图不存图、不引入对象存储。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "2", "3"] },
    { "id": 1, "tasks": ["4.1"] },
    { "id": 2, "tasks": ["5", "4.2"] },
    { "id": 3, "tasks": ["6.1", "6.2", "6.3", "6.4", "6.5", "6.6"] },
    { "id": 4, "tasks": ["7"] },
    { "id": 5, "tasks": ["8"] },
    { "id": 6, "tasks": ["9"] },
    { "id": 7, "tasks": ["10.1", "10.2", "10.3", "10.4", "10.5", "10.6", "10.7", "10.8", "10.9", "10.10", "10.11", "10.12", "10.13", "10.14", "10.15", "11.1", "11.2", "11.3", "11.4", "11.5", "13"] },
    { "id": 8, "tasks": ["14.1"] },
    { "id": 9, "tasks": ["14.2", "14.3", "14.4", "15"] }
  ]
}
```
