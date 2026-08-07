# Implementation Plan: AI 趣味分析（AI Fun Analysis）

## Overview

本计划把 AI 趣味分析设计（`design.md`）拆解为一系列增量式编码任务。所有任务严守「纯只读、纯增量」边界：
后端新增一个只读组合器 `AiInsightService`、一个纯函数文案渲染组件 `InsightNarrator`、一个可配置阈值载体
`AiInsightProperties`、一组只读响应 DTO（`AiInsightsResponse` + 内嵌 `AiInsight`），并在既有 `ReportController`
上新增单个只读端点 `GET /api/reports/ai-insights`；miniapp 新增 `api/report.js#aiInsights`、`utils/insights.js`
纯逻辑模块与 `pages/report/report.vue` 的 AI 趣味分析卡片区块（静默降级）。**不新增数据库表、不新增/改动 Flyway
脚本、不新增任何错误码、不改动任何既有接口契约、不新增任何 repository 查询**——五类洞察的原始指标全部复用既有
`ReportService.monthlyReport / categoryReport / dimensionReport`。

实现语言沿用仓库既有技术栈：后端 Java 17 + Spring Boot（服务 / DTO / `@ConfigurationProperties`），测试用
jqwik + `@DataJpaTest` + H2（`MODE=MySQL`）+ 真实 Repository + 固定注入 `Asia/Shanghai` 的 `Clock`、以真实
`ReportService` 做模型对照；前端 miniapp uni-app / Vue 3（JavaScript），测试用 vitest。

每个任务在前一步基础上增量推进，最终把后端与前端串联起来。属性测试对应设计文档 Correctness Properties 的
16 条属性，标注格式为 `Feature: ai-fun-analysis, Property {n}: {text}`，并保留 `Validates: Requirements X.Y`。

## Tasks

- [x] 1. 新增 AI 趣味分析响应 DTO（`AiInsightsResponse` + 内嵌 `AiInsight`）
  - 在 `src/main/java/com/damien/youyu/api/dto/` 新建 `AiInsightsResponse.java`
  - 顶层字段：`month`、`monthStatus`、`isFallback`、`fallbackText`、`insights`（`List<AiInsight>`）
  - 内嵌 `record AiInsight`，字段（异构五类共用、未用字段以 null 表达）：`type`、`dimension`、`dimensionId`、`dimensionName`、`currentValue`、`previousValue`、`currentCount`、`previousCount`、`deltaAmount`、`deltaCount`、`changeRate`、`streakMonths`、`streakStartMonth`、`streakEndMonth`、`direction`、`role`、`score`、`narrativeText`
  - 补充 Javadoc 明确各字段的 null 语义（`changeRate` 基线为 0 → null；`SAVINGS_TOTAL` 维度字段为 null；仅 `FREQUENCY_DELTA` 携带 `currentCount/previousCount/deltaCount`；仅 `TREND_STREAK` 携带 streak 字段；`narrativeText` 生成失败 → null）；DTO 字段集即隐私白名单，绝不含 email/令牌/其它账本数据/`external_id`/原始备注/商户原始标识
  - _Requirements: 1.1, 8.1, 9.4, 9.5, 9.6, 12.3, 12.4, 13.2_

- [x] 2. 新增可配置阈值载体 `AiInsightProperties`（`@ConfigurationProperties`）
  - 在 `src/main/java/com/damien/youyu/` 合适包下新建 `AiInsightProperties.java`，前缀如 `youyu.ai-insight`
  - 字段与默认值：`maxCount=5`（N）、`categoryRatePctMin=10.00`、`categoryAmountMin=20.00`、`savingsAmountMin=50.00`、`frequencyRatePctMin=20.00`、`frequencyCountMin=2`、`streakMinMonths=3`
  - `maxCount` 读取时钳制到 1–20（越界向边界取整），提供已钳制的取值方法
  - 补充 Javadoc 标注每个阈值对应的需求条款
  - _Requirements: 2.3, 3.4, 3.5, 4.4, 5.4, 5.6, 7.2, 10.1_

- [x] 3. 实现中文叙事渲染组件 `InsightNarrator`（纯函数）
  - [x] 3.1 实现 `InsightNarrator.render(AiInsight)`
    - 在 `src/main/java/com/damien/youyu/service/` 新建 `InsightNarrator.java`，纯函数、无任何 I/O、不注入任何 HTTP 客户端或外部服务
    - 为五类洞察各实现方向分支模板（`CATEGORY_DELTA`/`SAVINGS_TOTAL`/`FREQUENCY_DELTA`/`TREND_STREAK`/`TOP_MOVER`，方向按 `direction` 或 `role`）
    - 文案中每个数值直接取自洞察机器字段并按同口径格式化（金额 2dp、变化率百分比 2dp），保证「文案数值 == 机器字段」逐一相等
    - 每条文案至少含维度名称 + 变化率/金额/次数三者之一；长度不超过 100 个中文字符
    - 措辞极性：下降/减少/改善（`DOWN` 或 `role=IMPROVE`）→ 正向或中性、不含任何提醒/警示词；上升/增加/超支（`UP` 或 `role=OVERSPEND`）→ 提醒性措辞
    - 回退名：分类名缺失/空白 → `已删除分类`，商户名缺失/空白 → `已删除商户`（固定、可复现），不因名称缺失丢弃
    - 生成失败（缺全部关键数值）→ 返回 null（由服务标记生成失败），不抛错
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 2.7, 4.6, 12.1, 12.2_

  - [x]* 3.2 编写 `InsightNarrator` 单元测试
    - 逐类逐方向断言数值一致（文案内数值 == 机器字段，金额 2dp、变化率百分比 2dp）、长度 ≤100、措辞极性正确
    - 覆盖回退名分支与生成失败（缺全部关键数值返回 null）分支
    - _Requirements: 8.2, 8.4, 8.5, 8.6, 8.7, 8.8_

- [x] 4. 搭建只读组合器 `AiInsightService` 骨架（月状态 / partial 短路 / 基线检查）
  - 在 `src/main/java/com/damien/youyu/service/` 新建 `AiInsightService.java`，标注 `@Service`
  - 注入 `ReportService`、`CategoryRepository`、`MerchantRepository`、`Clock`、`AiInsightProperties`、`InsightNarrator`（仅编排既有服务，不新增任何 repository 查询）
  - `insights(Long ledgerId, YearMonth month)` 标注 `@Transactional(readOnly = true)`
  - 月状态：`status = month.isBefore(YearMonth.now(clock)) ? "final" : "partial"`
  - partial 短路：`status == "partial"` → v1 五类均依赖完整月对比，候选为空 → 走鼓励兜底（仍携带 `month`、`monthStatus`）
  - 可比基线检查：`prev = month.minusMonths(1)`，取 `monthlyReport(ledgerId, prev)`，若上月总收入与总支出均为 `0.00`（无可比基线）→ 候选为空 → 走鼓励兜底
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 9.1, 9.3, 9.6, 1.10_

- [x] 5. 实现五类候选洞察构建器（`AiInsightService` 内部，纯派生）
  - [x] 5.1 实现 `CATEGORY_DELTA`（分类消费涨跌）候选构建
    - 取 `categoryReport(ledgerId, M 全月范围)` 与 `categoryReport(ledgerId, prev 全月范围)`（默认 EXPENSE 口径），按 `Asia/Shanghai` 半开区间
    - 对 M 的每个支出分类计算 `deltaAmount = cur − prev`（2dp，HALF_UP，可负）、`changeRate`（仅 `prev>0` 时 = `deltaAmount ÷ prev × 100`，2dp，否则 null）
    - 门控：仅当 `prev>0` 且 `|changeRate| ≥ categoryRatePctMin` 且 `|deltaAmount| ≥ categoryAmountMin` 才生成候选；`prev=0` 不生成
    - 方向：`cur<prev`→`DOWN`、`cur>prev`→`UP`；携带分类 id/名称、cur、prev、`deltaAmount`、`changeRate`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.8, 1.6, 1.7_

  - [x] 5.2 实现 `SAVINGS_TOTAL`（比上月节省/多花总额）候选构建
    - 取 `monthlyReport(M)`、`monthlyReport(prev)` 的月度总支出
    - `savings = prevTotalExpense − curTotalExpense`（2dp，可负）；`changeRate` 仅 `prevTotalExpense>0` 时 = `savings ÷ prevTotalExpense × 100`（2dp）
    - 门控：仅当 `prevTotalExpense>0` 且 `|savings| ≥ savingsAmountMin` 才生成；`prevTotalExpense=0` 不生成且不报错
    - 角色：`savings>0`→`IMPROVE`（节省）、`savings<0`→`OVERSPEND`（多花）；维度为账本总额（`dimension=null, dimensionId=null`）
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 1.6, 1.7_

  - [x] 5.3 实现 `FREQUENCY_DELTA`（商户或分类频次变化）候选构建
    - 分类维度取 `categoryReport` 的每分类笔数；商户维度取 `dimensionReport(dim=merchant)` 的每商户笔数（M 与 prev）
    - `deltaCount = curCount − prevCount`（整数）；`countRate` 仅 `prevCount>0` 时 = `(curCount − prevCount) ÷ prevCount × 100`（2dp，HALF_UP，否则 null）
    - 门控：仅当 `prevCount>0` 且 `|countRate| ≥ frequencyRatePctMin` 且 `|deltaCount| ≥ frequencyCountMin` 才生成
    - 方向：`curCount<prevCount`→`DOWN`（减少）、`>`→`UP`（增加）；携带维度、维度 id/名称、`currentCount`、`previousCount`、`deltaCount`、`changeRate`；不使用 `note` 关键词匹配
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7_

  - [x] 5.4 实现 `TREND_STREAK`（连续涨跌趋势）候选构建
    - 对每个 `CATEGORY` 支出分类，由 `categoryReport(M−k)`（k=0..5，至多 6 次）构建 M−5..M 升序、无数据月计 `0.00` 的按月分类支出序列
    - 以 M 为锚点倒序逐一比较相邻两月：连续严格递减或严格递增；遇相等（含均为 0.00）或方向反转即终止；连续月数含两端计数
    - 门控：连续递减或递增月数 `≥ streakMinMonths` 才生成；方向递减→`DOWN`（连续下降）、递增→`UP`（连续上升）
    - 携带维度（`CATEGORY`）、分类 id/名称、`direction`、`streakMonths`、`streakStartMonth`、`streakEndMonth`（= M）
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [x] 5.5 实现 `TOP_MOVER`（最大改善/最超支分类）候选构建
    - 候选集合 = 「prev 分类支出 > 0」的分类；每个候选 `deltaAmount = cur − prev`
    - 集合非空时：选 `deltaAmount` 最小者为「改善」（`role=IMPROVE`）、最大者为「超支」（`role=OVERSPEND`），各生成一条；并列时分别以分类 id 升序决胜
    - 改善与超支落在同一分类（集合仅 1 个分类、min==max）时依去重规则只保留一条，`role` 由 `deltaAmount` 符号决定（`<0`→IMPROVE、`>0`→OVERSPEND、`==0` 不生成）
    - 携带分类 id/名称、cur、prev、`deltaAmount`、`changeRate`、`role`；候选集合为空 → 不生成任何 `TOP_MOVER`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 6. 实现显著度打分、确定性挑选、去重与数量上限
  - 为每条候选计算非负确定性 `score`（`BigDecimal`）：金额类（`CATEGORY_DELTA`/`SAVINGS_TOTAL`/`TOP_MOVER`）= `|deltaAmount|`；`FREQUENCY_DELTA` = `|deltaCount|`；`TREND_STREAK` = `|M值 − 段起始月值|`
  - 去重：同一 `(type, dimension, dimensionId)` 至多一条，保留同键中打分更高者、再按决胜键取唯一
  - 排序：按 `score` 降序；打分相等时先按洞察类型全序 `SAVINGS_TOTAL > TOP_MOVER > CATEGORY_DELTA > TREND_STREAK > FREQUENCY_DELTA`、再按维度 id 升序（`SAVINGS_TOTAL` 视 id 为 `-1` 恒最前）
  - 截断：取前 N（`props.maxCount`，钳制 1–20）；候选少于 N 时按同序返回全部、不补足
  - 幂等可复现：纯函数式排序/挑选 + 全序决胜键，保证同输入同 N 多次调用结果完全一致
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7_

- [x] 7. 组装 `AiInsightsResponse`、兜底语义与隐私白名单
  - 挑选后洞察列表非空 → `isFallback=false`、`fallbackText=null`、`insights=1..N` 条
  - 挑选后为空（partial 跳空 / 上月无基线 / 无候选）→ `isFallback=true`、`fallbackText` 为一条非空鼓励文案（1..100 字符）、`insights` 空列表
  - 兜底态与非兜底态均携带 `month`（`YYYY-MM`）与 `monthStatus`（`partial`/`final`）
  - 净化：仅装配白名单派生统计 + 名称 + 叙事文案，确保响应不含 email/令牌/其它账本数据/`external_id`/原始备注/商户原始标识
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 12.3, 12.4, 12.5_

- [x] 8. 将 `InsightNarrator` 接入 `AiInsightService`
  - 对每条挑选后的洞察调用 `narrator.render(insight)` 生成 `narrativeText`
  - 渲染失败（缺全部关键数值）→ `narrativeText=null` 标记生成失败、保留机器字段、整体不报错
  - _Requirements: 8.1, 8.8_

- [x] 9. 在 `ReportController` 新增只读端点 `GET /api/reports/ai-insights`
  - 注入 `AiInsightService`（构造器新增参数）
  - 新增 `@GetMapping("/ai-insights")` 方法，`month` 参数可选
  - `ledgerId = currentLedger.requireLedgerId()`（未认证/账本不可访问在此抛既有错误）
  - `month` 缺省 → `YearMonth.now(clock)`；否则复用既有 `parseMonth(month, "month")`（非法格式抛 `REPORT_PARAM_INVALID`）
  - 返回 `ResponseEntity.ok(aiInsightService.insights(ledgerId, ym))`；不新增任何错误码、不改动既有端点
  - 鉴权 → 账本 → 参数 的错误优先级由既有链路天然保证（`requireLedgerId()` 先于 `parseMonth`）
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.6, 10.7, 10.8, 13.3_

- [x] 10. 后端属性测试（对应 Correctness Properties 1–15）
  - [x]* 10.1 编写属性测试 Property 1（响应完整性与月状态正确）
    - **Feature: ai-fun-analysis, Property 1: 响应完整性与月状态正确**
    - **Validates: Requirements 1.1, 1.3, 1.4, 9.6**
    - 新建 `AiInsightServicePropertyTest`（`@DataJpaTest` + H2（MODE=MySQL）+ 真实 Repository + 固定 `Clock`），随机化目标月与当前时刻相对位置覆盖 partial/final；每次迭代独立 `ledgerId`；≥100 次迭代

  - [x]* 10.2 编写属性测试 Property 2（同口径口径一致，模型对照）
    - **Feature: ai-fun-analysis, Property 2: 同口径口径一致（模型对照）**
    - **Validates: Requirements 1.6, 2.1, 3.1, 4.1, 4.2, 5.1, 6.1, 13.5**
    - 以真实 `ReportService.monthlyReport/categoryReport/dimensionReport` 为参照，断言派生原始指标逐值相等（`isEqualByComparingTo`）；≥100 次迭代

  - [x]* 10.3 编写属性测试 Property 3（账本隔离）
    - **Feature: ai-fun-analysis, Property 3: 账本隔离**
    - **Validates: Requirements 1.5, 10.5**
    - 两账本 A/B 随机交易集，断言 A 的洞察结果与「仅存在 A 交易」时逐值相同；≥100 次迭代

  - [x]* 10.4 编写属性测试 Property 4（金额与变化率 2 位小数）
    - **Feature: ai-fun-analysis, Property 4: 金额与变化率 2 位小数**
    - **Validates: Requirements 1.7**
    - 断言每条洞察金额字段（`currentValue/previousValue/deltaAmount/score`）2dp、`changeRate` 有定义时 2dp；≥100 次迭代

  - [x]* 10.5 编写属性测试 Property 5（分类涨跌门控、字段、方向与变化率）
    - **Feature: ai-fun-analysis, Property 5: 分类涨跌门控、字段、方向与变化率**
    - **Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.8**
    - 随机化阈值边界附近取值（刚好达到/刚好不足）覆盖门控两侧；≥100 次迭代

  - [x]* 10.6 编写属性测试 Property 6（节省总额门控、算术与角色）
    - **Feature: ai-fun-analysis, Property 6: 节省总额门控、算术与角色**
    - **Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8**
    - 覆盖上月总支出为 0 的无定义分支与金额下限两侧；≥100 次迭代

  - [x]* 10.7 编写属性测试 Property 7（频次变化门控、笔数算术与方向）
    - **Feature: ai-fun-analysis, Property 7: 频次变化门控、笔数算术与方向**
    - **Validates: Requirements 4.3, 4.4, 4.5**
    - 覆盖分类维度与商户维度、上月笔数为 0 的无定义分支、次数与变化率下限两侧；≥100 次迭代

  - [x]* 10.8 编写属性测试 Property 8（连续涨跌段检测、门控与方向，暴力参照）
    - **Feature: ai-fun-analysis, Property 8: 连续涨跌段检测、门控与方向**
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5, 5.6**
    - 构造严格单调、含相等、含反转的按月序列，以暴力参照实现对照连续月数；覆盖恰好达到/差一个月下限；≥100 次迭代

  - [x]* 10.9 编写属性测试 Property 9（最大改善/最超支选择与确定性决胜）
    - **Feature: ai-fun-analysis, Property 9: 最大改善/最超支选择与确定性决胜**
    - **Validates: Requirements 6.2, 6.3, 6.4, 6.5**
    - 构造并列 min/max 触发分类 id 升序决胜、候选集合为空、min==max 同分类去重；≥100 次迭代

  - [x]* 10.10 编写属性测试 Property 10（删除/无名维度对象回退命名且不丢弃）
    - **Feature: ai-fun-analysis, Property 10: 删除/无名维度对象回退命名且不丢弃**
    - **Validates: Requirements 2.7, 4.6**
    - 构造指向不存在分类/商户或名称为空的交易，断言回退名固定且洞察不被丢弃；≥100 次迭代

  - [x]* 10.11 编写属性测试 Property 11（确定性、幂等、有界与去重的洞察挑选）
    - **Feature: ai-fun-analysis, Property 11: 确定性、幂等、有界与去重的洞察挑选**
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 7.6**
    - 构造并列打分触发决胜键、随机 N（含 1、20、越界钳制）、同输入多次调用断言结果与顺序完全一致、去重与不补足；≥100 次迭代

  - [x]* 10.12 编写属性测试 Property 12（叙事文案正确性：数值一致与措辞极性）
    - **Feature: ai-fun-analysis, Property 12: 叙事文案正确性（含数值一致与措辞极性）**
    - **Validates: Requirements 8.1, 8.2, 8.4, 8.5, 8.6, 8.7, 8.8**
    - 断言文案含维度名 + 关键数值、数值 == 机器字段、长度 ≤100、方向措辞极性、生成失败标记；≥100 次迭代

  - [x]* 10.13 编写属性测试 Property 13（鼓励性兜底语义）
    - **Feature: ai-fun-analysis, Property 13: 鼓励性兜底语义**
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5**
    - 覆盖上月无基线、无候选、partial 跳空三条兜底路径与非兜底路径；断言 `isFallback`/`fallbackText`/`insights` 语义；≥100 次迭代

  - [x]* 10.14 编写属性测试 Property 14（隐私白名单：响应不含被禁字段）
    - **Feature: ai-fun-analysis, Property 14: 隐私白名单（响应不含被禁字段）**
    - **Validates: Requirements 12.3, 12.4, 12.5**
    - 将响应序列化为 JSON，断言字段名集合为白名单子集且不含邮箱/令牌样式取值与 `external_id`/`note`；≥100 次迭代

  - [x]* 10.15 编写属性测试 Property 15（纯只读不写库）
    - **Feature: ai-fun-analysis, Property 15: 纯只读不写库**
    - **Validates: Requirements 13.1**
    - 调用前后对 `transactions`/`categories`/`merchants` 及全表清单做行数与内容快照，断言完全一致（零写入、零 DDL）；≥100 次迭代

- [x]* 11. 编写服务层边界单元测试与控制器契约测试
  - `AiInsightServiceTest`（`@DataJpaTest`）：阈值刚好达到/刚好不足；上月基线为 0 的无定义分支（2.8、3.8、4.3、6.5）；连续段恰好达到/差一个月（5.6）；`TOP_MOVER` 同分类去重取舍；partial 全部跳过 → 兜底；N 边界（1、20、越界钳制）；文案长度上界与措辞极性（8.5–8.7）；生成失败分支（8.8）
  - `ReportControllerTest`（MockMvc）：缺省 `month` 取当前月（1.2）；无/坏令牌 → `UNAUTHENTICATED` 且响应无洞察字段（10.2）；越权 `X-Ledger-Id` → `LEDGER_NOT_ACCESSIBLE`（10.3）；非法 `month` → `REPORT_PARAM_INVALID`（10.4）；无 `X-Ledger-Id` → 默认账本（10.7）；多错误并存按「鉴权 → 账本 → 参数」优先级（10.8）；返回类型为「≤N 条洞察」或「兜底文案」（10.1）
  - _Requirements: 1.2, 2.8, 3.8, 4.3, 5.6, 6.5, 8.5, 8.6, 8.7, 8.8, 10.1, 10.2, 10.3, 10.4, 10.7, 10.8_

- [x] 12. Checkpoint - 确保后端所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. miniapp 新增 AI 趣味分析 API 方法
  - 在 `miniapp/src/api/report.js` 新增 `aiInsights(month)`，`http.get('/reports/ai-insights?month=' + month)`
  - 补充 JSDoc 说明返回 `{ month, monthStatus, isFallback, fallbackText, insights:[AiInsight...] }`
  - 沿用 `utils/request.js` 网络层（自动带 Authorization 与 X-Ledger-Id；401 清 token 跳登录；LEDGER_NOT_ACCESSIBLE 清本地账本重试一次）
  - _Requirements: 10.1, 10.3_

- [x] 14. 新增 `miniapp/src/utils/insights.js` 纯逻辑模块
  - [x] 14.1 实现降级决策与展示映射纯函数
    - 常量 `AI_INSIGHTS_TIMEOUT_MS = 5000`
    - `shouldFetchInsights(isLoggedIn, isAll)`：已登录且非全部账本聚合视图才请求
    - `raceWithTimeout(promise, ms)`：`Promise.race` + 定时器超时包装
    - `resolveInsightsState({ isLoggedIn, isAll, fetchInsights, timeoutMs, isStale })`：返回 `{ requested, stale, insights, insightsVisible }`；未登录/聚合视图不请求不展示；失败或超时 `insightsVisible=false`、`insights=null` 静默隐藏；从不触碰其它报表状态
    - `insightToDisplay(insight)`：映射为展示项（图标/色调按方向、优先 `narrativeText`，缺失时降级为「维度名 + 关键数值」兜底串），仅取白名单字段，绝不引用邮箱/令牌/其它账本数据
    - _Requirements: 1.9, 11.1, 11.2, 11.4, 11.5, 12.3, 12.4_

  - [x]* 14.2 编写属性测试 Property 16（前端静默降级，纯逻辑）
    - **Feature: ai-fun-analysis, Property 16: 前端静默降级（`utils/insights.js` 纯逻辑）**
    - **Validates: Requirements 11.1**
    - vitest 覆盖：未登录/聚合视图不请求且不可见；失败或 5000ms 超时 `insightsVisible=false`/`insights=null`；决策只产出 AI 自身状态、从不返回或改动其它报表字段

  - [x]* 14.3 编写 `insightToDisplay` 字段隔离单测
    - 断言展示映射只取白名单字段（维度名、关键数值、`narrativeText`），绝不引用邮箱/令牌/其它账本数据
    - _Requirements: 12.3, 12.4_

- [x] 15. `report.vue` AI 趣味分析卡片区块（数据加载 + 静默降级 + 渲染）
  - 在既有 `load()` 中：当 `!ledgerStore.isAll` 且已登录（有 token）时并行请求 `aiInsights(month.value)`，用独立响应式状态 `insights`/`insightsVisible`，与其它报表相互独立
  - 5000ms 超时（复用 `raceWithTimeout`）；失败或超时 → `insightsVisible=false` 静默隐藏，不弹阻断性错误弹窗，不影响分类占比/趋势/智能月报等既有模块
  - 未登录不发起请求也不展示；全部账本聚合视图（`ledgerStore.isAll`）不发起也不展示；切换账本或目标月时重新请求并在 2 秒内刷新
  - 区块渲染：目标月标识 + 月状态徽标；`isFallback=true` 渲染鼓励文案；否则逐条渲染洞察叙事卡片（优先 `narrativeText`，方向色调：改善/下降暖绿中性、超支/上升提醒橙）；空/缺字段安全兜底、不报错
  - _Requirements: 1.8, 1.9, 8.1, 9.4, 11.1, 11.2, 11.3, 11.4, 11.5_

- [x] 16. Final Checkpoint - 确保后端与前端全部测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选（测试相关），可为更快的 MVP 跳过；核心实现任务从不标记为可选。
- 每个任务引用具体需求子条款以保证可追溯性。
- 属性测试对应 `design.md` 的 Correctness Properties（Property 1–16），每条属性为独立子任务；Property 2 及门控/选择类（5、6、7、8、9）采用模型对照（以真实 `ReportService` 或就地暴力实现为参照）。
- 后端属性测试沿用仓库既有 jqwik + `@DataJpaTest` + H2（MODE=MySQL）+ 真实 Repository 范式，不使用 mock；固定注入 `Clock`（Asia/Shanghai）获得确定性；每次迭代使用独立 `ledgerId` 隔离随机数据。
- 前端 `utils/insights.js` 为纯逻辑单一事实源，配合 vitest 覆盖降级决策与字段隔离。
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
    { "id": 8, "tasks": ["10.1", "10.2", "10.3", "10.4", "10.5", "10.6", "10.7", "10.8", "10.9", "10.10", "10.11", "10.12", "10.13", "10.14", "10.15", "11", "13"] },
    { "id": 9, "tasks": ["14.1"] },
    { "id": 10, "tasks": ["14.2", "14.3", "15"] }
  ]
}
```
