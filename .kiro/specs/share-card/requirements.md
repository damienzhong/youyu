# Requirements Document

## Introduction

「有余」已经把用户在产品里的坚持与成长沉淀成了一组可展示的数据：智能月报
（`/api/reports/monthly-digest`：本月收入/支出/结余等九模块）、AI 趣味分析
（`/api/reports/ai-insights`）、趣味人格标签（`/api/reports/personality-tags`）、
连续记账（`/api/streak`：今日打卡、当前连续天数、历史最长、里程碑）、成就
（`/api/achievements`：16 枚成就与解锁状态）、成长体系（`/api/growth`：等级、经验、
累计统计、徽章墙）与预算总览（`/api/budgets`）。但这些「值得晒一晒」的时刻目前只停在
应用内部，用户没有一个统一、好看、可保存/转发的出口把它们分享给朋友。

本次新增**分享卡片（Share_Card_System）**：把用户的六类成就时刻各做成一张
**可保存到相册、可转发给微信好友的成就卡片**——**连续记账里程碑（如连续 100 天）、
本月总结、年度账单、获得徽章、预算达成、成长升级**。每张卡片统一由六个内容元素构成：
**头像、昵称、标签、一句 AI 文案、核心数据、小尺寸品牌 Logo**。

一句话产品原则：**用户分享的主体是「用户自己」的成就与数据，不是产品广告。** 品牌
Logo（「有余」）只作小尺寸点缀出现，绝不占据卡片主视觉、绝不是广告位。

一句话技术边界：**分享卡片分两层职责——(1) 后端提供一个只读聚合接口，把某张卡片所需的
展示数据（昵称、标签、一句 AI 文案、核心数据）打包返回，全部复用既有报表/成长/连续/成就/
预算的聚合口径；(2) miniapp 端用 canvas 把这些数据渲染成一张图片卡片，并负责保存到相册与
转发分享。** 后端**不产生任何业务数据、不落库、不新增数据库表与迁移脚本、不产图不存图、
不把任何用户财务数据发送到外部服务**。把分享卡片整块摘掉，报表、成长、连续、成就、预算、
交易等其余功能原样成立。

### 范围与前提约定（影响验收标准的关键决策）

以下决策构成本 spec 的骨架，验收标准全部围绕它们展开。经评审「你决定」后，所有决策均已锁定为
**已定**；各项后附的可选替代仅作未来 spec 的备注保留，不影响本 spec 的验收标准。

1. **职责分层（已定）：后端出数据、前端出图。** 后端新增**一个只读卡片数据聚合接口**，
   按卡片类型返回该卡片的展示数据包（昵称、标签、一句 AI 文案、核心数据、卡片可用性），
   复用既有各子系统的聚合口径。图片在 miniapp 端用 canvas 渲染，保存到相册走
   `saveImageToPhotosAlbum`、转发走微信分享。**服务端不产图、不存图、不引入对象存储**
   （与 achievement-system 需求 8、smart-monthly-report 需求 8 同源：本项目当前无对象存储，
   分享图一律客户端生成）。

2. **AI 文案（已定）：模板/规则驱动，非外部 LLM，数据不出服务器。** 卡片上的「一句 AI 文案」
   由后端一套内置中文模板/规则从该卡片的核心数据确定性生成，措辞轻松、暖心、正向。
   **v1 不接入任何外部大模型/第三方文本生成服务，用户财务数据不出「有余」服务端**（与
   ai-fun-analysis、fun-personality-tags 的取舍一致）。（可选替代：未来可选接入 LLM 增强文案，
   需另立 spec 并覆盖去标识化、用户授权与降级——v1 不做。）

3. **卡片类型目录（v1，已定 6 种）：** 每种卡片由「卡片类型键、标题、核心数据口径、可用条件、
   账本语义」构成：

   | 卡片类型键 | 标题 | 核心数据来源（复用既有口径） | 账本语义 |
   |------------|------|------------------------------|----------|
   | `STREAK_MILESTONE` | 连续记账里程碑（如连续 100 天） | 连续记账（`/api/streak`：当前连续天数、历史最长、里程碑集合 7/30/100/365） | 账本无关 |
   | `MONTHLY_SUMMARY` | 本月总结 | 智能月报（`/api/reports/monthly-digest`：收入/支出/结余/分类 Top 等） | 当前账本 |
   | `ANNUAL_BILL` | 年度账单 | 按自然年聚合（复用 `trendReport`/`monthlyReport` 口径按年汇总） | 当前账本 |
   | `ACHIEVEMENT_BADGE` | 获得徽章 | 成就（`/api/achievements`：某枚已解锁成就的名称/描述/解锁时刻） | 账本无关 |
   | `BUDGET_ACHIEVED` | 预算达成 | 预算总览（`/api/budgets`：本月预算、已用、使用率、达成状态） | 当前账本 |
   | `LEVEL_UP` | 成长升级 | 成长体系（`/api/growth`：等级、经验、升级进度、称号） | 账本无关 |

   **每种卡片有明确的「可用条件」**（例如 `STREAK_MILESTONE` 需已达成某个连续里程碑、
   `BUDGET_ACHIEVED` 需当前账本目标月已设预算且未超支）。可用条件不满足时接口返回
   **该卡片不可用**而非错误，前端据此隐藏/禁用该卡片。**v1 全部 6 种卡片类型都上
   （`STREAK_MILESTONE` / `MONTHLY_SUMMARY` / `ANNUAL_BILL` / `ACHIEVEMENT_BADGE` /
   `BUDGET_ACHIEVED` / `LEVEL_UP`）。**（可选替代：未来若需分期上线，可先上高频的连续/月报/徽章
   三种——本 spec 按全部 6 种编写。）

4. **卡片内容元素（已定 6 元素）：** 每张卡片统一含头像、昵称、标签、一句 AI 文案、核心数据、
   品牌 Logo 六个元素。
   - **头像**：本项目当前**无头像图片上传能力**，「我的」页头像即以昵称首字符生成的文字头像
     （见 `pages/me/me.vue`）。卡片头像沿用该口径——取昵称首字符的文字头像，**不引入头像图片
     上传/外链**。（可选替代：未来支持微信头像授权后改为图片头像，需另立 spec——v1 不做。）
   - **昵称**：取当前登录用户的 `nickname`（缺省展示「有余用户」，与 `pages/me/me.vue` 一致）。
   - **标签**：复用既有标签体系——趣味人格标签（`/api/reports/personality-tags`，账本相关）
     或成就/等级称号；无可用标签时该元素优雅省略，不阻断出卡。
   - **一句 AI 文案**：见第 2 项（模板/规则）。
   - **核心数据**：随卡片类型不同，见第 3 项与需求 3–8。
   - **品牌 Logo**：小尺寸「有余」标识，见第 6 项约束。

5. **分享主体是用户自己（已定，核心约束）：** 卡片主视觉是用户的成就与数据；品牌 Logo 只作
   **小尺寸点缀**。本 spec 把「Logo 不喧宾夺主」落为**可验收的量化约束**（见需求 2）：Logo 在
   卡片可见区域内的占用面积占比有上限，且不置于卡片视觉中心。卡片上不出现任何促销、下载引导、
   二维码广告位等推广元素。

6. **不落库、不埋点（v1，已定）：** v1 分享卡片是纯只读派生，**不落任何分享记录表、不新增
   埋点上报**。（可选替代：若产品未来需要统计分享转化，可后续另立 spec 增加只读埋点，覆盖隐私与
   降级——v1 不做。）

7. **不含小程序码/二维码引流（v1，已定）：** v1 转发走微信原生分享（图片分享
   `showShareImageMenu` 或转发卡片 `onShareAppMessage`，与 report 页月报海报、成就分享同源），
   **卡片图内不绘制小程序码/二维码**。（可选替代：未来若要在卡片上放小程序码引流回小程序，
   需确认小程序码来源（invite-system 已有内存缓存的小程序码能力）与不外泄隐私——v1 不做。）

8. **年度账单口径（已定）：** 年度账单按**自然年**（`Asia/Shanghai`）聚合，年份参数缺省取
   **当前自然年**；核心数据（年度总收入、总支出、结余、支出最高的月份、支出 Top 分类）复用
   `trendReport`（按月序列）与 `categoryReport`/`monthlyReport` 口径按年汇总；**当前自然年标注为
   进行中（`partial`），早于当前年的目标年标注为已完结（`final`）。**（可选替代：未来可将缺省改为
   「上一个已结束自然年」以获得完整年度数据，或支持「滚动近 12 个月」——本 spec 按自然年、缺省
   当前自然年编写。）

9. **账本隔离按卡片类型区分（已定）：** 账本无关卡片（`STREAK_MILESTONE`、`ACHIEVEMENT_BADGE`、
   `LEVEL_UP`）不依赖 `X-Ledger-Id`，跨账本按用户维度取数（与 `/api/growth`、`/api/streak`、
   `/api/achievements` 的 `noLedger` 口径一致）；账本相关卡片（`MONTHLY_SUMMARY`、`ANNUAL_BILL`、
   `BUDGET_ACHIEVED`）按 `X-Ledger-Id` 解析的当前账本隔离，miniapp「全部账本」聚合视图下不提供
   这三类卡片（无单一账本上下文）。

10. **口径对齐（已定）：** 所有金额一律 `BigDecimal` 保留 2 位小数（HALF_UP）；占比与百分比保留
    2 位小数；所有金额统计一律排除 `type=transfer` 的交易；自然日/自然月/自然年边界一律按
    `Asia/Shanghai`（固定偏移 UTC+08:00）；连续天数、里程碑、成就解锁、等级、预算达成、月报/年度
    金额均与各自既有子系统同源同值，本 spec 只读派生、不改任何既有口径。

### 与其它 spec / 既有系统的关系

- **复用既有聚合与展示口径**：`STREAK_MILESTONE` 复用 streak-system 的当前连续天数、历史最长、
  里程碑集合（7/30/100/365，取自成就清单 `MAX_STREAK` 门槛）；`MONTHLY_SUMMARY` 复用
  smart-monthly-report 的月报数据包；`ANNUAL_BILL` 复用 `ReportService.trendReport`/`monthlyReport`/
  `categoryReport` 口径按自然年汇总；`ACHIEVEMENT_BADGE` 复用 achievement-system 的成就清单与解锁
  状态；`BUDGET_ACHIEVED` 复用 `BudgetService.overview` 口径；`LEVEL_UP` 复用 growth-level-system
  的等级曲线与成长概览。
- **复用账本与鉴权隔离**：账本相关卡片沿用 `CurrentLedger`（`X-Ledger-Id` 头解析 + 默认账本兜底）
  与 `CurrentUser`，以及既有账本受保护接口的鉴权与「账本不可访问」错误
  （`UNAUTHENTICATED` / `LEDGER_NOT_ACCESSIBLE` / `REPORT_PARAM_INVALID` 既有错误码，不新增错误码）。
- **复用既有出图/保存/分享范式**：miniapp 复用 `pages/report/report.vue` 与 `utils/digest.js` 的
  `drawDigestPoster` / `canvasToTempFilePath` / `saveImageToPhotosAlbum` / `showShareImageMenu` 流程，
  以及 achievement-system 成就卡片的授权失败/超时兜底范式。
- **复用文案/降级范式**：AI 文案对齐 `utils/insights.js` / `utils/personalityTags.js` 的模板/规则
  驱动与静默降级。
- **无迁移**：本 spec 不新增/不修改任何 Flyway 迁移脚本，不新建任何数据库表，不新增仓库查询方法。
- 与 record-suggestion、invite-system、custom-reminder、user-feedback-system 无功能耦合。

## Glossary

- **分享卡片系统（Share_Card_System）**：本 spec 涉及的卡片数据聚合、卡片可用性判定、一句 AI 文案
  生成、只读卡片数据接口，以及 miniapp 端卡片渲染、保存到相册与转发分享的整体。
- **分享卡片（share card）**：一张可保存到相册、可转发给微信好友的图片卡片，主体是用户自己的某项
  成就或数据；由头像、昵称、标签、一句 AI 文案、核心数据与小尺寸品牌 Logo 六个内容元素构成。
- **卡片类型（card type）**：区分大小写的稳定枚举，v1 取值之一——`STREAK_MILESTONE`（连续记账
  里程碑）、`MONTHLY_SUMMARY`（本月总结）、`ANNUAL_BILL`（年度账单）、`ACHIEVEMENT_BADGE`（获得
  徽章）、`BUDGET_ACHIEVED`（预算达成）、`LEVEL_UP`（成长升级）。
- **卡片数据包（card data package）**：某张卡片渲染所需的展示数据集合，含卡片类型、卡片可用性、
  昵称、文字头像种子、标签（可空）、一句 AI 文案、核心数据字段与品牌名。
- **核心数据（core data）**：某张卡片主展示的一组统计取值，随卡片类型不同（见需求 3–8）。
- **卡片可用性（card availability）**：某卡片类型在当前用户/当前账本/给定周期下是否满足其可用条件的
  布尔标识；不可用时接口返回可用性为假与不可用原因，且不返回该卡片的核心数据。
- **一句 AI 文案（card narrative）**：由卡片核心数据套入内置中文模板生成的一句暖心、正向、俏皮的
  中文文案。
- **文字头像（initial avatar）**：以昵称首字符生成的头像展示，与 `pages/me/me.vue` 一致；本 spec
  不引入头像图片上传或外链。
- **昵称（nickname）**：当前登录用户的 `nickname`；为空时取默认展示名「有余用户」。
- **标签（label）**：卡片上一枚简短标签，来源为趣味人格标签、成就名称或等级称号之一；无可用来源
  时该元素省略。
- **品牌 Logo（brand logo）**：卡片上小尺寸的「有余」品牌标识（文字或图形），仅作点缀。
- **连续里程碑（streak milestone）**：连续记账里程碑集合中的取值，取自成就清单常量中统计口径为
  `MAX_STREAK` 的成就门槛，当前为 7、30、100、365；`STREAK_MILESTONE` 卡片以用户已达成的最高
  里程碑为核心数据。
- **目标月（target month）**：`MONTHLY_SUMMARY`/`BUDGET_ACHIEVED` 卡片所针对的自然月，格式
  `YYYY-MM`，边界按时区口径；缺省为当前自然月。
- **目标年（target year）**：`ANNUAL_BILL` 卡片所针对的自然年，格式 `YYYY`，边界按时区口径；
  缺省为当前自然年。
- **月状态（month status）** / **年状态（year status）**：目标月/目标年的完结状态，取值 `partial`
  （进行中：为当前自然月/自然年且尚未结束）或 `final`（已完结）。
- **当前账本**：`CurrentLedger` 依 `X-Ledger-Id` 头解析所得账本；缺省时为用户默认账本。
- **全部账本聚合视图**：miniapp 选择「全部」时的跨账本汇总视图，无单一账本上下文。
- **账本无关卡片**：`STREAK_MILESTONE`、`ACHIEVEMENT_BADGE`、`LEVEL_UP`，与会话账本无关，跨账本按
  用户维度取数。
- **账本相关卡片**：`MONTHLY_SUMMARY`、`ANNUAL_BILL`、`BUDGET_ACHIEVED`，按当前账本隔离。
- **卡片数据接口（share-card endpoint）**：本 spec 新增的唯一只读接口，按卡片类型返回该卡片的
  数据包或不可用标识。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌。
- **时区口径**：本 spec 全部自然日/自然月/自然年边界的计算时区，取 `Asia/Shanghai`（固定偏移
  UTC+08:00）。
- **miniapp**：微信小程序端（uni-app / Vue 3）。

## Requirements

### 需求 1：卡片类型目录、整体数据生成与口径边界

**用户故事：** 作为用户，我想把自己的成就时刻做成一张卡片分享出去，且卡片只基于我自己的数据、口径与应用里看到的一致。

#### 验收标准

1. THE 分享卡片系统 SHALL 恰好支持以下 6 种卡片类型且类型键互不重复：`STREAK_MILESTONE`、`MONTHLY_SUMMARY`、`ANNUAL_BILL`、`ACHIEVEMENT_BADGE`、`BUDGET_ACHIEVED`、`LEVEL_UP`。
2. WHEN 已登录用户请求某卡片类型的数据 THEN THE 分享卡片系统 SHALL 返回一个卡片数据包，其至少包含卡片类型、卡片可用性、昵称、文字头像种子、标签（可空）、一句 AI 文案（卡片可用时非空）、该卡片类型对应的核心数据字段与品牌名。
3. THE 分享卡片系统 SHALL 使卡片数据仅派生自当前用户有权访问的数据，且 SHALL 不返回任何其它用户的数据。
4. THE 分享卡片系统 SHALL 在所有金额、占比统计中排除 `type=transfer` 的交易，并 SHALL 使所有金额值保留 2 位小数（HALF_UP）、所有占比与百分比保留 2 位小数（HALF_UP）。
5. THE 分享卡片系统 SHALL 以时区口径 `Asia/Shanghai`（固定偏移 UTC+08:00）界定全部自然日、自然月与自然年边界，且 SHALL 不依赖 JVM、数据库会话或操作系统的默认时区取值。
6. WHEN 同一用户在同一时刻、同一账本上下文、同一底层数据下多次请求同一卡片类型的同一周期 THEN THE 分享卡片系统 SHALL 返回相同的卡片可用性与相同的核心数据取值（确定性、可复现）。
7. THE 分享卡片系统 SHALL 使 `STREAK_MILESTONE`、`ACHIEVEMENT_BADGE`、`LEVEL_UP` 三类卡片为账本无关卡片，SHALL 不要求请求携带 `X-Ledger-Id` 头、SHALL 不因该头缺失或取值不可访问而拒绝这三类卡片的请求，且 SHALL 跨该用户全部账本按用户维度取数。
8. THE 分享卡片系统 SHALL 使 `MONTHLY_SUMMARY`、`ANNUAL_BILL`、`BUDGET_ACHIEVED` 三类卡片为账本相关卡片，SHALL 以 `X-Ledger-Id` 头解析的当前账本隔离取数；WHERE 请求未携带 `X-Ledger-Id` 头 THE 分享卡片系统 SHALL 以当前用户的默认账本作为当前账本处理这三类卡片的请求。
9. WHERE miniapp 处于全部账本聚合视图 THE 分享卡片系统 SHALL 不向用户提供账本相关卡片（`MONTHLY_SUMMARY`、`ANNUAL_BILL`、`BUDGET_ACHIEVED`）的分享入口。
10. THE 分享卡片系统 SHALL 使卡片核心数据的取值与其来源子系统在相同用户、相同账本、相同周期、相同统计口径下完全相等（数值差值为 0）：连续与里程碑与 `/api/streak` 一致、本月总结与 `/api/reports/monthly-digest` 一致、预算达成与 `/api/budgets` 一致、成就解锁状态与 `/api/achievements` 一致、等级与经验与 `/api/growth` 一致。

### 需求 2：卡片内容元素与「分享主体是用户自己」约束

**用户故事：** 作为用户，我分享的是我自己的成就，卡片要好看、突出我，品牌 Logo 只是小小的点缀，而不是一张广告。

#### 验收标准

1. THE 分享卡片系统 SHALL 使每张卡片恰好包含头像、昵称、标签、一句 AI 文案、核心数据与品牌 Logo 六类内容元素的渲染，其中标签元素在无可用来源时 SHALL 被省略、其余五类 SHALL 恒存在。
2. THE 分享卡片系统 SHALL 使卡片头像为以昵称首字符生成的文字头像，且 SHALL 不引入头像图片上传或外部图片链接。
3. THE 分享卡片系统 SHALL 使卡片昵称取当前登录用户的 `nickname`；IF 该用户的 `nickname` 去首尾空白后为空 THEN THE 分享卡片系统 SHALL 以默认展示名「有余用户」作为昵称。
4. THE 分享卡片系统 SHALL 使卡片标签来源于趣味人格标签、成就名称或等级称号之一；WHERE 该卡片类型与账本上下文下无任何可用标签来源 THE 分享卡片系统 SHALL 省略标签元素，且 SHALL 不使卡片出图失败。
5. THE 分享卡片系统 SHALL 使品牌 Logo 以小尺寸呈现，其在卡片可见区域内的占用面积占比不超过卡片可见区域面积的 5%，且 SHALL 不将品牌 Logo 置于卡片的视觉主区域（SHALL 使核心数据、昵称与一句 AI 文案占据卡片主视觉）。
6. THE 分享卡片系统 SHALL 不在卡片上绘制任何促销文案、下载引导、二维码广告位或其它推广元素（v1 卡片不含小程序码/二维码）。
7. THE 分享卡片系统 SHALL 使卡片仅展示当前用户本人的头像、昵称、标签、文案与核心数据，且 SHALL 不展示任何其它用户的标识或数据。
8. THE 分享卡片系统 SHALL 使一句 AI 文案与核心数据中出现的每个数值与该卡片数据包机器可读字段中的对应取值完全相等（金额保留 2 位小数、占比保留 2 位小数、天数/笔数/等级/年月为整数）。

### 需求 3：连续记账里程碑卡（STREAK_MILESTONE）

**用户故事：** 作为用户，我连续记账坚持到了某个里程碑（比如连续 100 天），我想把它做成卡片晒出来。

#### 验收标准

1. THE 分享卡片系统 SHALL 复用 streak-system 的口径取当前连续天数、历史最长连续天数与里程碑集合（取自成就清单常量中统计口径为 `MAX_STREAK` 的门槛，当前为 7、30、100、365），且 SHALL 不在本 spec 中写死这些里程碑数值。
2. THE 分享卡片系统 SHALL 把已达成里程碑定义为里程碑集合中不大于历史最长连续天数的取值；THE 分享卡片系统 SHALL 以已达成里程碑中的最大取值作为 `STREAK_MILESTONE` 卡片的核心里程碑。
3. WHEN 用户请求 `STREAK_MILESTONE` 卡片且存在至少一个已达成里程碑 THEN THE 分享卡片系统 SHALL 使该卡片可用，并使核心数据携带核心里程碑数值、当前连续天数与历史最长连续天数。
4. IF 用户无任何已达成里程碑（历史最长连续天数小于里程碑集合的最小取值）THEN THE 分享卡片系统 SHALL 使 `STREAK_MILESTONE` 卡片不可用、返回不可用原因，且 SHALL 不返回错误。
5. WHERE 请求携带指定里程碑的参数且该里程碑属于已达成里程碑 THE 分享卡片系统 SHALL 以该指定里程碑作为核心里程碑；WHERE 该参数指向未达成或不属于里程碑集合的取值 THE 分享卡片系统 SHALL 回退取核心里程碑（已达成里程碑的最大取值）。
6. THE 分享卡片系统 SHALL 使 `STREAK_MILESTONE` 卡片为账本无关卡片，取数与会话账本无关。

### 需求 4：本月总结卡（MONTHLY_SUMMARY）

**用户故事：** 作为用户，我想把这个月的收支小结做成一张卡片分享出去。

#### 验收标准

1. THE 分享卡片系统 SHALL 复用 smart-monthly-report 的月报数据包口径，为目标月取本月收入、本月支出、结余与支出占比最高的分类作为 `MONTHLY_SUMMARY` 卡片的核心数据。
2. WHERE 未指定目标月 THE 分享卡片系统 SHALL 以 `Asia/Shanghai` 当前自然月为目标月。
3. WHEN 目标月为当前自然月且当月尚未结束 THEN THE 分享卡片系统 SHALL 将月状态标记为 `partial`，并使核心数据基于截至当前时刻的数据；WHEN 目标月早于当前自然月 THEN THE 分享卡片系统 SHALL 将月状态标记为 `final`。
4. WHEN 用户请求 `MONTHLY_SUMMARY` 卡片且目标月在当前账本内存在至少一笔计入统计的交易 THEN THE 分享卡片系统 SHALL 使该卡片可用，并使核心数据携带目标月标识（`YYYY-MM`）、月状态、本月收入、本月支出与结余。
5. IF 目标月在当前账本内无任何计入统计的交易 THEN THE 分享卡片系统 SHALL 使 `MONTHLY_SUMMARY` 卡片不可用、返回不可用原因，且 SHALL 不返回错误。
6. THE 分享卡片系统 SHALL 使 `MONTHLY_SUMMARY` 卡片为账本相关卡片，按当前账本隔离取数。
7. IF 目标月参数格式非法（非 `YYYY-MM`，或月份不在 01 至 12 之间）THEN THE 分享卡片系统 SHALL 返回错误码 `REPORT_PARAM_INVALID`，且 SHALL 不返回任何卡片数据。

### 需求 5：年度账单卡（ANNUAL_BILL）

**用户故事：** 作为用户，我想把这一年的账单总结做成一张年度卡片分享出去。

#### 验收标准

1. THE 分享卡片系统 SHALL 复用 `ReportService.trendReport`（按月序列）与 `monthlyReport`/`categoryReport` 口径，按 `Asia/Shanghai` 自然年边界（自当年 1 月 1 日 00:00:00.000 起至次年 1 月 1 日 00:00:00.000 前，含起始、不含结束）为目标年汇总年度总收入、年度总支出、年度结余、支出最高的自然月与支出占比最高的分类作为 `ANNUAL_BILL` 卡片的核心数据。
2. WHERE 未指定目标年 THE 分享卡片系统 SHALL 以 `Asia/Shanghai` 当前自然年为目标年。
3. WHEN 目标年为当前自然年且当年尚未结束 THEN THE 分享卡片系统 SHALL 将年状态标记为 `partial`；WHEN 目标年早于当前自然年 THEN THE 分享卡片系统 SHALL 将年状态标记为 `final`。
4. WHEN 用户请求 `ANNUAL_BILL` 卡片且目标年在当前账本内存在至少一笔计入统计的交易 THEN THE 分享卡片系统 SHALL 使该卡片可用，并使核心数据携带目标年标识（`YYYY`）、年状态、年度总收入、年度总支出与年度结余。
5. IF 目标年在当前账本内无任何计入统计的交易 THEN THE 分享卡片系统 SHALL 使 `ANNUAL_BILL` 卡片不可用、返回不可用原因，且 SHALL 不返回错误。
6. THE 分享卡片系统 SHALL 使 `ANNUAL_BILL` 卡片为账本相关卡片，按当前账本隔离取数。
7. IF 目标年参数格式非法（非 4 位数字年份，或超出既有报表支持的年份范围）THEN THE 分享卡片系统 SHALL 返回错误码 `REPORT_PARAM_INVALID`，且 SHALL 不返回任何卡片数据。

### 需求 6：获得徽章卡（ACHIEVEMENT_BADGE）

**用户故事：** 作为用户，我解锁了一枚成就徽章，我想把这枚徽章做成卡片晒给朋友。

#### 验收标准

1. THE 分享卡片系统 SHALL 复用 achievement-system 的成就清单与解锁状态，仅以已解锁成就作为 `ACHIEVEMENT_BADGE` 卡片的候选。
2. WHEN 用户请求 `ACHIEVEMENT_BADGE` 卡片并指定某成就编码且该成就已解锁 THEN THE 分享卡片系统 SHALL 使该卡片可用，并使核心数据携带该成就的展示名称、中文描述与精确到自然日的解锁日期。
3. IF 请求指定的成就编码不属于成就清单，或该成就尚未解锁 THEN THE 分享卡片系统 SHALL 使 `ACHIEVEMENT_BADGE` 卡片不可用、返回不可用原因，且 SHALL 不返回错误。
4. WHERE 请求未指定成就编码 THE 分享卡片系统 SHALL 以该用户已解锁成就中解锁时刻最新的一枚作为该卡片的核心成就；IF 该用户无任何已解锁成就 THEN THE 分享卡片系统 SHALL 使该卡片不可用、返回不可用原因，且 SHALL 不返回错误。
5. THE 分享卡片系统 SHALL 使 `ACHIEVEMENT_BADGE` 卡片为账本无关卡片，取数与会话账本无关。
6. THE 分享卡片系统 SHALL 使 `ACHIEVEMENT_BADGE` 卡片的核心数据不含成就编码原始字面量之外用户不可读的内部标识（仅下发展示名称、描述与解锁日期）。

### 需求 7：预算达成卡（BUDGET_ACHIEVED）

**用户故事：** 作为用户，我这个月把预算控制得很好，我想把「预算达成」做成卡片分享出去。

#### 验收标准

1. THE 分享卡片系统 SHALL 复用 `BudgetService.overview` 口径，为当前账本目标月取本月总预算、已用支出、剩余、已用百分比与预算状态（OK / WARN / OVER）作为 `BUDGET_ACHIEVED` 卡片的核心数据。
2. WHERE 未指定目标月 THE 分享卡片系统 SHALL 以 `Asia/Shanghai` 当前自然月为目标月。
3. WHEN 用户请求 `BUDGET_ACHIEVED` 卡片且当前账本目标月已设置总预算、已用支出大于 0.00 且已用支出不超过本月总预算（即预算状态非 OVER）THEN THE 分享卡片系统 SHALL 使该卡片可用，并使核心数据携带目标月标识、本月总预算、已用支出、剩余与已用百分比。
4. IF 当前账本目标月未设置总预算，或已用支出为 0.00，或已用支出超过本月总预算（预算状态为 OVER）THEN THE 分享卡片系统 SHALL 使 `BUDGET_ACHIEVED` 卡片不可用、返回不可用原因，且 SHALL 不返回错误。
5. THE 分享卡片系统 SHALL 使 `BUDGET_ACHIEVED` 卡片为账本相关卡片，按当前账本隔离取数。
6. IF 目标月参数格式非法（非 `YYYY-MM`，或月份不在 01 至 12 之间）THEN THE 分享卡片系统 SHALL 返回错误码 `REPORT_PARAM_INVALID`，且 SHALL 不返回任何卡片数据。

### 需求 8：成长升级卡（LEVEL_UP）

**用户故事：** 作为用户，我升到了一个新的成长等级，我想把这个升级时刻做成卡片分享出去。

#### 验收标准

1. THE 分享卡片系统 SHALL 复用 growth-level-system 的成长概览口径，取当前等级、经验值、当前等级起始经验、升级进度与是否满级作为 `LEVEL_UP` 卡片的核心数据。
2. WHEN 用户请求 `LEVEL_UP` 卡片且当前等级大于或等于 2 THEN THE 分享卡片系统 SHALL 使该卡片可用，并使核心数据携带当前等级、经验值与本级内已获得经验。
3. IF 当前等级为 1（尚未产生任何升级）THEN THE 分享卡片系统 SHALL 使 `LEVEL_UP` 卡片不可用、返回不可用原因，且 SHALL 不返回错误。
4. WHERE 用户处于满级（等级为 100）THE 分享卡片系统 SHALL 使核心数据以满级标识替代升级进度，并 SHALL 不返回下一等级所需经验与升级还需经验两项。
5. THE 分享卡片系统 SHALL 使 `LEVEL_UP` 卡片为账本无关卡片，取数与会话账本无关。
6. THE 分享卡片系统 SHALL 使 `LEVEL_UP` 卡片核心数据中的等级与经验值与 `/api/growth` 在同一时刻的同名两项取值相等。

### 需求 9：一句 AI 文案（模板/规则，暖心正向，非外部 LLM）

**用户故事：** 作为用户，我希望卡片上那句文案有温度、俏皮、正向，而不是冷冰冰的数字，也不希望我的数据被发到外部。

#### 验收标准

1. WHEN 某卡片可用 THEN THE 分享卡片系统 SHALL 为该卡片生成一句已渲染的中文 AI 文案字符串。
2. THE 分享卡片系统 SHALL 以模板/规则方式生成一句 AI 文案，SHALL 不调用任何外部大语言模型或任何外部文本生成服务。
3. THE 分享卡片系统 SHALL 使一句 AI 文案仅采用正向或中性的措辞，且 SHALL 不使用任何负面、评判、羞辱或警示式措辞。
4. THE 分享卡片系统 SHALL 使一句 AI 文案至少包含该卡片核心数据中的一项关键数值（如连续天数、里程碑、金额、等级、成就名称之一）。
5. THE 分享卡片系统 SHALL 使一句 AI 文案中出现的每个数值与该卡片核心数据机器可读字段中的对应取值完全相等。
6. THE 分享卡片系统 SHALL 使一句 AI 文案长度不超过 60 个中文字符且不少于 1 个中文字符。
7. IF 某卡片可用但一句 AI 文案生成所需的关键数值缺失 THEN THE 分享卡片系统 SHALL 以该卡片类型的内置默认文案兜底，且 SHALL 不使该卡片请求返回错误。

### 需求 10：卡片数据聚合接口与权限边界

**用户故事：** 作为用户，我的分享卡片只基于我有权访问的数据，别人看不到也拿不到；作为开发者，我要一个清晰的只读接口。

#### 验收标准

1. THE 分享卡片系统 SHALL 提供一个只读的卡片数据聚合接口，只读指该接口不执行任何写操作：不新增、不修改、不删除任何交易、账本、预算、成长、成就或分享持久化数据。
2. THE 分享卡片系统 SHALL 使该接口接受卡片类型参数与该类型对应的可选周期/标识参数（`MONTHLY_SUMMARY`/`BUDGET_ACHIEVED` 接受可选 `YYYY-MM`、`ANNUAL_BILL` 接受可选 `YYYY`、`ACHIEVEMENT_BADGE` 接受可选成就编码、`STREAK_MILESTONE` 接受可选里程碑），并按对应需求缺省取值。
3. THE 分享卡片系统 SHALL 要求该接口携带有效令牌；IF 请求未携带令牌、令牌无法解析、令牌签名校验失败、令牌已过期、或其标识的用户不存在 THEN THE 分享卡片系统 SHALL 返回错误码 `UNAUTHENTICATED`，且响应中 SHALL 不包含任何卡片数据。
4. THE 分享卡片系统 SHALL 对账本相关卡片以 `X-Ledger-Id` 头解析当前账本；IF 该头指向的账本当前用户无权访问 THEN THE 分享卡片系统 SHALL 返回错误码 `LEDGER_NOT_ACCESSIBLE`（与既有账本受保护接口一致），且 SHALL 不返回任何卡片数据。
5. IF 请求的卡片类型不属于本 spec 支持的 6 种取值 THEN THE 分享卡片系统 SHALL 返回错误码 `REPORT_PARAM_INVALID`，且 SHALL 不返回任何卡片数据。
6. IF 单次请求同时存在鉴权失败、账本不可访问、参数非法中的多种错误 THEN THE 分享卡片系统 SHALL 按「鉴权（`UNAUTHENTICATED`）→ 账本（`LEDGER_NOT_ACCESSIBLE`）→ 参数（`REPORT_PARAM_INVALID`）」优先级仅返回最高优先级对应的错误码，且 SHALL 不返回任何卡片数据。
7. THE 分享卡片系统 SHALL 使该接口只返回派生自当前用户在当前账本（账本相关卡片）或当前用户维度（账本无关卡片）有权访问的数据的卡片，SHALL 忽略请求中任何用于指定目标用户身份的查询参数、路径参数、请求体字段与自定义请求头，且 SHALL 不因携带此类字段而返回错误。
8. WHEN 该接口被调用 THEN THE 分享卡片系统 SHALL 在服务端处理耗时不超过 2000 毫秒（含 2000 毫秒边界，不含网络传输耗时）内返回结果，结果类型为以下之一：可用卡片数据包、不可用卡片标识、或错误码（`UNAUTHENTICATED`、`LEDGER_NOT_ACCESSIBLE`、`REPORT_PARAM_INVALID`）。
9. THE 分享卡片系统 SHALL 不新增任何错误码，仅复用既有 `UNAUTHENTICATED`、`LEDGER_NOT_ACCESSIBLE`、`REPORT_PARAM_INVALID`。

### 需求 11：卡片渲染、保存到相册与分享（前端）

**用户故事：** 作为用户，我想在小程序里把卡片渲染出来，保存到相册或直接转发给微信好友。

#### 验收标准

1. WHEN 某卡片数据加载成功且卡片可用 THEN THE miniapp SHALL 依据卡片数据包在 miniapp 端用 canvas 渲染出一张图片卡片，其内容包含头像、昵称、标签（有则显示）、一句 AI 文案、核心数据与小尺寸品牌 Logo。
2. THE miniapp SHALL 复用既有出图/保存/分享范式（`pages/report/report.vue` 与 `utils/digest.js` 的 canvas 绘制、`canvasToTempFilePath` 出图、`saveImageToPhotosAlbum` 保存、`showShareImageMenu`/`onShareAppMessage` 分享）。
3. WHEN 卡片图渲染成功 THEN THE miniapp SHALL 允许用户将该图片保存到相册或发起分享。
4. WHEN 用户触发保存到相册且相册写入授权尚未授予 THEN THE miniapp SHALL 先发起一次相册写入授权请求；IF 用户拒绝相册写入授权 THEN THE miniapp SHALL 展示需要授权的提示文案、SHALL 不写入相册、SHALL 保持停留在当前页面，且 SHALL 不使页面进入错误态。
5. WHEN 相册写入成功 THEN THE miniapp SHALL 展示保存成功的提示文案并保持停留在当前页面。
6. IF 自用户触发出图或保存起 3000 毫秒内卡片图的渲染与相册写入未全部完成 THEN THE miniapp SHALL 结束本次操作、SHALL 展示失败提示文案、SHALL 保持停留在当前页面，且 SHALL 允许用户再次触发。
7. WHERE 某卡片类型不可用 THE miniapp SHALL 不提供该卡片的出图、保存与分享操作；IF 用户触发不可用卡片的相关入口 THEN THE miniapp SHALL 展示该卡片暂不可用的提示文案，且 SHALL 不发起 canvas 绘制、SHALL 不写入相册、SHALL 不发起转发。
8. WHERE 当前不存在已登录状态（即无有效令牌）THE miniapp SHALL 不发起卡片数据请求、SHALL 不展示任何卡片，且 SHALL 展示登录入口以引导用户登录。
9. IF 卡片数据接口返回错误标识，或 miniapp 自发起该请求起 5000 毫秒（含边界）内未收到响应 THEN THE miniapp SHALL 以静默方式降级（隐藏或禁用该卡片），SHALL 不向用户弹出阻断性错误弹窗，且 SHALL 不阻断用户在当前页面其余模块的交互。

### 需求 12：隐私与安全边界

**用户故事：** 作为用户，我希望我的财务数据留在服务器内部，分享卡片不会把我的邮箱、令牌或其它账本的数据泄露出去。

#### 验收标准

1. WHEN 用户请求或渲染分享卡片 THEN THE 分享卡片系统 SHALL 完全在「有余」服务端与 miniapp 端内部完成卡片数据聚合、一句 AI 文案生成与图片渲染，且 SHALL 不发起任何指向「有余」服务端与 miniapp 之外地址的网络调用、SHALL 不依赖任何外部第三方服务返回结果。
2. THE 分享卡片系统 SHALL 不将任何交易记录、金额、分类、商户、预算、成长或账本数据（无论原始或派生）通过网络发送到「有余」服务端与 miniapp 之外的任何外部第三方服务（含外部 AI/大模型云服务与外部分析/日志服务）。
3. THE 分享卡片系统 SHALL 使每个卡片数据包与卡片图渲染内容的全部字段集合中，不包含用户邮箱、任何访问令牌或刷新令牌内容、用户套餐（`plan`）、微信标识（`wx_openid`/`wx_unionid`）、邀请码，以及任何不属于当前请求所指定账本上下文的其它账本数据。
4. THE 分享卡片系统 SHALL 使卡片数据包仅包含聚合派生统计（如连续天数、里程碑、金额合计、占比、等级、经验、成就名称与解锁日期）、昵称、文字头像种子、标签与由其生成的一句 AI 文案，且 SHALL 不逐笔回传原始交易记录与其敏感字段（含 `external_id`、原始备注全文、商户原始标识、附件内容或链接）。
5. IF 卡片数据包在返回前被检测到包含第 3 条所列的任一被禁止字段 THEN THE 分享卡片系统 SHALL 在返回给调用方前移除该字段，并使最终返回结果中不含任何被禁止字段，且不因该移除而中断请求或改变其余合法字段的取值。
6. THE miniapp SHALL 使账本相关卡片图仅包含当前账本的数据，且 SHALL 不在卡片图中绘制任何其它账本的数据、邮箱、令牌、套餐与邀请码。

### 需求 13：与既有系统的兼容边界（纯增量、纯只读）

**用户故事：** 作为开发者，我要确认这次改动是纯增量、纯只读的：把分享卡片整块摘掉，其余功能原样成立。

#### 验收标准

1. WHEN 处理一次分享卡片请求 THEN THE 分享卡片系统 SHALL 对 `transactions`、`categories`、`merchants`、`budgets`、`growth_events`、`user_growth`、`streak_segments` 等既有数据仅执行 SELECT 只读查询，使其在该次请求处理过程中对任何数据库表执行的写语句（INSERT、UPDATE、DELETE）数量为 0，且 DDL 语句（CREATE、ALTER、DROP、TRUNCATE）数量为 0。
2. THE 分享卡片系统 SHALL 使其引入的 Flyway 迁移脚本变更数为 0、新建数据库表数为 0（复用既有报表/成长/连续/成就/预算聚合方法与查询）。
3. THE 分享卡片系统 SHALL 使交易、账本、分类、商户、预算、报表、智能月报、AI 趣味分析、趣味人格标签、成长、连续记账、成就既有接口的请求字段、响应字段与错误码的新增、删除、修改数量之和为 0（不新增任何错误码）。
4. WHEN 对上述既有接口发起与移除本功能前完全相同的请求（相同接口、参数、账本与时间范围）THEN THE 对应系统 SHALL 返回与移除卡片数据接口及 miniapp 分享卡片入口前在字段名称、字段取值、字段顺序、错误码上逐一相等的响应。
5. THE 分享卡片系统 SHALL 使卡片核心数据所依据的派生指标与既有子系统在相同用户、相同账本、相同周期、相同统计口径下完全相等（数值差值为 0）：金额精度为 0.01 元、占比四舍五入位数与 `monthlyReport` 对齐、时区与「排除转账」口径一致。
6. IF 卡片数据聚合或图片渲染发生异常或未能返回结果 THEN THE 分享卡片系统 SHALL 隔离该失败，使既有接口仍按移除本功能前的行为成功返回，且既有数据库数据保持不变（写语句数量为 0）。
