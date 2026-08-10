# Requirements Document

## Introduction

「有余」当前所有读写都实时依赖网络：断网或弱网时，首页/流水打不开、记账保存失败。但记账最高频的场景
（地铁、电梯、地下停车场、电梯间、地下超市收银台）恰恰常常没有稳定信号。本次新增 **离线缓存 + 弱网记账
（Offline_Sync_System）**：让小程序在弱网/断网下仍能**打开看数据**（读缓存回落）、**照常记一笔**（写入本地
队列 + 乐观上屏），并在网络恢复后**自动、幂等地同步**，全程给用户明确可信的状态反馈。

一句话边界：**离线层只在网络不可用时“兜底”，不改变在线时的任何既有行为**——在线时读写与今天完全一致；
离线时读走本地缓存快照、写进本地队列并乐观展示；联网后按序重放队列，用服务端返回替换本地临时记录。
落库仍复用既有交易创建链路，绝不新建第二套记账/余额逻辑。

### 范围与关键决策（构成验收骨架，若与产品预期不符请评审时指出）

以下九项是本 spec 的决策骨架，验收标准全部围绕它们展开。

1. **仅小程序端（mp-weixin），第一阶段。** 本 spec 只针对小程序端的 uni 存储与网络 API；H5/App 不在范围内，
   实现须保证不破坏 H5 既有行为（H5 无离线能力时表现与今天一致）。
2. **离线只放开「新增收支」这一最高频、最安全的写操作。** 离线可入队的写操作仅限 `POST /transactions`
   的 `expense`/`income` 创建。**编辑/删除服务端已有记录、转账、余额校准、AA、借贷等一律不入队**，
   离线触发时明确提示「该操作需要联网」。此约束是为了避免离线冲突的复杂度，是关键决策点。
3. **读缓存「先网络后缓存」，缓存仅作离线兜底展示，不作事实源。** 在线时 GET 正常走网络并**更新缓存**；
   仅当网络失败（`NETWORK_ERROR`）时回落到最近一次成功的缓存快照，并在界面打「缓存」标记。缓存不参与任何
   金额计算的最终口径——最终口径永远以服务端返回为准。
4. **写队列（Outbox）+ 乐观上屏 + 客户端幂等键。** 离线（或写请求失败）时，创建操作写入本地队列 `youyu_outbox`，
   同时以临时 id（`local_<uuid>`）乐观插入列表并打「待同步」标记。每条队列项携带客户端生成的 `clientToken`。
5. **幂等由后端唯一约束兜底（本 spec 唯一的后端改动）。** `POST /transactions` 接受可选 `clientToken`；
   后端对「同一用户 + clientToken」加唯一约束：若已存在则直接返回既有记录、**不重复落账**。这是保证断线重连、
   重复重放不产生重复流水的关键。为此新增 Flyway 增量迁移（接续当前最大版本，本 spec 取 **`V41`**）。
6. **同步触发时机：网络恢复、App 前台、手动。** ① `uni.onNetworkStatusChange` 变为在线；② App `onShow`
   / 首页加载；③ 用户在同步中心手动触发。队列按入队顺序**串行**重放；单项失败保留并标红，不阻塞后续项。
7. **失败/冲突可见可控。** 同步失败的项（如金额校验冲突、账户不存在）单独标「同步失败」并展示原因，
   提供「重试」；不影响其他项。用户可在同步中心逐条处理。
8. **同步中心（我的页）给重度用户掌控感。** 展示待同步数量、需处理数量、上次同步时间；提供「立即同步」、
   「仅 Wi-Fi 下同步」开关、离线缓存清理与占用查看。
9. **纯增量、可整块摘除、不改既有在线契约。** 离线层以中间件形式包裹既有 `http`；把离线层整块摘掉后，
   在线读写行为与今天完全一致。金额一律 `BigDecimal`/2 位小数（HALF_UP），临时 id 与 clientToken 仅前端与
   幂等去重使用，不泄漏进业务语义。

### 与其它 spec / 既有代码的关系

- **复用既有请求层**：`miniapp/src/utils/request.js` 的 `fail()` 已统一抛 `NETWORK_ERROR`，这是离线降级的唯一入口；
  离线中间件包裹 `http` 的 get/post，不改既有调用方签名。
- **复用既有交易创建链路**：确认/重放入账仍走 `POST /transactions`（账户加锁、单事务原子），不新建余额逻辑。
- **复用本地存储惯例**：沿用 uni 同步存储（参考 `utils/invite.js`、`stores/theme.js`、`stores/auth.js`），
  存储读写异常一律吞掉，绝不因缓存/队列故障抛出阻断主路径。
- **迁移版本号**：`db/migration` 当前最大版本为 `V38__recurring_transactions.sql`，本 spec 取 **`V41`**。
- 与 recurring-transactions、record-suggestion、aa-ledger 等无功能耦合；离线层对它们只表现为「离线时提示需联网」。

## Glossary

- **离线同步系统（Offline_Sync_System）**：本 spec 涉及的网络状态感知、读缓存回落、写队列、乐观上屏、
  自动/手动同步、失败处理与同步中心的整体。
- **网络状态（Network_State）**：全局网络态，取值 `ONLINE`（在线）、`OFFLINE`（无网络）之一；
  弱网在本 spec 归入「在线但请求可能失败」，失败即触发降级，不单列 `WEAK` 状态机分支。
- **读缓存快照（Cache_Snapshot）**：某 GET 接口最近一次成功响应的本地副本，键为「账本 + 接口路径」，含时间戳。
- **写队列 / 收件箱（Outbox）**：本地存储的待同步写操作有序列表，键 `youyu_outbox`。
- **队列项（Outbox_Item）**：一条待同步写操作，含 `clientToken`、请求 payload、ledgerId、临时 id
  （`local_<uuid>`）、状态、重试次数、失败原因、入队时间。
- **队列项状态（Outbox_Status）**：取值 `PENDING`（待同步）、`SYNCING`（同步中）、`FAILED`（同步失败）之一；
  成功后该项从队列移除（不保留 `SYNCED` 常驻态）。
- **客户端幂等键（Client_Token）**：前端为每条写操作生成的唯一字符串（如 uuid），随请求发往后端用于去重。
- **临时 id（Local_Id）**：离线记录乐观上屏时使用的本地占位 id，形如 `local_<uuid>`，同步成功后由服务端真实 id 替换。
- **乐观上屏（Optimistic_Insert）**：离线写入后立即把记录插入本地列表展示，不等待网络。
- **同步中心（Sync_Center）**：「我的」页内的离线同步管理界面。

## Requirements

### Requirement 1 — 网络状态感知与全局提示

**User Story:** 作为用户，我希望在断网时能立刻感知到「现在离线」，这样我心里有数、知道数据可能不是最新。

#### Acceptance Criteria

1. WHEN 小程序启动 THE Offline_Sync_System SHALL 通过 `uni.getNetworkType` 读取当前网络类型并初始化 Network_State（`none` 视为 `OFFLINE`，其余视为 `ONLINE`）。
2. THE Offline_Sync_System SHALL 通过 `uni.onNetworkStatusChange` 订阅网络变化，并在变化时更新全局 Network_State。
3. WHEN Network_State 为 `OFFLINE` THE Offline_Sync_System SHALL 在页面顶部展示离线横幅，文案表明「当前离线 · 记账将在联网后自动同步」。
4. WHEN Network_State 由 `OFFLINE` 变为 `ONLINE` THE Offline_Sync_System SHALL 移除离线横幅并触发一次同步（见 Requirement 5）。
5. WHERE 网络订阅或查询 API 调用异常 THE Offline_Sync_System SHALL 吞掉异常并默认按 `ONLINE` 处理，绝不因网络感知失败而阻断任何页面或记账。

### Requirement 2 — 读缓存回落（先网络后缓存）

**User Story:** 作为用户，我希望断网时首页和流水仍能打开看到上次的数据，而不是白屏或报错。

#### Acceptance Criteria

1. WHEN 一个被标记为「可缓存」的 GET 请求在线成功返回 THE Offline_Sync_System SHALL 以「账本 + 接口路径」为键写入 Cache_Snapshot（含响应体与时间戳）。
2. WHEN 一个可缓存 GET 请求因 `NETWORK_ERROR` 失败且存在对应 Cache_Snapshot THE Offline_Sync_System SHALL 返回该快照数据并标记该结果来自缓存。
3. WHEN 一个可缓存 GET 请求因 `NETWORK_ERROR` 失败且不存在对应 Cache_Snapshot THE Offline_Sync_System SHALL 按既有失败路径抛出 `NETWORK_ERROR`（调用方保持既有兜底）。
4. WHERE 结果来自缓存 THE Offline_Sync_System SHALL 使对应页面能展示「缓存」标记与缓存时间。
5. THE Offline_Sync_System SHALL 只缓存明确列入白名单的读接口（至少：按月流水列表、首页今日汇总、分类列表），不缓存鉴权、导出、二维码等敏感或大体积接口。
6. WHEN 用户切换当前账本 THE Offline_Sync_System SHALL 按账本维度隔离缓存，不同账本的快照互不覆盖、互不读取。
7. WHERE 缓存读写发生异常 THE Offline_Sync_System SHALL 吞掉异常并退回网络结果或既有失败路径，绝不抛出。

### Requirement 3 — 弱网/离线记账（写队列 + 乐观上屏）

**User Story:** 作为用户，我希望没信号时也能把一笔账记下来，回头联网它能自动补上，不用我重记。

#### Acceptance Criteria

1. WHEN 用户在 `OFFLINE` 下提交一笔 `expense`/`income` 记账 THE Offline_Sync_System SHALL 生成 `clientToken` 与 Local_Id，把该操作作为 Outbox_Item（状态 `PENDING`）追加进 Outbox，并立即以成功语义返回给调用方（携带 Local_Id 的乐观记录）。
2. WHEN 用户在 `ONLINE` 下提交记账但请求以 `NETWORK_ERROR` 失败 THE Offline_Sync_System SHALL 同样将其转入 Outbox（`PENDING`）并乐观返回，不向用户报「保存失败」。
3. WHEN 一笔记账被乐观入队 THE Offline_Sync_System SHALL 使其立即出现在流水/首页列表顶部并带「待同步」标记。
4. WHEN 用户在 `OFFLINE` 下触发不在放开范围内的写操作（编辑/删除已有记录、转账、余额校准、AA、借贷等）THE Offline_Sync_System SHALL 阻止该操作并提示「该操作需要联网」，不入队、不乐观展示。
5. THE Offline_Sync_System SHALL 保证同一 Outbox_Item 的 `clientToken` 在其生命周期内稳定不变（重试不重新生成）。
6. WHERE Outbox 写入发生存储异常 THE Offline_Sync_System SHALL 向用户提示保存失败并不产生半条脏数据（要么完整入队，要么明确失败）。
7. THE Outbox SHALL 携带足够重放所需的全部字段（payload、ledgerId、type、金额等），不依赖入队后仍在内存中的临时状态。

### Requirement 4 — 待同步记录的展示与区分

**User Story:** 作为用户，我希望一眼看出哪些记录已经存好、哪些还在等着同步。

#### Acceptance Criteria

1. WHEN 列表中存在 Local_Id 记录 THE Offline_Sync_System SHALL 为其渲染「待同步」状态标识（区别于已同步记录）。
2. WHEN 某 Outbox_Item 处于 `SYNCING` THE Offline_Sync_System SHALL 将对应记录标识切换为「同步中」。
3. WHEN 某 Outbox_Item 处于 `FAILED` THE Offline_Sync_System SHALL 将对应记录标识切换为「同步失败」并展示失败原因与「重试」入口。
4. WHEN 某 Outbox_Item 同步成功 THE Offline_Sync_System SHALL 以服务端返回的真实记录替换该 Local_Id 记录（按 `clientToken` 匹配），并移除「待同步」标识。
5. WHERE 存在待同步项 THE Offline_Sync_System SHALL 在涉及金额汇总处标注口径以服务端为准（如「含未同步 N 笔」），不把未同步项当作最终账实。

### Requirement 5 — 自动/手动同步与串行重放

**User Story:** 作为用户，我希望联网后不用我操心，App 自己把攒下的账补上去。

#### Acceptance Criteria

1. WHEN Network_State 变为 `ONLINE` 且 Outbox 非空 THE Offline_Sync_System SHALL 自动启动一次同步。
2. WHEN App 进入前台（`onShow`）或首页加载且 Outbox 非空且在线 THE Offline_Sync_System SHALL 启动一次同步。
3. WHEN 用户在同步中心点击「立即同步」THE Offline_Sync_System SHALL 立即启动一次同步。
4. THE Offline_Sync_System SHALL 按入队顺序**串行**重放 Outbox_Item：逐项置 `SYNCING`、调用 `POST /transactions`（携带 `clientToken`）、成功即出队、失败置 `FAILED` 并继续下一项。
5. WHILE 一次同步正在进行 THE Offline_Sync_System SHALL 防止并发重入（同一时刻至多一个同步循环）。
6. WHEN 同步过程中网络再次中断 THE Offline_Sync_System SHALL 停止本轮重放，保留未处理项为 `PENDING`，等待下次触发。
7. WHILE 正在同步 THE Offline_Sync_System SHALL 通过顶部横幅展示进度（如「正在同步 N 笔…」）。
8. WHEN 一轮同步结束 THE Offline_Sync_System SHALL 展示结果（全部成功→短暂「已同步 N 笔」并淡出；有失败→提示需处理数量）。

### Requirement 6 — 后端幂等去重

**User Story:** 作为用户，我最怕的是弱网重试把一笔账记成两笔。系统必须保证同一笔只落一次。

#### Acceptance Criteria

1. THE 交易创建接口 `POST /transactions` SHALL 接受可选字段 `clientToken`（字符串，长度受限，为空时行为与今天完全一致）。
2. WHEN 携带 `clientToken` 的创建请求到达且该用户尚无同 `clientToken` 记录 THE 系统 SHALL 正常创建交易并持久化其 `clientToken`。
3. WHEN 携带 `clientToken` 的创建请求到达且该用户已存在同 `clientToken` 记录 THE 系统 SHALL 不再创建新交易，直接返回既有记录（幂等），且不二次改动账户余额。
4. THE 系统 SHALL 以数据库层「用户 + clientToken」唯一约束兜底，保证并发重复提交也只落一笔。
5. WHERE 迁移新增字段与约束 THE 系统 SHALL 采用纯增量 Flyway 迁移（`V41`），对既有历史交易（`clientToken` 为 NULL）不产生约束冲突。
6. THE `clientToken` 幂等语义 SHALL 只作用于交易创建；不改动交易的更新/删除/查询契约与字段集合。

### Requirement 7 — 失败与冲突处理

**User Story:** 作为用户，个别账同步不上去时，我希望知道为什么、并能自己重试或删掉，而不是整批卡住。

#### Acceptance Criteria

1. WHEN 重放某 Outbox_Item 返回业务错误（非网络错误，如金额/账户校验失败）THE Offline_Sync_System SHALL 将该项置 `FAILED`、记录后端返回的 `message` 作为失败原因，并继续处理后续项。
2. WHEN 用户对 `FAILED` 项点击「重试」THE Offline_Sync_System SHALL 将其置回 `PENDING` 并在下一轮同步（或立即）重新重放（复用原 `clientToken`）。
3. WHEN 用户对 `FAILED` 项选择「删除」THE Offline_Sync_System SHALL 从 Outbox 移除该项并从列表移除对应 Local_Id 记录。
4. WHEN 重放某项遭遇 `NETWORK_ERROR` THE Offline_Sync_System SHALL 不将其判为 `FAILED`（保持 `PENDING`），而是按 Requirement 5.6 停止本轮。
5. THE Offline_Sync_System SHALL 对单项重试设定次数/退避策略，避免对同一必然失败项无限自动重试（自动重试上限后转 `FAILED` 待人工处理）。

### Requirement 8 — 同步中心

**User Story:** 作为重度用户，我希望有一个地方能看到同步状态、手动触发、管理缓存。

#### Acceptance Criteria

1. THE Sync_Center SHALL 展示当前待同步数量、需处理（`FAILED`）数量、上次成功同步时间。
2. THE Sync_Center SHALL 提供「立即同步」操作（在线时触发一次全量重放；离线时提示当前离线）。
3. THE Sync_Center SHALL 列出所有 `FAILED` 项并对每项提供「重试」「删除」。
4. THE Sync_Center SHALL 提供「仅 Wi-Fi 下同步」开关：开启后，蜂窝网络下自动同步暂缓（仍可手动触发），Wi-Fi 下恢复自动同步。
5. THE Sync_Center SHALL 展示离线缓存占用概况并提供「清理缓存」操作；清理仅删除 Cache_Snapshot，**不触碰** Outbox 中未同步的记账。
6. WHERE 展示的任何计数/时间读取存储异常 THE Sync_Center SHALL 以安全默认值（0 / 「暂无」）呈现，不崩溃。

### Requirement 9 — 非侵入、可摘除与在线行为不变

**User Story:** 作为维护者，我希望离线能力是一层可插拔的兜底，不改变在线时的既有行为，出问题能整块摘掉。

#### Acceptance Criteria

1. WHEN Network_State 为 `ONLINE` 且请求成功 THE Offline_Sync_System SHALL 使读写行为与未引入离线层时完全一致（仅额外写入读缓存、额外携带 `clientToken`）。
2. THE Offline_Sync_System SHALL 以中间件/包装形式封装既有 `http`，不修改各 `api/*.js` 调用方的函数签名。
3. WHERE 离线层任一环节（缓存、队列、网络感知）发生未预期异常 THE Offline_Sync_System SHALL 退回既有在线路径或既有失败提示，绝不使主流程崩溃。
4. THE 后端改动 SHALL 仅限交易创建接口的可选 `clientToken` 及其去重，不影响其它任何接口契约与错误码集合（新增字段不算破坏性变更）。
5. THE Offline_Sync_System SHALL 只在 mp-weixin 生效；H5 构建下无离线缓存/队列时，行为与今天一致（不得因缺失能力报错）。
6. THE 前端与后端全量测试与构建（`npm test`、`build:h5`、`build:mp-weixin`、`./mvnw test`）SHALL 在引入本特性后全部通过。
