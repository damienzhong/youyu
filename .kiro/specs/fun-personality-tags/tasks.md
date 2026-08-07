# Implementation Plan: 趣味人格标签（Fun Personality Tags）

## Overview

本计划把趣味人格标签设计（`design.md`）拆解为一系列增量式编码任务。所有任务严守「纯只读、纯增量」边界：
后端新增一个只读组合器 `PersonalityTagService`、一个纯函数中文文案渲染组件 `TagNarrator`、一个可配置阈值/匹配
集合/展示上限载体 `PersonalityTagProperties`、一组只读响应 DTO（`PersonalityTagsResponse` + 内嵌 `PersonalityTag`），
并在既有 `ReportController` 上新增单个只读端点 `GET /api/reports/personality-tags`；miniapp 新增
`api/report.js#personalityTags`、`utils/personalityTags.js` 纯逻辑模块与 `pages/report/report.vue` 的趣味人格标签
卡片区块（标签墙、静默降级）。**不新增数据库表、不新增/改动 Flyway 脚本、不新增任何错误码、不改动任何既有接口
契约、不新增任何 repository 查询**——8 枚标签的原始指标全部复用既有 `ReportService.monthlyReport /
categoryReport / dimensionReport`、`BudgetService.overview` 与既有「按账本 + `occurredAt` 半开区间」交易查询
（夜宵在内存派生本地小时）。

实现语言沿用仓库既有技术栈：后端 Java 17 + Spring Boot（服务 / DTO / `@ConfigurationProperties`），测试用
jqwik + `@DataJpaTest` + H2（`MODE=MySQL`）+ 真实 Repository + 固定注入 `Asia/Shanghai` 的 `Clock`、以真实
`ReportService` / `BudgetService` 做模型对照；前端 miniapp uni-app / Vue 3（JavaScript），测试用 vitest。

每个任务在前一步基础上增量推进，最终把后端与前端串联起来。属性测试对应设计文档 Correctness Properties 的
16 条属性，标注格式为 `Feature: fun-personality-tags, Property {n}: {text}`，并保留 `Validates: Requirements X.Y`。

## Tasks

- [x] 1. 新增趣味人格标签响应 DTO（`PersonalityTagsResponse` + 内嵌 `PersonalityTag`）
  - 在 `src/main/java/com/damien/youyu/api/dto/` 新建 `PersonalityTagsResponse.java`
  - 顶层字段：`month`、`monthStatus`、`isFallback`、`fallbackText`、`tags`（`List<PersonalityTag>`）
  - 内嵌 `record PersonalityTag`，字段（8 枚标签异构共用、未用字段以 null 表达）：`tagKey`、`title`、`emoji`、`dimension`、`dimensionId`、`dimensionName`、`currentValue`、`previousValue`、`income`、`savings`、`saveRate`、`budget`、`used`、`usedRate`、`matchCount`、`matchAmount`、`matchPercent`、`lateNightCount`、`lateNightWindow`、`threshold`、`strengthScore`、`narrativeText`
  - 补充 Javadoc 明确各字段的 null 语义（`saveRate`/`usedRate` 无定义 → null；聚合类标签维度字段为 null；仅行为类标签携带 `matchCount/matchAmount/matchPercent`；仅 `LATE_NIGHT_KING` 携带 `lateNightCount/lateNightWindow`；`narrativeText` 生成失败 → null）；DTO 字段集即隐私白名单，绝不含 email/令牌/其它账本数据/`external_id`/原始备注全文/商户原始标识
  - _Requirements: 1.1, 2.7, 8.1, 10.3, 10.4, 10.5, 13.3, 13.4_

- [x] 2. 新增可配置阈值/匹配集合/展示上限载体 `PersonalityTagProperties`（`@ConfigurationProperties`）
  - 在 `src/main/java/com/damien/youyu/` 合适包下新建 `PersonalityTagProperties.java`，前缀 `youyu.personality-tags`，镜像既有 `AiInsightProperties` 的 JavaBean 绑定风格
  - 展示上限：`maxCount=4`（N），提供 `maxCountClamped()` 将越界（<1 或 >8）回退默认 4
  - 阈值与默认值：`savingsAmountMin=200.00`、`savingsRatePctMin=15.00`、`financeSaveRatePctMin=20.00`、`budgetUsedPctMax=90.00`、`takeoutCountMin=8`、`takeoutPctMin=20.00`、`coffeeCountMin=5`、`travelAmountMin=1000.00`、`travelCountMin=5`、`shoppingCountMin=8`、`shoppingAmountMin=800.00`、`lateNightCountMin=5`、`lateNightStartHour=22`、`lateNightEndHour=4`
  - 匹配集合：`takeoutCategories/takeoutMerchants`、`coffeeCategories/coffeeMerchants`、`travelCategories/travelMerchants`、`shoppingCategories/shoppingMerchants`，缺省填充相应默认名称集合
  - 提供 `lateNightWindow()`（非法时段回退 `[22:00, 次日 04:00)`）与 `sanitize()`（金额/笔数为负、占比/比率不在 0.00–100.00 → 回退该项默认值，不报错）
  - 补充 Javadoc 标注每个阈值对应的需求条款
  - _Requirements: 2.4, 2.5, 3.3, 4.4, 5.3, 6.6, 7.2, 7.3, 7.4, 9.3, 9.4_

- [x] 3. 实现中文标签文案渲染组件 `TagNarrator`（纯函数）
  - [x] 3.1 实现 `TagNarrator.render(PersonalityTag)`
    - 在 `src/main/java/com/damien/youyu/service/` 新建 `TagNarrator.java`，纯函数、无任何 I/O、不注入任何 HTTP 客户端或外部服务/LLM
    - 为 8 枚标签各实现中文模板（`SAVINGS_MASTER`/`FINANCE_STAR`/`BUDGET_MASTER`/`TAKEOUT_EXPLORER`/`COFFEE_COLLECTOR`/`LATE_NIGHT_KING`/`TRAVEL_ENTHUSIAST`/`SHOPPING_LIFER`），采用设计中的正向/中性模板
    - 内置一份**可逐条枚举核对**的「负面/评判/羞辱/警示词汇表」（如「乱花、挥霍、超支、警告、冲动、剁手、败家、后悔、失控」等），提供 `containsForbiddenWord(text)`，保证标题与文案中每个词均零命中（正向或中性措辞）
    - 文案中每个数值直接取自标签机器字段并按同口径格式化（金额 2dp、占比/比率百分比 2dp、笔数/月数整数），保证「文案数值 == 机器字段」逐一相等
    - 每段文案至少含标签标题 + 金额/占比/笔数/月数四类关键数值中的至少一项；长度 1..60 个中文字符
    - 回退名：分类名缺失/空白 → `已删除分类`，商户名缺失/空白 → `已删除商户`（固定、可复现），不因名称缺失丢弃标签
    - 生成失败（缺标题或缺全部关键数值）→ 返回 null（由服务标记生成失败并附失败原因），不抛错
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 6.8_

  - [ ]* 3.2 编写 `TagNarrator` 单元测试
    - 逐枚模板断言数值一致（文案内数值 == 机器字段，金额 2dp、占比/比率百分比 2dp）、长度 1..60、禁用词零命中
    - 覆盖回退名分支与生成失败（缺标题/缺全部关键数值返回 null）分支
    - _Requirements: 8.3, 8.6, 8.7, 8.8, 8.9_

- [x] 4. 搭建只读组合器 `PersonalityTagService` 骨架（月状态 / partial 短路 / 取数编排）
  - 在 `src/main/java/com/damien/youyu/service/` 新建 `PersonalityTagService.java`，标注 `@Service`
  - 注入 `ReportService`、`BudgetService`、`TransactionRepository`（仅复用既有半开区间查询）、`CategoryRepository`、`MerchantRepository`、`Clock`、`PersonalityTagProperties`、`TagNarrator`（仅编排既有服务，不新增任何 repository 查询）
  - `tags(Long ledgerId, YearMonth month)` 标注 `@Transactional(readOnly = true)`
  - 月状态：`status = month.isBefore(YearMonth.now(clock)) ? "final" : "partial"`
  - partial/未来月短路：`status == "partial"`（含目标月晚于当前月）→ v1 全部标签依赖完整月 → 候选为空 → 直接走鼓励兜底（仍携带 `month`、`monthStatus`）
  - `final` 月取数（复用既有服务，同口径、`Asia/Shanghai` 半开区间、排除 transfer）：`monthlyReport(M)`、`monthlyReport(M−1)`、`budgetService.overview(M)`、`categoryReport(M 全月, EXPENSE)`、`dimensionReport(M 全月, EXPENSE, "merchant")`、`transactionRepository.findByLedgerIdAndOccurredAt...(M 全月)`（内存过滤 EXPENSE + 派生本地小时）
  - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 1.10, 1.11_

- [x] 5. 实现 8 枚标签达标判定评估器（`PersonalityTagService` 内部，纯派生、互相独立）
  - [x] 5.1 实现 `SAVINGS_MASTER`（省钱达人）评估器
    - 取 `monthlyReport(M)`、`monthlyReport(M−1)` 的月度总支出（各 2dp HALF_UP）
    - `savings = prevExpense − expense`（2dp，可负）；`savingsRate` 仅 `prevExpense>0` 时 = `savings ÷ prevExpense × 100`（2dp），否则未定义（null）
    - 达标当且仅当 `prevExpense>0 且 savings>0 且（savings ≥ savingsAmountMin 或 savingsRate ≥ savingsRatePctMin）`；`prevExpense=0` 或 `savings≤0` 不授予、不报错、不中断其余
    - 携带目标月总支出、上月总支出、节省额、节省率
    - _Requirements: 3.1, 3.2, 3.4, 3.5, 3.6, 2.3_

  - [x] 5.2 实现 `FINANCE_STAR`（理财新星）评估器
    - 取 `monthlyReport(M)` 的总收入、总支出；`balance = income − expense`（2dp，可负）；无任何计入交易时三者取 0.00
    - `saveRate` 仅 `income>0` 时 = `balance ÷ income × 100`（2dp），否则未定义（null）
    - 达标当且仅当 `income>0 且 balance>0 且 saveRate ≥ financeSaveRatePctMin`；否则不授予、不报错
    - 携带总收入、总支出、结余、结余率
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 4.6, 2.3_

  - [x] 5.3 实现 `BUDGET_MASTER`（预算大师）评估器
    - 从 `budgetService.overview(ledgerId, M)` 取 `hasBudget`、`totalBudget`、`spent`
    - 仅在 `hasBudget 且 totalBudget>0.00` 时计算 2 位小数使用率 `usedRate = spent ÷ totalBudget × 100`（BigDecimal，HALF_UP，与 `BudgetService` 同源）
    - 达标当且仅当 `hasBudget 且 totalBudget>0.00 且 spent ≤ totalBudget 且 usedRate ≤ budgetUsedPctMax`；超支或 `usedRate>上限` 不授予；未设预算或预算≤0 不计算使用率、不授予、不报错
    - 携带本月预算、已用支出、预算使用率（2dp）
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 2.3_

  - [x] 5.4 实现行为类标签评估器（`TAKEOUT_EXPLORER`/`COFFEE_COLLECTOR`/`TRAVEL_ENTHUSIAST`/`SHOPPING_LIFER`）
    - 每枚配置分类名称集合与/或商户名称集合（`props`）；将目标月当前账本、未删除、`type=expense` 且分类名称或商户名称落在该标签匹配集合内的交易计入统计
    - 同一笔交易同时命中同一标签的分类集合与商户集合时**只计一次**（以交易 id 集合合并去重）
    - `matchCount`（整数≥0）、`matchAmount`（2dp≥0.00）、`matchPercent = matchAmount ÷ 当月总支出 × 100`（2dp）；当月总支出为 0 → 占比记 0.00 且不授予任何行为类标签
    - 达标当且仅当「matchCount ≥ 笔数下限 或 matchPercent ≥ 占比下限 或 matchAmount ≥ 金额下限」（仅对已配置的下限参与判定）；对每个已配置下限均严格不达标则不授予
    - 数据来源：分类维度复用 `categoryReport`（按名称归入集合），商户维度复用 `dimensionReport(dim=merchant)`；不基于 `note` 关键词匹配
    - 匹配对象已删除或无名称 → 固定回退名（分类 `已删除分类`、商户 `已删除商户`），不因缺名丢弃标签或漏计交易
    - 携带判定维度、维度 id/名称、匹配笔数、金额、占比及其对应阈值
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 2.3_

  - [x] 5.5 实现 `LATE_NIGHT_KING`（夜宵王）评估器
    - 取目标月半开区间内当前账本、未删除、`type=expense` 交易，按 `Asia/Shanghai` 内存派生每笔本地小时（0–23），不新增任何数据库查询
    - 依可配置夜宵时段（默认 `[22:00, 24:00) ∪ [00:00, 04:00)`，半开）统计夜宵笔数 `lateNightCount`
    - 达标当且仅当 `lateNightCount ≥ lateNightCountMin`（默认 5）；小于下限（含 0）不授予
    - 携带夜宵时段、夜宵笔数、笔数下限
    - _Requirements: 7.1, 7.2, 7.4, 7.5, 2.3_

- [x] 6. 实现强度打分、确定性挑选、去重与数量上限
  - 为每枚达标标签计算**有限、非负、6 位小数**确定性强度分 `strengthScore`（判定指标相对阈值的归一化比值）：`SAVINGS_MASTER`=`max(savings/min, savingsRate/min)`；`FINANCE_STAR`=`saveRate/min`；`BUDGET_MASTER`=`usedMax/max(usedRate, ε)`（使用率越低越高，保证有限）；行为类=`max(count/min, pct/min, amount/min)`（仅已配置下限参与）；`LATE_NIGHT_KING`=`lateNightCount/min`
  - 阈值为 0 或无法按比值计算时记 `strengthScore=0`，该标签**仍参与**排序与挑选，且不中断其余打分；结果一律 `setScale(6, HALF_UP)`
  - 排序：按 `strengthScore` 降序；相等时按固定标签优先级全序决胜：`SAVINGS_MASTER > FINANCE_STAR > BUDGET_MASTER > TAKEOUT_EXPLORER > COFFEE_COLLECTOR > LATE_NIGHT_KING > TRAVEL_ENTHUSIAST > SHOPPING_LIFER`
  - 去重：同一 `tagKey` 至多保留一枚（保留强度分更高者、再按决胜键取唯一）
  - 截断：取前 N（`props.maxCountClamped()`，钳制 1–8，默认 4）；达标少于 N 时按同序返回全部、不补足
  - 幂等可复现：纯函数式排序/挑选 + 全序决胜键，保证同输入同 N 多次调用结果与顺序完全一致
  - _Requirements: 9.1, 9.2, 9.3, 9.5, 9.6, 9.7, 9.8, 2.6, 2.7_

- [x] 7. 组装 `PersonalityTagsResponse`、兜底语义与隐私白名单
  - 挑选后标签列表非空 → `isFallback=false`、`fallbackText=null`、`tags=1..N` 枚
  - 挑选后为空（partial/未来月跳空 / 无标签达标 / 数据不足）→ `isFallback=true`、`fallbackText` 为一条非空鼓励文案（1..60 字符，来源为空则用系统内置默认）、`tags` 空列表
  - 兜底态与非兜底态均携带 `month`（`YYYY-MM`）与 `monthStatus`（`partial`/`final`）
  - 净化：仅装配白名单派生统计 + 标题/表情/维度名 + 标签文案，确保响应不含 email/令牌/其它账本数据/`external_id`/原始备注全文/商户原始标识；返回前检测到任一被禁字段则移除、照常返回其余合法字段
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 13.3, 13.4, 13.5_

- [x] 8. 将 `TagNarrator` 接入 `PersonalityTagService`
  - 对每枚挑选后的标签调用 `narrator.render(tag)` 生成 `narrativeText`
  - 渲染失败（缺标题或缺全部关键数值）→ `narrativeText=null` 标记生成失败原因、保留机器字段、整体不报错
  - _Requirements: 8.1, 8.9_

- [x] 9. 在 `ReportController` 新增只读端点 `GET /api/reports/personality-tags`
  - 注入 `PersonalityTagService`（构造器新增参数）
  - 新增 `@GetMapping("/personality-tags")` 方法，`month` 参数可选
  - `ledgerId = currentLedger.requireLedgerId()`（未认证/账本不可访问在此抛既有错误）
  - `month` 缺省 → `YearMonth.now(clock)`；否则复用既有 `parseMonth(month, "month")`（非法格式/月份非 01–12 → `REPORT_PARAM_INVALID`）
  - 返回 `ResponseEntity.ok(personalityTagService.tags(ledgerId, ym))`；不新增任何错误码、不改动既有端点
  - 鉴权 → 账本 → 参数 的错误优先级由既有链路天然保证（`requireLedgerId()` 先于 `parseMonth`）
  - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.9, 11.11, 11.12, 11.13, 14.3_

- [ ] 10. 后端属性测试（对应 Correctness Properties 1–15）
  - [ ]* 10.1 编写属性测试 Property 1（响应完整性与月状态正确）
    - **Feature: fun-personality-tags, Property 1: 响应完整性与月状态正确**
    - **Validates: Requirements 1.1, 1.3, 1.4, 10.5**
    - 新建 `PersonalityTagServicePropertyTest`（`@DataJpaTest` + H2（MODE=MySQL）+ 真实 Repository + 固定 `Clock`），随机化目标月与当前时刻相对位置覆盖 partial/final/未来月；每次迭代独立 `ledgerId`；≥100 次迭代

  - [ ]* 10.2 编写属性测试 Property 2（同口径一致，模型对照）
    - **Feature: fun-personality-tags, Property 2: 同口径一致（模型对照）**
    - **Validates: Requirements 1.6, 2.1, 3.1, 4.1, 5.1, 6.1, 6.3, 14.5**
    - 以真实 `ReportService.monthlyReport/categoryReport/dimensionReport` 与 `BudgetService.overview` 为参照，断言派生原始指标逐值相等（`isEqualByComparingTo`）；≥100 次迭代

  - [ ]* 10.3 编写属性测试 Property 3（账本隔离）
    - **Feature: fun-personality-tags, Property 3: 账本隔离**
    - **Validates: Requirements 1.5, 11.8**
    - 两账本 A/B 随机交易/预算集，断言 A 的标签结果与「仅存在 A 数据」时逐值相同；≥100 次迭代

  - [ ]* 10.4 编写属性测试 Property 4（金额与占比/比率 2 位小数）
    - **Feature: fun-personality-tags, Property 4: 金额与占比/比率 2 位小数**
    - **Validates: Requirements 1.7**
    - 断言每枚标签金额字段（`currentValue/previousValue/income/savings/budget/used/matchAmount/threshold`）2dp、占比/比率字段（`saveRate/usedRate/matchPercent`）有定义时 2dp；≥100 次迭代

  - [ ]* 10.5 编写属性测试 Property 5（省钱达人门控、算术与角色）
    - **Feature: fun-personality-tags, Property 5: 省钱达人门控、算术与角色**
    - **Validates: Requirements 3.2, 3.4, 3.5, 3.6**
    - 覆盖上月总支出为 0 的无定义分支、节省金额/节省率下限两侧（刚好达到/刚好不足）；≥100 次迭代

  - [ ]* 10.6 编写属性测试 Property 6（理财新星门控、算术与角色）
    - **Feature: fun-personality-tags, Property 6: 理财新星门控、算术与角色**
    - **Validates: Requirements 4.2, 4.3, 4.5, 4.6**
    - 覆盖总收入为 0 的无定义分支、结余≤0 与结余率下限两侧；无任何计入交易时三值取 0.00；≥100 次迭代

  - [ ]* 10.7 编写属性测试 Property 7（预算大师门控与使用率算术）
    - **Feature: fun-personality-tags, Property 7: 预算大师门控与使用率算术**
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5**
    - 覆盖未设预算/预算为 0 的无定义分支、超支与使用率上限两侧；使用率与 `BudgetService` 同源对照；≥100 次迭代

  - [ ]* 10.8 编写属性测试 Property 8（行为类标签匹配、去重、占比与门控）
    - **Feature: fun-personality-tags, Property 8: 行为类标签匹配、去重、占比与门控**
    - **Validates: Requirements 6.2, 6.4, 6.5, 6.6, 6.7**
    - 构造分类/商户同时命中同一标签的交易验证去重只计一次、当月总支出为 0、`note` 含关键词但分类/商户不在集合内不计入、笔数/占比/金额下限两侧；≥100 次迭代

  - [ ]* 10.9 编写属性测试 Property 9（夜宵王本地时段派生与门控）
    - **Feature: fun-personality-tags, Property 9: 夜宵王本地时段派生与门控**
    - **Validates: Requirements 7.1, 7.2, 7.4, 7.5**
    - 以独立的 `Asia/Shanghai` 换算参照对照本地小时；构造 `occurredAt` 恰好落在 22:00/00:00/04:00 边界覆盖半开区间；笔数下限两侧（含 0）；≥100 次迭代

  - [ ]* 10.10 编写属性测试 Property 10（删除/无名维度对象回退命名且不丢弃）
    - **Feature: fun-personality-tags, Property 10: 删除/无名维度对象回退命名且不丢弃**
    - **Validates: Requirements 6.8**
    - 构造指向不存在分类/商户或名称为空的交易，断言回退名固定（`已删除分类`/`已删除商户`）且标签不被丢弃、交易不漏计；≥100 次迭代

  - [ ]* 10.11 编写属性测试 Property 11（确定性、幂等、有界、去重的强度打分与标签挑选）
    - **Feature: fun-personality-tags, Property 11: 确定性、幂等、有界、去重的强度打分与标签挑选**
    - **Validates: Requirements 2.3, 2.6, 2.7, 9.1, 9.2, 9.3, 9.5, 9.6, 9.7, 9.8**
    - 构造并列强度分触发标签优先级决胜、随机 N（含 1、8、越界钳制回退 4）、同输入多次调用断言结果与顺序完全一致、去重与不补足、每枚携带机器字段、独立判定；≥100 次迭代

  - [ ]* 10.12 编写属性测试 Property 12（标签文案正确性：数值一致、禁用词与长度）
    - **Feature: fun-personality-tags, Property 12: 标签文案正确性（含数值一致、禁用词与长度）**
    - **Validates: Requirements 8.1, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9**
    - 断言文案含标题 + 关键数值、数值 == 机器字段、长度 1..60、标题与文案对可枚举禁用词表零命中、生成失败标记；≥100 次迭代

  - [ ]* 10.13 编写属性测试 Property 13（鼓励性兜底语义）
    - **Feature: fun-personality-tags, Property 13: 鼓励性兜底语义**
    - **Validates: Requirements 1.10, 1.11, 10.1, 10.2, 10.3, 10.4, 10.6**
    - 覆盖无标签达标、partial、未来月跳空三条兜底路径与非兜底路径；断言 `isFallback`/`fallbackText`（1..60，来源为空用内置默认）/`tags` 语义；≥100 次迭代

  - [ ]* 10.14 编写属性测试 Property 14（隐私白名单：响应不含被禁字段）
    - **Feature: fun-personality-tags, Property 14: 隐私白名单（响应不含被禁字段）**
    - **Validates: Requirements 13.3, 13.4, 13.5**
    - 将响应序列化为 JSON，断言字段名集合为白名单子集且不含邮箱/令牌样式取值与 `external_id`/`note`；≥100 次迭代

  - [ ]* 10.15 编写属性测试 Property 15（纯只读不写库）
    - **Feature: fun-personality-tags, Property 15: 纯只读不写库**
    - **Validates: Requirements 11.1, 14.1, 14.6**
    - 调用前后对 `transactions`/`categories`/`merchants`/预算表及全表清单做行数与内容快照，断言完全一致（零写入、零 DDL）；异常隔离后数据同样不变；≥100 次迭代

- [ ] 11. 编写服务层边界单元测试、组件单元测试与控制器契约测试
  - [ ]* 11.1 编写 `PersonalityTagServiceTest`（`@DataJpaTest`）服务层边界单元测试
    - 各枚标签典型场景与边界：上月支出为 0 的省钱达人无定义分支（3.5）；总收入为 0 的理财新星分支（4.2、4.6）；未设预算/预算为 0 的预算大师分支（5.5）；行为类「只满足金额/占比/笔数」各分支与去重（6.2、6.6）；当月总支出为 0（6.4）；夜宵恰好 5 笔/差一笔与边界时刻（7.4、7.5）；`partial`/未来月全部跳过 → 兜底（1.10、1.11）；N 边界（1、8、越界回退 4）；兜底来源为空用内置默认文案（10.6）
    - _Requirements: 3.5, 4.2, 4.6, 5.5, 6.2, 6.4, 6.6, 7.4, 7.5, 1.10, 1.11, 9.4, 10.6_

  - [ ]* 11.2 编写 `PersonalityTagPropertiesTest` 单元测试
    - 断言 `maxCountClamped()`（1/8/越界回退 4）、`lateNightWindow()`（非法回退默认时段）、`sanitize()`（负金额/负笔数/越界比率回退默认）
    - _Requirements: 2.5, 3.3, 7.3, 9.4_

  - [ ]* 11.3 编写 `ReportControllerTest`（MockMvc）契约测试补充
    - 缺省 `month` 取当前月（1.2）；无/坏令牌 → `UNAUTHENTICATED` 且响应无标签字段与兜底文案（11.5）；越权 `X-Ledger-Id` → `LEDGER_NOT_ACCESSIBLE`（11.6）；非法 `month`（格式/月份非 01–12）→ `REPORT_PARAM_INVALID`（11.7）；无 `X-Ledger-Id` → 默认账本（11.9）；多错误并存按「鉴权 → 账本 → 参数」优先级（11.11、11.12、11.13）；返回类型为「≤N 枚标签」或「兜底文案」（11.3、11.4）
    - _Requirements: 1.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.9, 11.11, 11.12, 11.13_

  - [ ]* 11.4 编写契约不回归与依赖不可用兜错测试
    - 既有报表/预算/月报/AI 趣味分析/交易接口现有测试保持通过、字段集与错误码集合不变（14.3、14.4）；注入使内部聚合抛错的场景，断言返回指示标签暂不可用的错误响应且不含任何原始数据（13.6）
    - _Requirements: 13.6, 14.3, 14.4_

- [x] 12. Checkpoint - 确保后端所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. miniapp 新增趣味人格标签 API 方法
  - 在 `miniapp/src/api/report.js` 新增 `personalityTags(month)`，`http.get('/reports/personality-tags?month=' + month)`
  - 补充 JSDoc 说明返回 `{ month, monthStatus, isFallback, fallbackText, tags:[PersonalityTag...] }`
  - 沿用 `utils/request.js` 网络层（自动带 Authorization 与 X-Ledger-Id；401 清 token 跳登录；LEDGER_NOT_ACCESSIBLE 清本地账本重试一次）
  - _Requirements: 11.1, 11.6_

- [x] 14. 新增 `miniapp/src/utils/personalityTags.js` 纯逻辑模块
  - [x] 14.1 实现降级决策与标签展示映射纯函数
    - 常量 `PERSONALITY_TAGS_TIMEOUT_MS = 5000`
    - `shouldFetchTags(isLoggedIn, isAll)`：已登录且非全部账本聚合视图才请求
    - `raceWithTimeout(promise, ms)`：`Promise.race` + 定时器超时包装
    - `resolveTagsState({ isLoggedIn, isAll, fetchTags, timeoutMs, isStale })`：返回 `{ requested, stale, tags, tagsVisible }`；未登录/聚合视图不请求不展示；失败或超时 `tagsVisible=false`、`tags=null` 静默隐藏；成功但空体静默隐藏；过期响应丢弃；从不触碰其它报表状态
    - `tagToDisplay(tag)`：映射为展示项（标题/表情，优先 `narrativeText`，缺失时降级为「标题 + 关键数值」兜底串），仅取白名单字段（`tagKey`、`title`、`emoji`、`dimensionName`、关键数值、`narrativeText`），绝不引用邮箱/令牌/其它账本数据
    - _Requirements: 1.9, 12.1, 12.2, 12.4, 12.5, 12.6, 12.7, 13.3, 13.4_

  - [ ]* 14.2 编写属性测试 Property 16（前端静默降级，纯逻辑）
    - **Feature: fun-personality-tags, Property 16: 前端静默降级（`utils/personalityTags.js` 纯逻辑）**
    - **Validates: Requirements 1.9, 12.1, 12.2, 12.4, 12.5, 12.6, 12.7**
    - vitest 覆盖：未登录/聚合视图不请求且不可见；失败或 5000ms 超时 `tagsVisible=false`/`tags=null`；成功但空体静默隐藏；`stale`（请求期间切换账本/月份）跳过应用；决策只产出趣味人格标签自身状态、从不返回或改动其它报表字段

  - [ ]* 14.3 编写 `tagToDisplay` 字段隔离单测
    - 断言展示映射只取白名单字段（`tagKey`、`title`、`emoji`、`dimensionName`、关键数值、`narrativeText`），绝不引用邮箱/令牌/其它账本数据
    - _Requirements: 13.3, 13.4_

- [x] 15. `report.vue` 趣味人格标签卡片区块（数据加载 + 静默降级 + 渲染）
  - 在既有 `load()` 中：当 `!ledgerStore.isAll` 且已登录（有 token）时并行请求 `personalityTags(month.value)`，用独立响应式状态 `tags`/`tagsVisible`，与其它报表相互独立
  - 5000ms 超时（复用 `raceWithTimeout`）；失败或超时 → `tagsVisible=false` 静默隐藏，不弹阻断性错误弹窗，不影响分类占比/趋势/智能月报/AI 趣味分析等既有模块；成功但标签体为空 → 静默隐藏
  - 未登录不发起请求也不展示；全部账本聚合视图（`ledgerStore.isAll`）不发起也不展示；请求期间切账本/月份使响应过期 → 丢弃过期响应不覆盖卡片；切换账本或目标月时重新请求并在 2 秒内刷新
  - 区块渲染：以标签墙形式展示目标月标识 + 月状态徽标；`isFallback=true` 渲染鼓励文案；否则逐枚渲染标签芯片（表情 + 标题 + `narrativeText`，正向暖色调）；空/缺字段安全兜底、不报错
  - _Requirements: 1.8, 1.9, 8.1, 10.4, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7_

- [x] 16. Final Checkpoint - 确保后端与前端全部测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选（测试相关），可为更快的 MVP 跳过；核心实现任务从不标记为可选。
- 每个任务引用具体需求子条款以保证可追溯性。
- 属性测试对应 `design.md` 的 Correctness Properties（Property 1–16），每条属性为独立子任务；Property 2 及门控/选择类（5、6、7、8、9）采用模型对照（以真实 `ReportService`/`BudgetService` 或就地暴力实现为参照），夜宵本地小时以独立 `Asia/Shanghai` 换算参照对照。
- 后端属性测试沿用仓库既有 jqwik + `@DataJpaTest` + H2（MODE=MySQL）+ 真实 Repository 范式，不使用 mock；固定注入 `Clock`（Asia/Shanghai）获得确定性；每次迭代使用独立 `ledgerId` 隔离随机数据。
- 前端 `utils/personalityTags.js` 为纯逻辑单一事实源，配合 vitest 覆盖降级决策与字段隔离。
- 本 spec 纯只读、纯增量：不新增数据库表、不新增/改动 Flyway 脚本、不新增任何错误码、不新增任何 repository 查询、不改动任何既有接口契约。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "2"] },
    { "id": 1, "tasks": ["3.1"] },
    { "id": 2, "tasks": ["4", "3.2"] },
    { "id": 3, "tasks": ["5.1", "5.2", "5.3", "5.4", "5.5"] },
    { "id": 4, "tasks": ["6"] },
    { "id": 5, "tasks": ["7"] },
    { "id": 6, "tasks": ["8"] },
    { "id": 7, "tasks": ["9"] },
    { "id": 8, "tasks": ["10.1", "10.2", "10.3", "10.4", "10.5", "10.6", "10.7", "10.8", "10.9", "10.10", "10.11", "10.12", "10.13", "10.14", "10.15", "11.1", "11.2", "11.3", "11.4", "13"] },
    { "id": 9, "tasks": ["14.1"] },
    { "id": 10, "tasks": ["14.2", "14.3", "15"] }
  ]
}
```
