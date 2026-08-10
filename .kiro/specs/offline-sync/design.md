# Design Document

## Overview

离线缓存 + 弱网记账（Offline_Sync_System）是一层**可插拔的兜底中间件**：在线时对既有读写行为透明（仅额外写读缓存、
额外携带幂等键），离线/弱网时接管「读回落缓存、写入队乐观上屏」，联网后串行、幂等地重放队列。第一阶段仅小程序端。

本设计遵循 requirements 的九项决策骨架，核心结构与原型 `design/offline-sync-prototype.html` 一致：

```
             ┌─────────────────────────── 前端（miniapp / mp-weixin）────────────────────────────┐
  页面/组件 → api/*.js → http(offline 包装)                                        stores/net.js（网络态）
                              │                                                          ▲
                 ┌────────────┼─────────────┐                                            │ onNetworkStatusChange
                 ▼            ▼              ▼                                            │
           读缓存 offlineCache   写队列 outbox   同步引擎 syncEngine ───────── POST /transactions(clientToken)
           （GET 白名单）       （POST 收支）    （串行重放/停止/失败）                    │
                                                                                          ▼
             ┌────────────────────────────── 后端（唯一改动）─────────────────────────────┐
             │  TransactionCreateRequest.clientToken（可选）                               │
             │  TransactionService.create：clientToken 命中→返回既有、不重复落账            │
             │  transactions.client_token 列 + (created_by, client_token) 唯一约束（V41）      │
             └────────────────────────────────────────────────────────────────────────────┘
```

### 关键设计原则

1. **在线路径零行为改变**：在线成功时，读多写一份缓存、写多带一个 `clientToken`，其余与今天逐字节一致。
2. **故障吞噬**：缓存/队列/网络感知任一环节异常，一律退回既有在线路径或既有失败提示，绝不抛出中断主流程
   （与 `utils/invite.js` 的存储容错哲学一致）。
3. **纯函数内核 + 薄 IO 壳**：队列、缓存、同步引擎、幂等键、乐观记录构造均抽成不依赖页面的纯模块，
   仅通过注入的存储/网络抽象与 uni 交互，可在 node 下用 vitest 直接测（mock 全局 `uni`）。
4. **落库唯一真源仍是后端**：缓存与乐观记录只服务展示；金额最终口径永远以服务端返回为准。

## Architecture

### 拦截点：包装既有 `http`

`miniapp/src/utils/request.js` 已导出纯 `request()` 与 `http`。各 `api/*.js` 均 `import { http }`。为不改调用方签名，
新增 `utils/offline/offlineHttp.js` 对 `http.get` / `http.post` 做**装饰**，并在 `request.js` 中把导出的 `http`
替换为装饰后的版本（或在 `http` 定义处直接接入 offline 分支，保留原始 `request` 供内部/同步引擎绕过使用）。

- `http.get(url, opts)`：
  1. 若 `url` 命中读缓存白名单：先走网络；成功→写缓存并返回；失败为 `NETWORK_ERROR` 且有快照→返回快照并附 `__fromCache` 标记；无快照→抛原错误。
  2. 非白名单：直接走原 `request`。
- `http.post(url, data, opts)`：
  1. 仅 `url === '/transactions'` 且 `data.type ∈ {expense, income}` 参与离线：为其补 `clientToken`（若无）。
     - 离线（Network_State=OFFLINE）：直接入队 + 返回乐观记录，不发请求。
     - 在线：正常发请求；若 `NETWORK_ERROR` 失败→转入队 + 返回乐观记录。
  2. 其它 POST：直接走原 `request`（不受影响）。

> 同步引擎重放时使用**未装饰的原始** `request`（绕过“失败又入队”的递归），避免重放失败再次入队造成循环。

### 前端模块划分（均在 `miniapp/src/`）

| 模块 | 文件 | 职责 | 可 vitest 纯测 |
|---|---|---|---|
| 网络态 store | `stores/net.js`（Pinia） | 维护 `online`；订阅 `onNetworkStatusChange`；暴露 `isOnline`、`wifiOnly` 偏好 | 逻辑部分可测 |
| 读缓存 | `utils/offline/cache.js` | 白名单判定、键构造（账本+路径）、put/get/clear/size；容错 | ✅ |
| 写队列 | `utils/offline/outbox.js` | Outbox CRUD：enqueue/list/markSyncing/markFailed/removeByToken/retry；FIFO；容错 | ✅ |
| 幂等/乐观 | `utils/offline/token.js` | `newClientToken()`、`newLocalId()`、`buildOptimisticTx(payload)` | ✅ |
| 同步引擎 | `utils/offline/syncEngine.js` | 串行重放（注入 replayFn + outbox + netState）：SYNCING→成功出队/失败标记/网络错停止；防重入 | ✅（注入依赖） |
| offline http | `utils/offline/offlineHttp.js` | 装饰 http.get/post，编排 cache/outbox/token/net | 集成为主 |
| 同步状态 store | `stores/sync.js`（Pinia） | 暴露待同步数、失败数、上次同步时间、进度；驱动横幅与同步中心 | 逻辑部分可测 |

### UI 接入点（对应原型六屏）

- **全局横幅组件** `components/NetBanner/NetBanner.vue`：读 `net` + `sync` store，渲染离线/同步中/已同步/需处理四态，挂在首页与流水页顶部（tabBar 页各自引入）。
- **首页 / 流水页**：列表渲染时，Local_Id 记录叠加 `pill`（待同步/同步中/失败 + 重试）；缓存回落时展示「缓存于 HH:mm」。
- **记账页**：离线时保存按钮文案与配色切换（「离线保存」深灰），保存后 toast「已离线保存，联网后自动同步」。
- **同步中心** `pages/sync/sync.vue`：从「我的」进入，展示统计、失败列表（重试/删除）、立即同步、仅 Wi-Fi 开关、缓存清理。
- **拦截提示**：放开范围外的写操作在离线时统一走一个 `guardOnlineOnly()` 工具，`uni.showToast('该操作需要联网')`。

## 后端设计（唯一改动）

### 数据模型

`transactions` 表新增一列（复用既有 `externalId` 的“外部唯一键”思路）：

- `client_token VARCHAR(64) NULL`：客户端幂等键；手动在线记账不传时为 NULL，与历史数据一致。
- 唯一约束 `uk_tx_creator_client_token (created_by, client_token)`：MySQL 唯一索引允许多个 NULL，历史 NULL 不冲突。
  - **归属键用 `created_by` 而非 `user_id`**：`transactions.user_id` 是 V9 之后的历史遗留可空列，交易创建路径并不写入它（见 `TransactionService.create` 只 `setCreatedBy`）；`created_by` 才是实际写入的记账人列。用 `user_id` 会让所有新行 user_id 为 NULL，唯一约束形同虚设。

`Transaction` 实体新增 `clientToken` 字段（getter/setter），仅参与创建；不进入更新/删除/查询契约。

### Flyway 迁移 `V41__transaction_client_token.sql`

```sql
ALTER TABLE transactions ADD COLUMN client_token VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_tx_creator_client_token ON transactions (created_by, client_token);
```

> 纯增量、可整块摘除：删除本特性时该列与索引可独立回收，不影响其余逻辑（既有代码不读该列）。

### 接口与服务

- `TransactionCreateRequest` 增加可选 `clientToken`（`@Size(max = 64)`；为空则行为与今天完全一致）。
- `TransactionController.create`：把 `req.clientToken()` 透传给服务层；响应契约字段集合不变（`clientToken` 不必回显，
  前端以 `clientToken`↔`localId` 本地映射匹配即可；如需回显则新增一个非破坏性可选字段）。
- `TransactionService.create` 增加 `clientToken` 参数（归属键取该次创建的 `effectiveCreatedBy = createdByOverride != null ? createdByOverride : userId`）：
  1. 若 `clientToken != null`：`repo.findByCreatedByAndClientToken(effectiveCreatedBy, clientToken)` 命中→**直接返回既有交易**
     （不新建、不改余额、不重复设标签）。
  2. 未命中或为 null：走既有创建链路（账户加锁、单事务原子），落库时写入 `clientToken`。
  3. 并发兜底：即便两请求同时未命中，唯一约束会让第二个 `INSERT` 抛 `DataIntegrityViolationException`；
     服务层捕获后再查一次并返回既有记录（读己所写），对客户端仍表现为幂等成功。
- **归属键用 `created_by`**（`user_id` 是历史遗留可空列，创建路径不写入）。
- `TransactionRepository` 增加 `Optional<Transaction> findByCreatedByAndClientToken(Long createdBy, String clientToken)`。

> 幂等只作用于**创建**。更新/删除/查询/搜索接口与错误码集合零改动。

## 数据结构（前端本地存储）

### Outbox（`youyu_outbox`）

```jsonc
[
  {
    "clientToken": "ct_9f2c…",      // 稳定幂等键，重试不变
    "localId": "local_9f2c…",       // 乐观记录占位 id
    "ledgerId": 12,                  // 目标账本（入队时快照，重放据此路由）
    "payload": { "type": "expense", "amount": "28.00", "accountId": 3,
                 "categoryId": 5, "occurredAt": "2026-08-10T18:20:00", "note": "晚餐" },
    "status": "PENDING",            // PENDING | SYNCING | FAILED
    "retryCount": 0,
    "failReason": null,
    "enqueuedAt": 1754820000000
  }
]
```

### Cache（`youyu_cache_<ledgerId>_<pathKey>`）

```jsonc
{ "at": 1754819520000, "data": { /* 该 GET 的响应体原样 */ } }
```

- `pathKey`：对 `url`（含 query）做稳定归一（如去掉易变参数后取路径+关键 query）。白名单：`/transactions?month=`、
  首页汇总接口、`/categories`。
- 账本维度：`ledgerId` 取当前生效账本（与 `request.js` 里 `X-Ledger-Id` 同源口径），`all` 聚合视图单独成键。

### 偏好与元信息

- `youyu_sync_wifi_only`（bool）、`youyu_sync_last_at`（ts）。均走 uni 同步存储，读写容错。

## Correctness Properties（属性测试目标，每条 ≥100 次迭代）

1. **创建幂等**：对任意合法 payload，用同一 `clientToken` 调用创建 N≥2 次，最终数据库仅一条该交易，账户余额仅变化一次；返回的交易 id 恒相同。
2. **队列 FIFO**：任意入队序列，`list()` 顺序恒等于入队顺序；出队只移除对应 `clientToken` 项，不扰动其余顺序。
3. **串行重放-成功出队**：全部可成功的队列重放后，Outbox 为空，重放调用次数 = 队列长度，顺序与入队一致。
4. **网络错误即停**：重放过程中第 k 项遇 `NETWORK_ERROR`，则第 k 项及其后全部保持 `PENDING`，前 k−1 项已出队；本轮不再调用后续项。
5. **业务错误不阻塞**：第 k 项遇业务错误→置 `FAILED`（记 message），第 k+1…项继续重放；`FAILED` 项 `clientToken` 不变。
6. **token 稳定**：任意「入队→SYNCING→FAILED→retry→再 SYNCING」路径，`clientToken` 与 `localId` 全程不变。
7. **乐观替换无重复无丢失**：任意「离线入队 M 笔 + 已有在线 K 笔」，同步成功后列表恰为 K+M 笔，Local_Id 全部被服务端记录按 `clientToken` 替换，无重复、无残留 Local_Id。
8. **缓存往返一致 + 账本隔离**：任意可缓存响应 put 后 get 得到深相等副本；不同 `ledgerId` 的同路径快照互不覆盖、互不读取。
9. **清缓存不伤队列**：任意 Outbox 与 Cache 状态下执行 `clearCache()`，Outbox 内容逐字节不变。
10. **在线等价性**：Network_State=ONLINE 且请求成功时，经 offlineHttp 的读写结果与直接调用原 `request` 等价（读结果相等且写发出的请求体除多出的 `clientToken` 外一致）。
11. **放开范围守卫**：离线下对「非 expense/income 创建」的写调用一律被拒绝且不写入 Outbox（队列长度不变）。
12. **弱网转离线队列**：在线 POST 收支遇 `NETWORK_ERROR`，结果等价于离线入队（同一 payload 产生一条 `PENDING` 项 + 乐观返回），不向调用方抛错。

## Error Handling

- **存储异常**（getStorage/setStorage 抛错）：cache/outbox 所有读写包 try/catch，读失败返回空/网络结果，写失败对用户提示保存失败且不留半条脏数据（Requirement 3.6）。
- **网络订阅异常**：`net` store 初始化失败默认 `online=true`（Requirement 1.5）。
- **重放业务错误 vs 网络错误**：以错误体是否为 `{code:'NETWORK_ERROR'}` 区分；网络错→停止本轮保 `PENDING`；其它（含后端统一 `{code,message}`）→ `FAILED` 记 `message`。
- **自动重试上限**：`retryCount` 超过阈值（如 3）的项在自动同步中跳过，仅手动「重试」可再触发（Requirement 7.5）。
- **并发同步**：`syncEngine` 用一个内存 `running` 标志防重入（Requirement 5.5）。

## Testing Strategy

- **后端**：
  - 属性测试：Property 1（创建幂等）——jqwik 随机 payload + 重复 token，断言单条落库、余额单次变化、id 恒等；并发用例断言唯一约束兜底。
  - 单元/集成：`TransactionServiceTest`（clientToken 命中/未命中/为 null）、控制器契约测试（带/不带 clientToken 响应字段集合不变、错误码集合不变）、迁移冒烟（历史 NULL 不冲突、多 NULL 允许）。
  - 兼容回归：既有交易创建测试全绿，`clientToken` 缺省路径与今天一致。
- **前端（vitest，mock `uni`）**：
  - `outbox.test.js`、`cache.test.js`、`token.test.js`、`syncEngine.test.js` 覆盖 Property 2–12（注入 storage/replay/net 抽象）。
  - 组件级：`NetBanner` 四态渲染、记账页离线态按钮文案、列表 pill 状态映射。
- **全量门禁**：`./mvnw test`、`cd miniapp && npm test`、`npm run build:h5`、`npm run build:mp-weixin` 全通过（Requirement 9.6）。
- **属性测试迭代**：所有 property 测试固定 ≥100 次迭代（与既有 spec 口径一致）。
