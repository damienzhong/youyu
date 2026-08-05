# Requirements Document

## Introduction

有余（youyu）目前没有任何主动提醒能力：用户是否每天记账全靠自觉，一旦忘记，连续记账（streak-system）
就断了、成长体系（growth-level-system）的当前连续天数归零，而用户在事后才发现。本次新增
**自定义提醒（Custom_Reminder_System）**，让用户自己设定「什么时候来提醒我记账」，并让提醒文案随
「今天到底记了没有」自动切换：

- **提醒频率**：每天（DAILY）、工作日（WEEKDAY，周一至周五）、周末（WEEKEND，周六与周日）三选一。
- **提醒时间**：用户指定一天中的某个时刻（时:分，分钟粒度），到点触发当天的提醒。
- **自适应文案**：触发时刻按「今日已记账」判定二选一——**未记账**时发「今天还没记账哦~」、
  **已记账**时发「今天已经完成啦~」；判定复用连续记账/成长体系既有的记账日历口径，不另立第二套。
- **投递通道**：微信小程序的**一次性订阅消息**（`subscribeMessage.send`）。这是个人主体小程序唯一可用的
  提醒下发方式，每条订阅消息消耗用户一次订阅授权额度。
- **提醒设置页**：miniapp 新增页面，用户在此新增/编辑/开关/删除提醒，并按微信要求逐次授权订阅、
  查看剩余可提醒次数。

### 范围与前提约定（影响验收标准的关键决策）

以下八项是本 spec 的决策骨架，验收标准全部围绕它们展开。

1. **投递通道锁定为微信一次性订阅消息，不做站内推送、短信与邮件。** 有余是个人主体微信小程序
   （`manifest.json` 的 `mp-weixin.appid = wx58eeb3784f3d644f`），个人主体**只能使用一次性订阅**、
   拿不到长期订阅。因此每发送一条提醒都要消耗用户此前授予的**一次订阅授权额度**，额度耗尽即无法再发，
   必须由用户再次授权补充。这是微信平台的硬约束，不是本 spec 的设计选择；本 spec 的额度管理
   （需求 5）、发送与跳过（需求 6）全部建立在这条约束之上。
2. **「今日已记账」的判定复用记账日历，不新开第二口径。** 触发时刻是否已记账，等价于
   `StreakJudgment.todayDone(user_growth.last_record_date, 判定日)`（判定日为触发时刻按时区口径折算所得的
   自然日）——即最近记账日不早于判定日。刻意**不**在提醒里另查 `transactions` 表：两套口径会在结算未收敛
   时彼此矛盾（提醒说「已完成」而连续天数不含今天），单一事实源的代价只是极少数情形下短暂发出
   「还没记账」，可接受。
3. **频率按自然日的星期几判定，不接入法定节假日与调休。** 工作日固定为周一至周五、周末固定为周六与周日，
   均按 `Asia/Shanghai` 折算所得自然日的星期几判定，**不识别**国务院法定节假日与调休安排
   （否则要引入并长期维护一份逐年更新的节假日表，且用户对「记账提醒是否跟着调休走」并无强预期）。
   该取舍写死在验收标准里，避免日后含糊。
4. **提醒是「到点触发一次」，不是待办清单。** 每条提醒在其每个触发日**至多**产生一次发送尝试；
   同一提醒同一自然日不重复发送，由发送记录的唯一键构造性保证（需求 6、需求 9），
   而不是靠调度器不重叠这种时序巧合。
5. **调度器是本项目第一个定时任务。** 触发依赖服务端定时扫描（需求 3），本 spec 引入
   `@EnableScheduling` 与一个分钟粒度的调度任务。为容忍进程重启与调度抖动，设一个有界的**追补窗口**：
   触发时刻已过但仍落在追补窗口内且当天尚未发送的提醒仍会补发；超出窗口的过期提醒直接跳过，
   不发「昨天/几小时前」的陈旧提醒。
6. **文案两选一，两种都发。** 用户明确要求「已完成」也要有文案（「今天已经完成啦~」），
   因此已记账时同样发送提醒（正向反馈），而非静默跳过。文案取值集合恰为两条，二者按「今日已记账」互斥选择。
7. **提醒与订阅数据是纯增量，不改动既有体系的任何行为。** 本 spec 只读 `user_growth.last_record_date` 与
   `users.wx_openid`，不写这两张表；不改记账、预算、登录、注销、邀请、成长、成就、连续记账的任何既有验收标准。
   删掉本 spec 新增的表，其余功能原样成立。
8. **提醒收发的任何故障都不得阻断记账等主路径。** 发送失败、微信接口异常、额度耗尽一律就地捕获、
   只记告警日志并写发送记录，绝不向记账、登录等路径抛出。

### 与其它 spec 的关系

- **依赖 growth-level-system / streak-system（均已实现）**：复用 `user_growth.last_record_date` 记账日历口径与
  `StreakJudgment.todayDone` 判定纯函数决定「今日已记账」。本 spec 只读、不改其任何一条验收标准。
- **依赖既有微信集成**：复用 `WeChatAccessTokenProvider`（全项目唯一获取 `access_token` 的地方）取凭证，
  经 `WeChatClient` 调用微信 `subscribeMessage.send`。本 spec 不新建第二套凭证获取或缓存逻辑。
- **迁移版本号**：`src/main/resources/db/migration` 当前最大版本号为 34（`V34__streak.sql`），
  `V30` 由 user-feedback-system 预占（尚未落地）。本 spec 取 **`V35__custom_reminder.sql`**。
- 与 invite-system、user-feedback-system、account-ledger-redesign、auth-email-passwordless 无功能耦合。

## Glossary

- **自定义提醒系统（Custom_Reminder_System）**：本 spec 涉及的提醒配置、订阅额度管理、定时触发、
  文案选择、订阅消息下发与提醒查询接口的整体。
- **提醒（reminder）**：用户创建的一条提醒配置，对应 `custom_reminders` 表一行，由频率、提醒时间与
  启用状态三项确定，主键 `reminder_id`。
- **频率（frequency）**：提醒的重复模式，取值区分大小写的 `DAILY`（每天）、`WEEKDAY`（工作日，周一至周五）、
  `WEEKEND`（周末，周六与周日）三者之一。
- **提醒时间（remind_time）**：提醒在一天中的触发时刻，格式为 `HH:mm`（24 小时制，分钟粒度），
  取值范围 `00:00` 到 `23:59`。
- **启用状态（enabled）**：提醒是否生效的布尔开关；停用的提醒保留配置但不参与触发。
- **触发日（trigger day）**：某自然日，其按 `Asia/Shanghai` 折算所得的星期几与某提醒的频率相符
  （`DAILY` 命中每一天；`WEEKDAY` 命中周一至周五；`WEEKEND` 命中周六与周日）。
- **触发时刻（trigger moment）**：某触发日的 `提醒时间` 所对应的时刻。
- **判定日**：触发时刻（或概览请求时刻）按时区口径折算所得的自然日。
- **今日已记账（today_recorded）**：判定日在记账日历中的状态，等价于
  `StreakJudgment.todayDone(user_growth.last_record_date, 判定日)` 为真，即最近记账日不早于判定日。
- **提醒文案（reminder message）**：下发给用户的正文，取值集合恰为两条：今日已记账时为「今天已经完成啦~」，
  今日未记账时为「今天还没记账哦~」。
- **订阅消息（subscription message）**：微信小程序**一次性订阅消息**（服务端 `subscribeMessage.send`），
  每成功发送一条消耗一次订阅授权额度。
- **订阅授权（subscription grant）**：用户在 miniapp 端经 `wx.requestSubscribeMessage` 对本提醒模板点击
  「允许」后微信授予的一次发送许可。
- **剩余订阅次数（remaining_quota）**：某用户当前累积、尚未消耗的订阅授权额度，取值为大于或等于 0 的整数，
  对应 `reminder_quota.remaining` 列。
- **发送记录（send log）**：一次提醒发送尝试的落表结果，对应 `reminder_send_logs` 表一行，
  记录用户、提醒、触发日、发送结果与微信错误码。
- **发送结果（send result）**：取值区分大小写的 `SENT`（已发送）、`SKIPPED_NO_QUOTA`（无额度跳过）、
  `SKIPPED_STALE`（超出追补窗口跳过）、`FAILED`（微信返回错误）四者之一。
- **调度器（scheduler）**：本 spec 新增的分钟粒度定时任务，周期性扫描到点的提醒并触发发送。
- **追补窗口（catch-up window）**：触发时刻已过但仍允许补发的最长时长，取 10 分钟。
- **时区口径**：本 spec 全部自然日与时刻边界的计算时区，取 `Asia/Shanghai`（固定偏移 UTC+08:00，
  与 growth-level-system、streak-system 同一口径，不随夏令时变化）。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌。
- **miniapp**：微信小程序端（uni-app / Vue 3）。
- **提醒设置页**：miniapp 新增页面 `pages/reminder/reminder`，用于新增、编辑、开关、删除提醒与订阅授权。

## Requirements

### 需求 1：创建自定义提醒

**用户故事：** 作为用户，我想自己设定一个记账提醒（选每天/工作日/周末，再挑个时间），这样我不会忘记记账。

#### 验收标准

1. THE 自定义提醒系统 SHALL 提供已认证用户创建提醒的接口，接收 `frequency` 与 `remindTime` 两个必填字段，
   并接收可选的 `enabled` 字段（缺省为真）。
2. WHEN 已认证用户创建提醒 AND `frequency` 属于 `DAILY`/`WEEKDAY`/`WEEKEND` AND `remindTime` 为 `HH:mm`
   格式且落在 `00:00` 到 `23:59` 之间 THEN THE 自定义提醒系统 SHALL 创建一条提醒，写入 `user_id` 为当前会话用户、
   `frequency` 与 `remind_time` 为提交值、`enabled` 为提交的启用状态、`created_at` 与 `updated_at` 为服务端当前时刻，
   并返回该提醒的 `reminder_id`（正整数）、`frequency`、`remindTime` 与 `enabled`。
3. IF `frequency` 缺失、为空值、或其取值（区分大小写）不属于 `DAILY`/`WEEKDAY`/`WEEKEND` THEN THE 自定义提醒系统
   SHALL 拒绝创建并返回错误码 `REMINDER_FREQUENCY_INVALID`，且 SHALL 保持 `custom_reminders` 表数据不变（不新增任何行）。
4. IF `remindTime` 缺失、为空值、不符合 `HH:mm` 格式（须为零填充的两位小时与两位分钟，不含秒）、或其小时不在 0 到 23、分钟不在 0 到 59 之内 THEN THE
   自定义提醒系统 SHALL 拒绝创建并返回错误码 `REMINDER_TIME_INVALID`，且 SHALL 保持 `custom_reminders` 表数据不变。
5. IF 当前会话用户已存在与本次提交的 `frequency` 与 `remind_time` 两项均相同的提醒（无论该已存在提醒的启用状态为真或假）THEN THE 自定义提醒系统
   SHALL 拒绝创建并返回错误码 `REMINDER_DUPLICATE`，且 SHALL 保持 `custom_reminders` 表数据不变（同一频率同一时间不重复建两条）。
6. IF 当前会话用户启用与停用的提醒总数已达到上限 10 条（即已有 10 条时拒绝创建第 11 条）THEN THE 自定义提醒系统 SHALL 拒绝创建并返回错误码
   `REMINDER_LIMIT_EXCEEDED`，且 SHALL 保持 `custom_reminders` 表数据不变。
7. THE 自定义提醒系统 SHALL 以 `Asia/Shanghai`（固定偏移 UTC+08:00）解释 `remind_time`，
   且 SHALL 不依赖 JVM、数据库会话或操作系统的默认时区取值。
8. WHEN 已认证用户创建提醒 THEN THE 自定义提醒系统 SHALL 使每一次创建请求的服务端处理耗时（不含网络传输耗时）不超过 2000 毫秒，并在该耗时内返回成功结果或错误标识。
9. WHEN 已认证用户创建提醒 AND 该请求同时满足第 3、4、5、6 条中的两条或以上拒绝条件 THEN THE 自定义提醒系统 SHALL 仅返回优先级最高的单一错误码，其优先级由高到低固定为 `REMINDER_FREQUENCY_INVALID`、`REMINDER_TIME_INVALID`、`REMINDER_DUPLICATE`、`REMINDER_LIMIT_EXCEEDED`（`UNAUTHENTICATED` 仍依需求 8 优先于以上全部字段与业务校验），且 SHALL 保持 `custom_reminders` 表数据不变。

### 需求 2：提醒频率与触发日判定

**用户故事：** 作为用户，我选了「工作日」就只想在周一到周五被提醒，选了「周末」就只想在周六周日被提醒。

#### 验收标准

1. THE 自定义提醒系统 SHALL 使某自然日为频率 `DAILY` 提醒的触发日，无论该自然日为星期几。
2. THE 自定义提醒系统 SHALL 使某自然日为频率 `WEEKDAY` 提醒的触发日，当且仅当该自然日按 `Asia/Shanghai`
   折算所得的星期几为周一、周二、周三、周四或周五。
3. THE 自定义提醒系统 SHALL 使某自然日为频率 `WEEKEND` 提醒的触发日，当且仅当该自然日按 `Asia/Shanghai`
   折算所得的星期几为周六或周日。
4. THE 自定义提醒系统 SHALL 按自然日的星期几判定触发日，SHALL 不依据国务院法定节假日与调休安排调整判定
   （工作日恒为周一至周五、周末恒为周六与周日）。
5. IF 某提醒的启用状态为假 THEN THE 自定义提醒系统 SHALL 不使其任何自然日成为触发日（停用提醒不触发）。
6. THE 自定义提醒系统 SHALL 使某自然日按 `Asia/Shanghai` 折算所得的星期几不随 JVM、数据库会话或操作系统默认时区取值变化。
7. WHEN 运行环境的默认时区被改为任一其它时区 THEN THE 自定义提醒系统 SHALL 使同一提醒对同一自然日的触发日判定结果保持不变。
8. THE 自定义提醒系统 SHALL 以某时刻所属的 `Asia/Shanghai` 自然日（自当日 `00:00` 起至次日 `00:00` 之前的闭开区间）判定其星期几，且 SHALL 使落在 `Asia/Shanghai` 同一自然日内的所有时刻映射到同一星期几（跨该时区自然日边界的时刻以其所属自然日的星期几为准）。

### 需求 3：触发时刻与调度

**用户故事：** 作为用户，我设了 21:00 提醒，就希望大概在晚上九点收到，而不是拖到第二天或者干脆漏掉。

#### 验收标准

1. THE 调度器 SHALL 以本项目首次引入的 `@EnableScheduling` 启用一个分钟粒度的调度任务，SHALL 使相邻两次执行的启动间隔不超过 60 秒，且 SHALL 以 `Asia/Shanghai` 时区口径判定当前触发时刻（精确到时与分）。
2. WHEN 调度任务在某时刻执行 AND 某启用提醒按 `Asia/Shanghai` 时区口径的当前自然日为其触发日 AND 其 `remind_time` 的时与分等于当前触发时刻的时与分 THEN THE 自定义提醒系统 SHALL 为该提醒在该触发日生成一次发送尝试（发送与跳过的具体行为见需求 6）。
3. IF 某启用提醒的触发时刻已过、且已过时长在 0 到 10 分钟（追补窗口）闭区间内 AND 该提醒在该触发日尚无发送记录 THEN THE 自定义提醒系统 SHALL 为其补发一次发送尝试。
4. IF 某启用提醒的触发时刻已过且已过时长严格大于追补窗口（10 分钟）AND 该提醒在该触发日尚无发送记录 THEN THE 自定义提醒系统 SHALL 不发送该提醒、SHALL 以发送结果 `SKIPPED_STALE` 写入一条发送记录，且 SHALL 不消耗剩余订阅次数。
5. THE 自定义提醒系统 SHALL 使同一提醒在同一触发日至多产生一条发送结果为 `SENT` 的发送记录（到点触发一次，不重复发送）。
6. WHEN 调度任务的两次执行在时间上重叠、或进程重启后再次扫描同一触发时刻 THEN THE 自定义提醒系统 SHALL 依据发送记录的唯一键（需求 9）保证同一提醒同一触发日不产生第二条 `SENT` 记录。
7. THE 自定义提醒系统 SHALL 使调度任务的单次执行只处理触发时刻落在「当前时刻减追补窗口（10 分钟）」到「当前时刻」闭区间内的提醒，且 SHALL 不预发未来触发时刻的提醒。
8. IF 调度任务在对某启用提醒生成或执行发送尝试的过程中发生异常或发送失败 THEN THE 自定义提醒系统 SHALL 以发送结果 `FAILED` 写入一条发送记录、SHALL 不消耗该提醒的剩余订阅次数，且 SHALL 继续处理本次执行中的其余提醒而不中断调度任务。

### 需求 4：提醒文案自适应

**用户故事：** 作为用户，我今天要是还没记账，提醒该催我一句；要是已经记了，提醒不妨夸我一句。

#### 验收标准

1. THE 自定义提醒系统 SHALL 使提醒文案取值集合恰为两条：今日已记账时为「今天已经完成啦~」、今日未记账时为「今天还没记账哦~」。
2. WHEN 生成某提醒在触发时刻的发送尝试，IF 该用户在判定日今日已记账为真，THEN THE 自定义提醒系统 SHALL 选用文案「今天已经完成啦~」。
3. WHEN 生成某提醒在触发时刻的发送尝试，IF 该用户在判定日今日已记账为假，THEN THE 自定义提醒系统 SHALL 选用文案「今天还没记账哦~」。
4. WHEN 生成某提醒在触发时刻的发送尝试，THE 自定义提醒系统 SHALL 恰好从上述两条文案中选用一条，且 SHALL 不选用该集合以外的任何文案、SHALL 不产生未选用文案的发送尝试。
5. THE 自定义提醒系统 SHALL 以 `StreakJudgment.todayDone(user_growth.last_record_date, 判定日)` 作为今日已记账的唯一判定依据，SHALL 只读取 `user_growth.last_record_date`，且 SHALL 不查询 `transactions` 表判定今日已记账。
6. WHERE 某用户在 `user_growth` 中无记录（等价于最近记账日为空值）THE 自定义提醒系统 SHALL 判定今日已记账为假、选用文案「今天还没记账哦~」，且 SHALL 不返回错误、SHALL 不写入 `user_growth` 表。
7. THE 自定义提醒系统 SHALL 在触发时刻当刻读取 `user_growth.last_record_date` 并据此判定今日已记账，SHALL 不使用创建提醒时或更早时刻的记账状态（文案随「发送那一刻是否已记账」变化）。
8. IF 触发时刻读取 `user_growth.last_record_date` 失败或返回不可解析的值 THEN THE 自定义提醒系统 SHALL 判定今日已记账为假、选用文案「今天还没记账哦~」，且 SHALL 不写入 `user_growth` 表。

### 需求 5：订阅授权与剩余订阅次数

**用户故事：** 作为用户，我知道微信要我每次点「允许」才能收到提醒，我希望应用能告诉我还能收到几次、什么时候该再授权。

#### 验收标准

1. THE 自定义提醒系统 SHALL 提供已认证用户上报订阅授权的接口，接收本次经 `wx.requestSubscribeMessage` 对提醒模板
   点击「允许」的次数 `grantedCount`（整数，取值范围 1 到 5）。
2. WHEN 已认证用户上报订阅授权 AND `grantedCount` 落在 1 到 5 之间 THEN THE 自定义提醒系统 SHALL 把该用户的
   剩余订阅次数增加 `grantedCount`，并 SHALL 返回增加后的剩余订阅次数。
3. THE 自定义提醒系统 SHALL 使剩余订阅次数落在 0 到 50 的闭区间内；WHEN 上报授权将使剩余订阅次数超过 50
   THEN THE 自定义提醒系统 SHALL 把剩余订阅次数置为 50（累积上限，避免额度无限增长）。
4. IF `grantedCount` 缺失、无法解析为整数、小于 1 或大于 5 THEN THE 自定义提醒系统 SHALL 拒绝上报并返回错误码
   `REMINDER_GRANT_INVALID`，且 SHALL 保持该用户的剩余订阅次数不变。
5. WHEN 一条提醒成功发送（发送结果为 `SENT`）THEN THE 自定义提醒系统 SHALL 把该用户的剩余订阅次数减 1；IF 减 1 前该用户的剩余订阅次数已为 0 THEN THE 自定义提醒系统 SHALL 保持剩余订阅次数为 0（不产生负值）。
6. IF 微信 `subscribeMessage.send` 返回表示用户拒收或额度不足的错误码（如 `43101`）THEN THE 自定义提醒系统
   SHALL 把该用户的剩余订阅次数置为 0（微信侧已无额度，本地计数须归零对齐）。
7. WHEN 已认证用户查询自身提醒设置 THEN THE 自定义提醒系统 SHALL 在响应中返回该用户当前的剩余订阅次数；IF 该用户尚无订阅授权记录 THEN THE 自定义提醒系统 SHALL 返回剩余订阅次数为 0。
8. THE 自定义提醒系统 SHALL 以 `user_id` 作为剩余订阅次数的唯一统计维度，SHALL 使同一用户在全部设备与会话共享同一计数，且 SHALL 对该计数的每次增加与减少执行原子更新，使并发的上报授权与发送扣减不产生丢失更新（最终计数等于所有增减操作的净和）。
9. WHILE 某用户的剩余订阅次数为 0，THE 自定义提醒系统 SHALL 不对该用户调用微信 `subscribeMessage.send` 发送提醒，并 SHALL 在该用户查询提醒设置时以剩余订阅次数为 0 指示其需重新授权。

### 需求 6：提醒发送、幂等与故障隔离

**用户故事：** 作为开发者，我要提醒该发时发得出去、发过就不再重发，而且它彻底挂了也不能连累记账和登录。

#### 验收标准

1. WHEN 生成某提醒在触发时刻的发送尝试 AND 该用户剩余订阅次数大于 0 AND 该用户 `wx_openid` 非空
   THEN THE 自定义提醒系统 SHALL 经 `WeChatAccessTokenProvider` 取凭证、调用微信 `subscribeMessage.send`
   向该 `wx_openid` 下发含需求 4 所选文案的订阅消息；WHEN 微信 `subscribeMessage.send` 返回零错误码（视为发送成功）THEN THE 自定义提醒系统 SHALL 以发送结果 `SENT` 写入发送记录，并 SHALL 依需求 5 第 5 条把该用户剩余订阅次数减 1（减 1 后不小于 0）。
2. IF 生成发送尝试时该用户剩余订阅次数为 0 THEN THE 自定义提醒系统 SHALL 不调用微信发送接口、
   SHALL 以发送结果 `SKIPPED_NO_QUOTA` 写入发送记录，且 SHALL 不改变剩余订阅次数。
3. IF 生成发送尝试时该用户 `wx_openid` 为空值 THEN THE 自定义提醒系统 SHALL 不调用微信发送接口、
   SHALL 以发送结果 `SKIPPED_NO_QUOTA` 写入发送记录、SHALL 不消耗剩余订阅次数，且 SHALL 不返回错误
   （无 openid 无法投递，视同不可发送）。
4. IF 微信 `subscribeMessage.send` 返回非零错误码或调用抛出异常 THEN THE 自定义提醒系统 SHALL 以发送结果
   `FAILED` 写入发送记录并记录该微信错误码、SHALL 记录一条含用户 id 且不含金额、邮箱与令牌的告警日志，
   SHALL 不因该失败扣减该用户剩余订阅次数（保持发送前取值不变、任何情形下不小于 0），且 SHALL 不向记账、登录、注销与结算路径传播该异常。
5. THE 自定义提醒系统 SHALL 使某提醒某触发日的发送记录至多一条（以需求 9 的唯一键保证）；
   WHEN 该提醒该触发日已存在发送记录 THEN THE 自定义提醒系统 SHALL 不再为其生成第二次发送尝试。
6. IF 发送记录写入过程中因唯一键冲突失败（并发触发）THEN THE 自定义提醒系统 SHALL 放弃本次发送尝试、
   SHALL 不重复调用微信发送接口，且 SHALL 不返回错误（幂等）。
7. THE 自定义提醒系统 SHALL 在调度任务内隔离单条提醒的故障：IF 某条提醒的发送尝试抛出异常 THEN THE
   自定义提醒系统 SHALL 捕获该异常、记录告警日志并继续处理本轮其余提醒，且 SHALL 不中断整个调度任务。
8. THE 自定义提醒系统 SHALL 不修改 `transactions`、`budgets`、`ledgers`、`growth_events`、`user_growth`、
   `streak_segments` 六表的任何行。

### 需求 7：查询、更新、开关与删除提醒

**用户故事：** 作为用户，我想随时改提醒时间、临时关掉某个提醒、或者把不用的删掉，只能动我自己的。

#### 验收标准

1. WHEN 已认证用户请求自身提醒列表 THEN THE 自定义提醒系统 SHALL 仅返回 `user_id` 等于当前会话用户的提醒，按 `created_at` 升序排列，每项 SHALL 包含 `reminder_id`、`frequency`、`remindTime` 与 `enabled` 四项，并 SHALL 在响应中一并返回该用户的剩余订阅次数（取值为大于等于 0 的整数）。
2. WHEN 已认证用户请求自身提醒列表 AND 该用户当前无任何提醒 THEN THE 自定义提醒系统 SHALL 返回一个空列表（元素数量为 0），并 SHALL 仍返回该用户的剩余订阅次数（取值为大于等于 0 的整数）。
3. WHEN 已认证用户更新自身某提醒的 `frequency`、`remindTime` 或 `enabled` AND 更新后取值通过需求 1 第 3、4 条校验 THEN THE 自定义提醒系统 SHALL 仅保存本次提交的字段、保持未提交字段的原值不变、把 `updated_at` 置为服务端当前时刻，并返回更新后的提醒（包含 `reminder_id`、`frequency`、`remindTime` 与 `enabled` 四项）。
4. IF 已认证用户更新自身某提醒 AND 更新后取值未通过需求 1 第 3、4 条校验 THEN THE 自定义提醒系统 SHALL 拒绝本次更新、保持目标提醒的全部列不变，并返回指示该校验失败字段的错误响应（`REMINDER_FREQUENCY_INVALID` 或 `REMINDER_TIME_INVALID`）。
5. IF 更新或删除的目标 `reminder_id` 在 `custom_reminders` 表中不存在，或其 `user_id` 不等于当前会话用户 THEN THE 自定义提醒系统 SHALL 对两种情形返回完全相同的 `NOT_FOUND` 响应，且响应中 SHALL 不包含任何提醒字段值（不泄漏他人提醒是否存在）。
6. WHEN 已认证用户删除自身某提醒 THEN THE 自定义提醒系统 SHALL 删除该 `custom_reminders` 行，且 SHALL 不删除该提醒已产生的历史发送记录（发送记录是已发生事实）。
7. WHEN 已认证用户把某提醒的 `enabled` 置为假再置为真、其余字段不变 THEN THE 自定义提醒系统 SHALL 使该提醒恢复参与触发，且 SHALL 不因期间未触发而补发停用期内错过的提醒。
8. IF 更新提交的 `frequency` 与 `remindTime` 会与当前用户的另一条已存在提醒两项均相同 THEN THE 自定义提醒系统 SHALL 拒绝更新并返回错误码 `REMINDER_DUPLICATE`，且 SHALL 保持目标提醒的全部列不变。

### 需求 8：权限边界

**用户故事：** 作为用户，我希望我的提醒配置只有我能看能改，别人改参数也看不到、动不了我的提醒。

#### 验收标准

1. THE 自定义提醒系统 SHALL 要求创建、查询、更新、删除、上报订阅授权全部接口携带**有效令牌**。
2. IF 请求未携带令牌、令牌签名校验失败、令牌已过期、或其标识的用户在 `users` 表中不存在 THEN THE 自定义提醒系统
   SHALL 返回错误码 `UNAUTHENTICATED`（优先于任何字段校验），且 SHALL 保持 `custom_reminders` 表与剩余订阅次数不变，
   响应中 SHALL 不包含任何提醒字段值。
3. THE 自定义提醒系统 SHALL 把全部接口的数据范围硬性限定为当前会话用户本人的提醒与订阅额度，
   SHALL 以有效令牌所标识的用户 id 作为唯一的数据归属依据，SHALL 忽略请求中任何用于指定目标用户身份的
   查询参数、路径参数、请求体字段与自定义请求头，且 SHALL 不因请求携带此类字段而返回错误码。
4. WHEN 用户 A 以自身有效令牌请求提醒接口 THEN THE 自定义提醒系统 SHALL 只返回或只修改用户 A 的数据，
   且 SHALL 不在响应中返回任何其它用户的提醒、订阅次数与发送记录。
5. THE 自定义提醒系统 SHALL 使提醒接口与会话账本无关，SHALL 不要求请求携带 `X-Ledger-Id` 头、
   SHALL 不因该头缺失或取值不可访问而拒绝请求。
6. THE 自定义提醒系统 SHALL 以统一错误体格式 `{code, message, field}` 返回错误，其字段集 SHALL 恰好为这 3 项；
   THE 自定义提醒系统 SHALL 使 `message` 为长度不超过 100 个字符的中文文案，且不含用户 id、邮箱与令牌三类内容。
7. WHEN 错误与某个具体输入字段无关（如 `UNAUTHENTICATED`、`NOT_FOUND`）THEN THE 自定义提醒系统 SHALL 使错误体的 `field` 取空值，且 SHALL 不因该情形省略 `code` 与 `message` 两项。
8. WHEN 已认证用户以查询、更新或删除接口访问一个 `user_id` 不等于当前会话用户的 `reminder_id` THEN THE 自定义提醒系统 SHALL 返回与该 `reminder_id` 不存在时完全相同的 `NOT_FOUND` 响应（相同 `code` 与相同 `message`），且 SHALL 保持 `custom_reminders` 表与剩余订阅次数不变。

### 需求 9：数据模型与迁移

**用户故事：** 作为开发者，我需要提醒配置、订阅额度与发送记录有清晰的表结构和迁移脚本，能与既有 Flyway 体系一致演进。

#### 验收标准

1. THE 迁移脚本 SHALL 命名为 `V35__custom_reminder.sql`，SHALL 置于 `src/main/resources/db/migration`，
   且 SHALL 不修改任何已存在的迁移脚本文件。
2. THE 迁移脚本 SHALL 新建 `custom_reminders` 表，该表 SHALL 恰好包含以下 7 列：
   `reminder_id`（BIGINT NOT NULL AUTO_INCREMENT，主键）、`user_id`（BIGINT NOT NULL）、
   `frequency`（VARCHAR(16) NOT NULL）、`remind_time`（TIME NOT NULL）、`enabled`（TINYINT(1) NOT NULL，缺省 1）、
   `created_at`（DATETIME NOT NULL）、`updated_at`（DATETIME NOT NULL）。
3. THE 迁移脚本 SHALL 为 `custom_reminders` 建立名为 `uk_custom_reminders_user_freq_time` 的具名唯一约束，
   其列序恰为 `user_id`、`frequency`、`remind_time`（同一用户同一频率同一时间至多一条，支撑需求 1.5 的去重）。
4. THE 迁移脚本 SHALL 为 `custom_reminders` 建立名为 `idx_custom_reminders_enabled_time` 的非唯一复合索引，
   其列序恰为 `enabled`、`remind_time`（支撑调度器按启用状态与时间扫描到点提醒）。
5. THE 迁移脚本 SHALL 建立名为 `ck_custom_reminders_frequency` 的具名 CHECK 约束，
   把 `frequency` 限制为区分大小写的 `DAILY`/`WEEKDAY`/`WEEKEND`。
6. THE 迁移脚本 SHALL 新建 `reminder_quota` 表，该表 SHALL 恰好包含以下 4 列：
   `user_id`（BIGINT NOT NULL，主键）、`remaining`（INT NOT NULL，缺省 0）、
   `created_at`（DATETIME NOT NULL）、`updated_at`（DATETIME NOT NULL）；
   SHALL 建立名为 `ck_reminder_quota_remaining` 的具名 CHECK 约束，其表达式为 `remaining >= 0`。
7. THE 迁移脚本 SHALL 新建 `reminder_send_logs` 表，该表 SHALL 恰好包含以下 8 列：
   `id`（BIGINT NOT NULL AUTO_INCREMENT，主键）、`reminder_id`（BIGINT NOT NULL）、`user_id`（BIGINT NOT NULL）、
   `trigger_date`（DATE NOT NULL）、`result`（VARCHAR(24) NOT NULL）、`message_variant`（VARCHAR(16) NOT NULL）、
   `wx_errcode`（INT NULL）、`created_at`（DATETIME NOT NULL）。
8. THE 迁移脚本 SHALL 为 `reminder_send_logs` 建立名为 `uk_reminder_send_logs_reminder_date` 的具名唯一约束，
   其列序恰为 `reminder_id`、`trigger_date`（同一提醒同一触发日至多一条发送记录，构造性保证需求 6.5 的幂等）。
9. THE 迁移脚本 SHALL 使三张新表的存储引擎为 `InnoDB`、字符集为 `utf8mb4`、排序规则为 `utf8mb4_unicode_ci`，
   并 SHALL 为每张表与其每一列写非空的中文注释（与 `V32`、`V33`、`V34` 同一风格）。
10. THE 迁移脚本 SHALL 不建立指向 `users(id)` 的外键（与 `user_growth`、`growth_events`、`streak_segments` 同一取舍：
    注销时由服务层在同一事务内显式删除）。
11. WHEN 某用户完成账号注销 THEN THE 应用 SHALL 在注销的同一事务内删除该用户在 `custom_reminders`、`reminder_quota` 与 `reminder_send_logs` 三张表的全部行、SHALL 使该用户在这三张表的行数均为 0，且 SHALL 不使注销接口的响应字段集、HTTP 状态码与既有错误码发生变化。
12. IF 注销事务内对这三张表任一表的删除失败 THEN THE 应用 SHALL 回滚整个注销事务（不产生部分删除），且 SHALL 经既有错误码返回失败。
13. THE 迁移脚本 SHALL 在 MySQL 与 H2 `MODE=MySQL` 两种执行环境下均无错误执行完成并在 `flyway_schema_history` 中记为成功，且 SHALL 不使用窗口函数、`CONVERT_TZ`、存储过程与触发器四类构造。
14. THE 清库脚本 `deploy/reset-db.sql` SHALL 在 `SET FOREIGN_KEY_CHECKS = 0` 与 `SET FOREIGN_KEY_CHECKS = 1` 之间、
    且在 `TRUNCATE TABLE users` 之前清空 `custom_reminders`、`reminder_quota` 与 `reminder_send_logs` 三张表；
    脚本执行后这三张表行数 SHALL 为 0，表结构与 `flyway_schema_history` SHALL 保留。
15. WHEN 应用以 Hibernate `ddl-auto=validate` 在迁移完成的数据库上启动 THEN THE 应用 SHALL 启动成功
    且 SHALL 不抛出针对这三张表的 schema 校验异常。

### 需求 10：提醒设置页与订阅授权入口

**用户故事：** 作为用户，我希望有一个页面让我加提醒、改时间、开关提醒，并按微信要求点「允许」来接收提醒。

#### 验收标准

1. THE miniapp SHALL 新增页面 `pages/reminder/reminder`，SHALL 在该页展示当前用户的提醒列表、剩余订阅次数、
   新增提醒入口与订阅授权入口，且 SHALL 在「我的」页提供进入该页的入口。
2. WHEN 用户打开提醒设置页 THEN THE miniapp SHALL 请求一次提醒列表，SHALL 在请求返回之前展示占位状态且不展示任何提醒项，
   并 SHALL 在返回之后以真实取值渲染提醒列表与剩余订阅次数。
3. WHEN 用户新增或编辑提醒 THEN THE miniapp SHALL 提供频率三选一（每天/工作日/周末）与时间选择控件（时:分），SHALL 在提交前于本地校验频率已从三项中选定、且时间的小时在 0 到 23、分钟在 0 到 59 之内，并 SHALL 在校验通过后把 `frequency` 与 `remindTime` 提交给创建或更新接口。
4. IF 用户提交新增或编辑时频率未选定或时间不在合法范围内 THEN THE miniapp SHALL 不调用创建或更新接口、SHALL 就地展示校验失败提示，且 SHALL 保持已填写的其余表单内容不变。
5. WHEN 用户在提醒设置页触发订阅授权 THEN THE miniapp SHALL 调用 `wx.requestSubscribeMessage` 请求提醒模板授权，
   并 SHALL 在用户点击「允许」后调用上报订阅授权接口把本次授权次数上报给服务端。
6. IF `wx.requestSubscribeMessage` 返回用户拒绝或调用失败 THEN THE miniapp SHALL 不调用上报订阅授权接口、
   SHALL 展示未授权的提示文案与再次授权的入口，且 SHALL 不使页面进入错误态。
7. WHERE 剩余订阅次数为 0 THE miniapp SHALL 展示引导用户再次授权的文案，
   且 SHALL 明确提示「授权后才能继续收到提醒」（对齐微信一次性订阅的额度耗尽语义）。
8. WHEN 用户切换某提醒的启用开关、修改时间或删除提醒 THEN THE miniapp SHALL 调用对应的更新或删除接口，
   并 SHALL 在接口返回成功后就地更新列表展示。
9. IF 提醒列表、创建、更新、删除或上报授权接口返回错误标识，或请求自发出起 3000 毫秒内无响应 THEN THE miniapp
   SHALL 展示失败提示与重试入口、SHALL 使该请求的自动重试次数为 0，且 SHALL 保持其余已加载内容不变。
10. IF 当前不存在已登录状态 THEN THE miniapp SHALL 不发起任何提醒接口请求、SHALL 不展示任何提醒项，
    且 SHALL 展示登录入口以引导用户登录。
11. THE miniapp SHALL 复用项目既有的日期/时间格式化与请求封装工具展示与获取提醒数据，
    SHALL 使全部时间以 `Asia/Shanghai` 呈现，且 SHALL 不在提醒设置页展示任何金额、账本名称、邮箱与邀请码。

### 需求 11：与既有体系的兼容边界

**用户故事：** 作为开发者，我要确认这次改动是纯增量的：把自定义提醒整块摘掉，其余功能仍原样成立。

#### 验收标准

1. THE 自定义提醒系统 SHALL 在其任意接口调用与调度任务执行的前后，使任何用户的经验值、等级、累计记账天数、连续段长度、历史最长连续天数与最近记账日六项取值逐项相等，且 SHALL 只读取 `user_growth.last_record_date`、不对 `user_growth` 表执行任何插入、更新或删除语句。
2. THE 自定义提醒系统 SHALL 只读取 `users.wx_openid`，SHALL 不对 `users` 表执行任何插入、更新或删除语句，且 SHALL 在其任意接口调用与调度任务执行的前后使 `users` 表各列取值不变。
3. THE 自定义提醒系统 SHALL 复用 `WeChatAccessTokenProvider` 获取 `access_token`，
   SHALL 不新建第二套凭证获取或凭证缓存逻辑，且 SHALL 不自行调用 `cgi-bin/token`。
4. WHEN 从库中删除 `custom_reminders`、`reminder_quota` 与 `reminder_send_logs` 三张表的全部行 THEN THE 记账、预算、登录、注销、邀请、成长、成就与连续记账系统 SHALL 对相同请求在相同数据前置条件下保持其全部接口的响应字段集、字段取值、HTTP 状态码与错误码不变。
5. THE 自定义提醒系统 SHALL 只新增 `REMINDER_FREQUENCY_INVALID`、`REMINDER_TIME_INVALID`、`REMINDER_DUPLICATE`、
   `REMINDER_LIMIT_EXCEEDED`、`REMINDER_GRANT_INVALID` 五个错误码，且 SHALL 复用既有的 `UNAUTHENTICATED` 与 `NOT_FOUND`
   两个错误码、不重命名任何既有错误码。
6. THE 自定义提醒系统 SHALL 使调度任务的故障、微信接口的故障与订阅额度的耗尽均不改变记账、登录、注销与结算路径接口的响应字段集、字段取值、HTTP 状态码与既有错误码，且 SHALL 不向上述路径传播任何异常。
7. THE 自定义提醒系统 SHALL 使数据库变更为纯增量：除 `custom_reminders`、`reminder_quota` 与 `reminder_send_logs` 三张新表外，SHALL 不对任何既有表执行 `ALTER`、`DROP` 或行写入语句，且对 `user_growth` 与 `users` 两表仅执行只读访问。
