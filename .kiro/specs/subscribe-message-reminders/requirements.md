# Requirements Document

## Introduction

有余（youyu）已有两块基础能力，本次新功能建立在它们之上：

- **预算模块（已实现）**：用户可为个人 / 家庭账本按自然月设月度总预算与分类预算，`BudgetService`
  已按 `Asia/Shanghai` 自然月聚合 `type=expense` 的已支出，并给出状态阈值 —— 已用 **> 100%** 为 `OVER`
  （超支）、**>= 80%** 为 `WARN`（预警）、否则 `OK`。AA 账本不设预算（对预算接口返回 `BUDGET_NOT_SUPPORTED`）。
- **自定义提醒 / 记账提醒（custom-reminder，已实现）**：用户可设「每天 / 工作日 / 周末 + 时间」的记账提醒，
  由分钟粒度调度器触发，经微信小程序**一次性订阅消息**（`subscribeMessage.send`）下发，文案随「今日是否已记账」
  二选一，并有一套「订阅授权额度（reminder_quota）+ 发送记录（reminder_send_logs）」的收发与幂等机制。

本次新增 **预算提醒（Budget_Reminder_System）**，把「预算即将超支 / 已经超支」这件用户最关心、
最能驱动回访的事，通过微信一次性订阅消息主动推送，用于**留存**：

- **触发是事件驱动的**：不是定时扫描，而是在用户的一笔支出写入 / 修改 / 删除 / 恢复导致**当前自然月**某个预算
  范围（月度总预算或某个分类预算）的已用比例达到预警线（`WARN`，>= 80%）或超支线（`OVER`，> 100%）时，
  就地评估并推送一条对应提醒。这与记账提醒的「到点触发」互补，两者共用订阅消息通道但各自独立。
- **每月每范围每级别至多一条**：同一收件人、同一账本、同一自然月、同一预算范围（总预算或某分类）、同一级别
  （预警 / 超支）至多推送一次，避免用户每记一笔就被打扰；由发送记录唯一键构造性保证。超支级别一旦推送，
  同范围同月的预警级别不再补推。
- **投递与额度独立于记账提醒**：微信一次性订阅消息的额度是**按模板**计的，预算提醒使用**独立的**预算提醒模板，
  因此本 spec 引入**独立的**预算提醒订阅额度与授权上报，不与记账提醒的 `reminder_quota` 混用、不改动其任何行为。
- **纯增量、绝不阻断主路径**：预算提醒的评估、发送与其任何故障都在记账主路径之外就地隔离，
  发送失败、微信接口异常、额度耗尽一律只记告警日志并写发送记录，绝不向记账、登录、注销、结算等路径抛出异常，
  也绝不改变交易接口的响应。

### 范围与前提约定（影响验收标准的关键决策）

以下决策构成本 spec 的骨架，验收标准围绕它们展开。

1. **本 spec 只新增「预算提醒」，不重建「记账提醒」。** 用户诉求原文为「预算超支 + 记账提醒的订阅消息（留存）」，
   其中**记账提醒已由 custom-reminder 实现并上线**。本 spec 复用其订阅消息通道与收发范式，但**不重新实现**记账提醒的
   任何验收标准；对记账提醒只做「与预算提醒并存、在同一设置页并列呈现」这一层集成（需求 10）。若需扩大范围重建记账提醒，
   须在评审时另行确认。
2. **投递通道锁定为微信一次性订阅消息，不做站内推送、短信与邮件。** 有余是个人主体微信小程序，个人主体只能用
   一次性订阅，每发一条消耗一次该模板的订阅授权额度。预算提醒使用**独立于记账提醒**的预算提醒模板，故其订阅授权、
   额度累计、发送与跳过全部建立在「按模板计额度」这条微信硬约束之上。
3. **超支 / 预警口径完全复用既有预算判定，不新开第二套。** 已支出按 `Asia/Shanghai` 自然月半开区间聚合、仅计
   `type=expense`；预警线为已用 >= 80% 且 <= 100%，超支线为已用 > 100%，与 `BudgetService` 现有 `WARN` / `OVER`
   阈值逐字一致。预算金额为 0 或未设时不产生任何级别。
4. **只对「当前自然月」的预算触发提醒。** 只有当引发评估的交易其发生月为当前 `Asia/Shanghai` 自然月时才评估，
   且只评估当前自然月的预算；历史月份的超支不推送（对留存无意义，且会在回填历史数据时误触发）。
5. **只对个人 / 家庭账本触发，AA 账本不触发。** 与预算模块一致：AA 账本不设预算，故不产生任何预算提醒。
6. **收件人是账本成员，各自独立授权与计额度。** 预算按账本归属，家庭账本可有多名成员。某账本某范围触发时，
   系统向该账本中**已开启预算提醒、`wx_openid` 非空、且预算提醒剩余订阅次数大于 0** 的每名成员各推送一条，
   去重与额度扣减按**收件人**维度独立进行。
7. **每月每范围每级别至多一条，由唯一键构造性保证。** 不依赖「一次评估只发一次」这种时序巧合；
   即使交易反复增删使已用比例上下穿越阈值，同一收件人同范围同月同级别也至多一条 `SENT`。
8. **预算提醒数据是纯增量。** 本 spec 只**读取** `budgets` / `category_budgets` / `transactions` / `ledger_members` /
   `users.wx_openid` 等既有数据用于评估，只**写入**本 spec 新增的两张表；删掉本 spec 新增的表与配置，其余功能原样成立。

### 与其它 spec 的关系

- **依赖预算模块（已实现）**：复用 `BudgetService` 的自然月已支出聚合与 `WARN` / `OVER` 阈值口径；只读、不改其任何验收标准。
- **依赖 custom-reminder（已实现）**：复用 `WeChatAccessTokenProvider` 取凭证、经 `WeChatClient` 调 `subscribeMessage.send`
  的既有集成范式与「授权额度 + 发送记录 + 幂等」范式；但预算提醒的模板、额度、发送记录表**全部独立新增**，不改动
  custom-reminder 的任何行为与数据。
- **依赖账本成员体系（`ledger_members`，已实现）**：以其确定某账本的收件成员集合。
- **迁移版本号**：`src/main/resources/db/migration` 当前最大版本号为 42（`V42__user_gender_avatar_color.sql`），
  本 spec 取 **`V43__budget_reminder.sql`**，不修改任何既有迁移脚本。
- **账号注销**：与 custom-reminder 同一取舍 —— 本 spec 新增表不建指向 `users(id)` 的外键，注销时由
  `AccountDeletionService` 在同一事务内按 `user_id` 显式删除。

## Glossary

- **预算提醒系统（Budget_Reminder_System）**：本 spec 涉及的预算阈值评估、预算提醒偏好、独立订阅额度管理、
  收件人筛选、订阅消息下发、发送记录与相关接口的整体。
- **预算范围（budget scope）**：一次预算提醒所针对的预算对象，取二者之一：**月度总预算**（scope 记为 `TOTAL`）或
  **某个分类预算**（scope 记为该分类的 `category_id`）。
- **预警级别（level）**：预算提醒的严重程度，取值区分大小写的 `WARN`（预警）或 `OVER`（超支）之一。
- **预警线（warn threshold）**：某预算范围的已用比例 **>= 80% 且 <= 100%**，对应级别 `WARN`（与 `BudgetService` 一致）。
- **超支线（over threshold）**：某预算范围的已支出严格大于其预算金额（已用比例 **> 100%**），对应级别 `OVER`。
- **已支出（spent）**：某账本某自然月内 `type=expense` 的交易金额之和，按 `Asia/Shanghai` 自然月半开区间
  `[当月 1 日 00:00, 次月 1 日 00:00)` 聚合，排除转账与收入（复用 `BudgetService` 口径）。
- **当前自然月（current month）**：服务端当前时刻按 `Asia/Shanghai` 折算所得的 `YYYY-MM`。
- **触发交易（triggering transaction）**：其写入、修改、删除或恢复引发一次预算提醒评估的交易；仅当其发生月为
  当前自然月且其账本为个人 / 家庭账本时才引发评估。
- **收件人（recipient）**：某账本触发时的候选接收用户，须同时满足：为该账本的有效成员、已开启预算提醒、
  `wx_openid` 非空、预算提醒剩余订阅次数大于 0。
- **预算提醒偏好（budget reminder preference / enabled）**：某用户是否接收预算提醒的布尔开关；无记录时视为开启（缺省开启）。
- **预算提醒订阅消息（budget subscription message）**：经**独立的预算提醒模板**下发的微信一次性订阅消息，
  每成功发送一条消耗该用户一次预算提醒订阅授权额度。
- **预算提醒剩余订阅次数（budget remaining quota）**：某用户当前累积、尚未消耗的**预算提醒**订阅授权额度，
  取值为 0 到 50 的整数，独立于记账提醒的 `reminder_quota`。
- **预算提醒发送记录（budget send log）**：一次预算提醒发送尝试的落表结果，记录收件人、账本、自然月、
  预算范围、级别、发送结果与微信错误码。
- **发送结果（send result）**：取值区分大小写的 `SENT`（已发送）、`SKIPPED_NO_QUOTA`（无额度跳过）、
  `SKIPPED_NO_OPENID`（无 openid 跳过）、`FAILED`（微信返回错误或调用异常）四者之一。
- **时区口径**：本 spec 全部自然日 / 自然月 / 时刻边界的计算时区，取 `Asia/Shanghai`（固定偏移 UTC+08:00，
  与预算模块、custom-reminder 同一口径）。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌。
- **miniapp**：微信小程序端（uni-app / Vue 3）。
- **提醒设置页**：miniapp 展示与管理提醒（含记账提醒与预算提醒）的页面。

## Requirements

### 需求 1：预算提醒偏好设置

**用户故事：** 作为用户，我希望能一键打开或关掉「预算超支提醒」，不想被打扰时可以随时关。

#### 验收标准

1. THE 预算提醒系统 SHALL 提供已认证用户查询自身预算提醒状态的接口，其响应 SHALL 恰好包含 `enabled`（布尔，
   预算提醒偏好）与 `remainingQuota`（整数，预算提醒剩余订阅次数）两项。
2. WHEN 已认证用户查询自身预算提醒状态 AND 该用户尚无预算提醒偏好记录 THEN THE 预算提醒系统 SHALL 返回
   `enabled` 为真、`remainingQuota` 为 0（缺省开启、初始无额度）。
3. THE 预算提醒系统 SHALL 提供已认证用户更新自身预算提醒偏好的接口，接收布尔字段 `enabled`。
4. WHEN 已认证用户提交预算提醒偏好 AND `enabled` 为合法布尔值 THEN THE 预算提醒系统 SHALL 把该用户的预算提醒偏好
   置为提交值、把更新时间置为服务端当前时刻，并返回更新后的 `enabled` 与 `remainingQuota`。
5. IF 更新偏好请求的 `enabled` 缺失或无法解析为布尔值 THEN THE 预算提醒系统 SHALL 拒绝更新并返回错误码
   `BUDGET_REMINDER_PREF_INVALID`，且 SHALL 保持该用户的预算提醒偏好不变。
6. WHILE 某用户的预算提醒偏好为假，THE 预算提醒系统 SHALL 不向该用户发送任何预算提醒（不将其纳入收件人）。

### 需求 2：预算阈值评估与触发

**用户故事：** 作为用户，我某个月花超了预算或者快花超了，希望应用第一时间提醒我，而不用我自己天天盯着看。

#### 验收标准

1. WHEN 某笔交易在某个人或家庭账本内写入、修改、删除或恢复成功 AND 该交易的发生月为当前自然月 THEN THE
   预算提醒系统 SHALL 对该账本该当前自然月的月度总预算与每个已设分类预算逐一评估其预警级别（发送与去重见需求 3、4）。
2. WHERE 触发交易所属账本为 AA 账本 THE 预算提醒系统 SHALL 不进行任何预算提醒评估、不产生任何发送尝试
   （AA 账本不设预算）。
3. IF 触发交易的发生月不是当前自然月 THEN THE 预算提醒系统 SHALL 不进行任何预算提醒评估（只对当前自然月的预算触发）。
4. THE 预算提醒系统 SHALL 以「某预算范围的已支出严格大于其预算金额」判定该范围达到 `OVER` 级别，
   以「某预算范围的已用比例大于或等于 80% 且小于或等于 100%」判定该范围达到 `WARN` 级别，其已支出与已用比例的口径
   SHALL 与 `BudgetService` 的自然月 `type=expense` 聚合完全一致。
5. IF 某预算范围未设预算金额或其预算金额小于或等于 0 THEN THE 预算提醒系统 SHALL 不对该范围产生任何级别的发送尝试。
6. WHEN 对某预算范围评估 AND 其同时达到 `WARN` 与 `OVER` 的判定 THEN THE 预算提醒系统 SHALL 只按 `OVER` 级别
   处理该范围（超支级别优先于预警级别）。
7. THE 预算提醒系统 SHALL 以 `Asia/Shanghai`（固定偏移 UTC+08:00）判定当前自然月与交易发生月，
   且 SHALL 不依赖 JVM、数据库会话或操作系统的默认时区取值。
8. WHEN 交易接口在评估预算提醒的过程中发生任何异常 THEN THE 预算提醒系统 SHALL 就地捕获该异常、
   记录一条不含金额、邮箱与令牌的告警日志，且 SHALL 不改变该交易接口的响应字段集、字段取值、HTTP 状态码与错误码
   （评估失败不回滚、不阻断交易主路径）。

### 需求 3：发送去重与「每月每范围每级别至多一次」

**用户故事：** 作为用户，我不想每记一笔就被同样的超支提醒轰炸，同一件事提醒我一次就够了。

#### 验收标准

1. THE 预算提醒系统 SHALL 使同一收件人、同一账本、同一自然月、同一预算范围、同一级别至多产生一条发送结果为
   `SENT` 的发送记录（每月每范围每级别至多推送一次）。
2. WHEN 对某收件人某范围某级别评估 AND 该收件人该账本该自然月该范围该级别已存在任一发送记录 THEN THE 预算提醒系统
   SHALL 不再为其生成第二次发送尝试。
3. WHEN 对某收件人某范围评估 AND 该范围已达 `OVER` 级别 AND 该收件人该账本该自然月该范围的 `OVER` 级别已存在
   发送记录 THEN THE 预算提醒系统 SHALL 不再为该范围该月的 `WARN` 级别生成任何发送尝试（超支已推送后不再补推预警）。
4. IF 发送记录写入过程中因唯一键冲突失败（并发触发）THEN THE 预算提醒系统 SHALL 放弃本次发送尝试、
   SHALL 不重复调用微信发送接口，且 SHALL 不返回错误（幂等）。
5. THE 预算提醒系统 SHALL 使同一预算范围在跨自然月后重新独立计次：某收件人在某自然月已收到某范围某级别的提醒后，
   WHEN 进入下一自然月并再次达到该级别 THEN THE 预算提醒系统 SHALL 视其为新的一次并允许推送。

### 需求 4：收件人筛选与订阅消息下发

**用户故事：** 作为开发者，我要让预算提醒只发给该账本里真正开了提醒、授权过、还有额度的成员，发不出去也不能报错。

#### 验收标准

1. WHEN 某账本某范围某级别需要推送 THEN THE 预算提醒系统 SHALL 把收件人集合确定为该账本的有效成员中，
   同时满足「预算提醒偏好为真、`wx_openid` 非空、预算提醒剩余订阅次数大于 0」的全部用户，并对每名收件人各生成一次
   发送尝试。
2. WHEN 对某收件人生成发送尝试 AND 该收件人预算提醒剩余订阅次数大于 0 AND 其 `wx_openid` 非空 THEN THE 预算提醒系统
   SHALL 经 `WeChatAccessTokenProvider` 取凭证、调用微信 `subscribeMessage.send` 向该 `wx_openid` 下发含需求 5
   所选文案的预算提醒订阅消息；WHEN 微信返回零错误码（视为成功）THEN THE 预算提醒系统 SHALL 以发送结果 `SENT`
   写入发送记录，并 SHALL 把该收件人预算提醒剩余订阅次数减 1（减 1 后不小于 0）。
3. IF 生成发送尝试时该收件人预算提醒剩余订阅次数为 0 THEN THE 预算提醒系统 SHALL 不调用微信发送接口、
   SHALL 以发送结果 `SKIPPED_NO_QUOTA` 写入发送记录，且 SHALL 不改变其剩余订阅次数。
4. IF 生成发送尝试时该收件人 `wx_openid` 为空值 THEN THE 预算提醒系统 SHALL 不调用微信发送接口、
   SHALL 以发送结果 `SKIPPED_NO_OPENID` 写入发送记录、SHALL 不消耗其剩余订阅次数，且 SHALL 不返回错误。
5. IF 微信 `subscribeMessage.send` 返回非零错误码或调用抛出异常 THEN THE 预算提醒系统 SHALL 以发送结果 `FAILED`
   写入发送记录并记录该微信错误码、SHALL 记录一条含用户 id 且不含金额、邮箱与令牌的告警日志、
   SHALL 不因该失败扣减该收件人预算提醒剩余订阅次数（保持发送前取值不变、任何情形下不小于 0），
   且 SHALL 不向记账、登录、注销与结算路径传播该异常。
6. IF 微信 `subscribeMessage.send` 返回表示用户拒收或额度不足的错误码（如 `43101`）THEN THE 预算提醒系统
   SHALL 把该收件人预算提醒剩余订阅次数置为 0（微信侧已无额度，本地计数须归零对齐）。
7. THE 预算提醒系统 SHALL 复用 `WeChatAccessTokenProvider` 获取 `access_token`，SHALL 不新建第二套凭证获取或
   凭证缓存逻辑，且 SHALL 不自行调用 `cgi-bin/token`。
8. THE 预算提醒系统 SHALL 通过**独立于记账提醒**的预算提醒模板下发，SHALL 由服务端配置项提供该模板 id；
   IF 该模板 id 未配置或为空 THEN THE 预算提醒系统 SHALL 以发送结果 `FAILED` 记录并安全降级为不发送，
   SHALL 不消耗任何额度，且 SHALL 不影响任何主路径（未配置即静默不发）。

### 需求 5：预算提醒文案

**用户故事：** 作为用户，我收到的提醒要能一眼看出是哪个预算、快超了还是已经超了。

#### 验收标准

1. THE 预算提醒系统 SHALL 使预算提醒文案指明预算范围（月度总预算或具体分类名称）与级别（预警或超支）两项要素。
2. WHEN 生成 `OVER` 级别的发送尝试 THEN THE 预算提醒系统 SHALL 选用表意为「该预算范围本月已超支」的超支文案。
3. WHEN 生成 `WARN` 级别的发送尝试 THEN THE 预算提醒系统 SHALL 选用表意为「该预算范围本月已接近预算上限」的预警文案。
4. WHERE 预算范围为某个分类预算 THE 预算提醒系统 SHALL 在文案中体现该分类的当前名称；IF 该分类已被删除或名称不可得
   THEN THE 预算提醒系统 SHALL 以「该分类」之类的占位表述替代，且 SHALL 不因此中止发送或抛出异常。
5. THE 预算提醒系统 SHALL 使每条预算提醒文案落入微信订阅消息模板对应字段的长度限制之内，
   且 SHALL 不在文案中包含收件人邮箱、令牌与其它用户的任何信息。
6. WHEN 生成任一发送尝试 THEN THE 预算提醒系统 SHALL 从上述超支与预警两类文案中按级别恰好选用一条，
   且 SHALL 不产生级别与文案不一致的发送尝试。

### 需求 6：预算提醒订阅授权与额度

**用户故事：** 作为用户，微信要我点「允许」才能收到预算提醒，我希望应用能记住我还能收到几次、什么时候该再授权。

#### 验收标准

1. THE 预算提醒系统 SHALL 提供已认证用户上报预算提醒订阅授权的接口，接收本次经 `wx.requestSubscribeMessage`
   对**预算提醒模板**点击「允许」的次数 `grantedCount`（整数，取值范围 1 到 5）。
2. WHEN 已认证用户上报预算提醒订阅授权 AND `grantedCount` 落在 1 到 5 之间 THEN THE 预算提醒系统 SHALL 把该用户的
   预算提醒剩余订阅次数增加 `grantedCount`，并 SHALL 返回增加后的预算提醒剩余订阅次数。
3. THE 预算提醒系统 SHALL 使预算提醒剩余订阅次数落在 0 到 50 的闭区间内；WHEN 上报授权将使其超过 50 THEN THE
   预算提醒系统 SHALL 把其置为 50（累积上限）。
4. IF `grantedCount` 缺失、无法解析为整数、小于 1 或大于 5 THEN THE 预算提醒系统 SHALL 拒绝上报并返回错误码
   `BUDGET_REMINDER_GRANT_INVALID`，且 SHALL 保持该用户的预算提醒剩余订阅次数不变。
5. THE 预算提醒系统 SHALL 以 `user_id` 作为预算提醒剩余订阅次数的唯一统计维度，SHALL 使同一用户在全部设备与会话
   共享同一计数，且 SHALL 对该计数的每次增加与减少执行原子更新，使并发的上报授权与发送扣减不产生丢失更新
   （最终计数等于所有增减操作的净和）。
6. THE 预算提醒系统 SHALL 使预算提醒的订阅额度独立于记账提醒的 `reminder_quota`：SHALL 不因预算提醒的授权或扣减
   改变记账提醒的剩余订阅次数，也 SHALL 不因记账提醒的授权或扣减改变预算提醒的剩余订阅次数。

### 需求 7：权限边界

**用户故事：** 作为用户，我的预算提醒偏好与额度只有我能看能改，别人动不了。

#### 验收标准

1. THE 预算提醒系统 SHALL 要求查询状态、更新偏好、上报订阅授权全部接口携带**有效令牌**。
2. IF 请求未携带令牌、令牌签名校验失败、令牌已过期、或其标识的用户在 `users` 表中不存在 THEN THE 预算提醒系统
   SHALL 返回错误码 `UNAUTHENTICATED`（优先于任何字段校验），且 SHALL 保持预算提醒偏好与剩余订阅次数不变，
   响应中 SHALL 不包含任何偏好或额度字段值。
3. THE 预算提醒系统 SHALL 把上述接口的数据范围硬性限定为当前会话用户本人的偏好与额度，
   SHALL 以有效令牌所标识的用户 id 作为唯一的数据归属依据，SHALL 忽略请求中任何用于指定目标用户身份的
   查询参数、路径参数、请求体字段与自定义请求头，且 SHALL 不因请求携带此类字段而返回错误码。
4. THE 预算提醒系统 SHALL 使这三个接口与会话账本无关，SHALL 不要求请求携带 `X-Ledger-Id` 头、
   SHALL 不因该头缺失或取值不可访问而拒绝请求。
5. THE 预算提醒系统 SHALL 以统一错误体格式 `{code, message, field}` 返回错误，其字段集 SHALL 恰好为这 3 项；
   THE 预算提醒系统 SHALL 使 `message` 为长度不超过 100 个字符的中文文案，且不含用户 id、邮箱与令牌三类内容。
6. WHEN 错误与某个具体输入字段无关（如 `UNAUTHENTICATED`）THEN THE 预算提醒系统 SHALL 使错误体的 `field` 取空值，
   且 SHALL 不因该情形省略 `code` 与 `message` 两项。

### 需求 8：数据模型与迁移

**用户故事：** 作为开发者，我需要预算提醒的偏好 / 额度与发送记录有清晰的表结构和迁移脚本，能与既有 Flyway 体系一致演进。

#### 验收标准

1. THE 迁移脚本 SHALL 命名为 `V43__budget_reminder.sql`，SHALL 置于 `src/main/resources/db/migration`，
   且 SHALL 不修改任何已存在的迁移脚本文件。
2. THE 迁移脚本 SHALL 新建 `budget_reminder_settings` 表，该表 SHALL 恰好包含以下 5 列：
   `user_id`（BIGINT NOT NULL，主键）、`enabled`（TINYINT(1) NOT NULL，缺省 1）、`remaining`（INT NOT NULL，缺省 0）、
   `created_at`（DATETIME NOT NULL）、`updated_at`（DATETIME NOT NULL）；SHALL 建立名为
   `ck_budget_reminder_settings_remaining` 的具名 CHECK 约束，其表达式为 `remaining >= 0`。
3. THE 迁移脚本 SHALL 新建 `budget_reminder_send_logs` 表，该表 SHALL 恰好包含以下 9 列：
   `id`（BIGINT NOT NULL AUTO_INCREMENT，主键）、`user_id`（BIGINT NOT NULL）、`ledger_id`（BIGINT NOT NULL）、
   `budget_month`（VARCHAR(7) NOT NULL）、`scope_ref`（BIGINT NOT NULL，`0` 表示月度总预算范围、大于 0 表示分类 id）、
   `level`（VARCHAR(8) NOT NULL）、`result`（VARCHAR(24) NOT NULL）、`wx_errcode`（INT NULL）、
   `created_at`（DATETIME NOT NULL）。
4. THE 迁移脚本 SHALL 为 `budget_reminder_send_logs` 建立名为 `uk_budget_reminder_send_logs_scope` 的具名唯一约束，
   其列序恰为 `user_id`、`ledger_id`、`budget_month`、`scope_ref`、`level`（构造性保证需求 3.1 的「每月每范围每级别至多一次」）。
5. THE 迁移脚本 SHALL 建立名为 `ck_budget_reminder_send_logs_level` 的具名 CHECK 约束，
   把 `level` 限制为区分大小写的 `WARN`/`OVER`。
6. THE 迁移脚本 SHALL 使两张新表的存储引擎为 `InnoDB`、字符集为 `utf8mb4`、排序规则为 `utf8mb4_unicode_ci`，
   并 SHALL 为每张表与其每一列写非空的中文注释（与既有迁移脚本同一风格）。
7. THE 迁移脚本 SHALL 不建立指向 `users(id)`、`ledgers(id)` 或分类表的外键
   （与 `user_growth`、`custom_reminders` 等同一取舍：注销时由服务层在同一事务内显式删除）。
8. WHEN 某用户完成账号注销 THEN THE 应用 SHALL 在注销的同一事务内删除该用户在 `budget_reminder_settings` 与
   `budget_reminder_send_logs` 两张表的全部行、SHALL 使该用户在这两张表的行数均为 0，且 SHALL 不使注销接口的
   响应字段集、HTTP 状态码与既有错误码发生变化。
9. IF 注销事务内对这两张表任一表的删除失败 THEN THE 应用 SHALL 回滚整个注销事务（不产生部分删除），
   且 SHALL 经既有错误码返回失败。
10. THE 迁移脚本 SHALL 在 MySQL 与 H2 `MODE=MySQL` 两种执行环境下均无错误执行完成并在 `flyway_schema_history`
    中记为成功，且 SHALL 不使用窗口函数、`CONVERT_TZ`、存储过程与触发器四类构造。
11. THE 清库脚本 `deploy/reset-db.sql` SHALL 在 `SET FOREIGN_KEY_CHECKS = 0` 与 `SET FOREIGN_KEY_CHECKS = 1` 之间、
    且在 `TRUNCATE TABLE users` 之前清空 `budget_reminder_settings` 与 `budget_reminder_send_logs` 两张表；
    脚本执行后这两张表行数 SHALL 为 0，表结构与 `flyway_schema_history` SHALL 保留。
12. WHEN 应用以 Hibernate `ddl-auto=validate` 在迁移完成的数据库上启动 THEN THE 应用 SHALL 启动成功
    且 SHALL 不抛出针对这两张表的 schema 校验异常。

### 需求 9：与既有体系的兼容边界

**用户故事：** 作为开发者，我要确认这次改动是纯增量的：把预算提醒整块摘掉，其余功能仍原样成立。

#### 验收标准

1. THE 预算提醒系统 SHALL 只读取 `budgets`、`category_budgets`、`transactions`、`ledgers`、`ledger_members`、
   `categories` 与 `users.wx_openid` 用于评估与投递，SHALL 不对上述任一表执行插入、更新或删除语句，
   且 SHALL 在其任意接口调用与评估执行的前后使上述各表各列取值不变。
2. WHEN 从库中删除 `budget_reminder_settings` 与 `budget_reminder_send_logs` 两张表的全部行 THEN THE 记账、预算、
   登录、注销、成长、成就、连续记账与记账提醒（custom-reminder）系统 SHALL 对相同请求在相同数据前置条件下
   保持其全部接口的响应字段集、字段取值、HTTP 状态码与错误码不变。
3. THE 预算提醒系统 SHALL 只新增 `BUDGET_REMINDER_PREF_INVALID` 与 `BUDGET_REMINDER_GRANT_INVALID` 两个错误码，
   且 SHALL 复用既有的 `UNAUTHENTICATED` 错误码、不重命名任何既有错误码。
4. THE 预算提醒系统 SHALL 使预算提醒的评估故障、微信接口故障与订阅额度耗尽均不改变记账、预算、登录、注销与结算
   路径接口的响应字段集、字段取值、HTTP 状态码与既有错误码，且 SHALL 不向上述路径传播任何异常。
5. THE 预算提醒系统 SHALL 使数据库变更为纯增量：除 `budget_reminder_settings` 与 `budget_reminder_send_logs`
   两张新表外，SHALL 不对任何既有表执行 `ALTER`、`DROP` 或行写入语句。
6. THE 预算提醒系统 SHALL 使 custom-reminder 的记账提醒行为、`reminder_quota` 与 `reminder_send_logs` 数据
   在预算提醒的任意接口调用与评估执行的前后逐项不变（两套提醒的额度与发送记录互不影响）。

### 需求 10：提醒设置页与预算提醒入口

**用户故事：** 作为用户，我希望在提醒设置页里既能管记账提醒，也能开关预算提醒、给预算提醒授权。

#### 验收标准

1. THE miniapp SHALL 在提醒设置页新增「预算提醒」区块，SHALL 在该区块展示预算提醒开关（`enabled`）与
   预算提醒剩余订阅次数，且 SHALL 与既有记账提醒区块并列呈现、不改变记账提醒区块的既有行为。
2. WHEN 用户打开提醒设置页 THEN THE miniapp SHALL 请求一次预算提醒状态，SHALL 在请求返回之前展示占位状态，
   并 SHALL 在返回之后以真实取值渲染开关与剩余订阅次数。
3. WHEN 用户切换预算提醒开关 THEN THE miniapp SHALL 调用更新偏好接口提交 `enabled`，并 SHALL 在接口返回成功后
   就地更新开关展示。
4. WHEN 用户在预算提醒区块触发订阅授权 THEN THE miniapp SHALL 调用 `wx.requestSubscribeMessage` 请求**预算提醒模板**
   授权，并 SHALL 在用户点击「允许」后调用上报预算提醒订阅授权接口把本次授权次数上报给服务端。
5. IF `wx.requestSubscribeMessage` 返回用户拒绝或调用失败 THEN THE miniapp SHALL 不调用上报授权接口、
   SHALL 展示未授权的提示文案与再次授权的入口，且 SHALL 不使页面进入错误态。
6. WHERE 预算提醒剩余订阅次数为 0 THE miniapp SHALL 展示引导用户再次授权的文案，
   且 SHALL 明确提示「授权后才能继续收到预算提醒」（对齐微信一次性订阅的额度耗尽语义）。
7. IF 预算提醒状态、更新偏好或上报授权接口返回错误标识，或请求自发出起 3000 毫秒内无响应 THEN THE miniapp
   SHALL 展示失败提示与重试入口、SHALL 使该请求的自动重试次数为 0，且 SHALL 保持其余已加载内容不变。
8. IF 当前不存在已登录状态 THEN THE miniapp SHALL 不发起任何预算提醒接口请求、SHALL 不展示预算提醒的真实取值，
   且 SHALL 展示登录入口以引导用户登录。
9. THE miniapp SHALL 复用项目既有的请求封装与提醒相关 API 模块获取与提交预算提醒数据，
   且 SHALL 不在预算提醒区块展示任何金额、账本名称、邮箱与邀请码。
