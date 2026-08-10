# Implementation Plan

## Overview

按「后端幂等地基 → 前端纯内核模块 → 中间件编排与状态 → UI 接入 → 全量联调」增量实现，每步可独立编译 / 测试。
后端唯一改动是交易创建的 `clientToken` 幂等（Flyway `V41` 增量列 + `(created_by, client_token)` 唯一约束），
以 jqwik 属性测试 Property 1 门控。前端把队列 `outbox`、读缓存 `cache`、同步引擎 `syncEngine`、幂等/乐观 `token`
抽成不依赖页面的纯模块，用注入的存储/网络/重放抽象在 vitest 下直接测（mock `uni`），覆盖 Property 2–12。
离线层以装饰 `http` 的形式接入，在线成功路径行为零改变；整层可摘除。

## Task Dependency Graph

```
1 后端 clientToken 幂等（V41 + 实体/Repo/Service/Controller）+ PBT(P1)
2 前端纯内核：token / cache / outbox / syncEngine + PBT(P2–P9)（与 1 并行，无依赖）
3 编排与状态：net store / sync store / offlineHttp 装饰 + 接入 request.js + PBT(P10–P12)（依赖 2）
4 UI 接入：NetBanner / 记账页离线态 / 列表 pill+缓存标记 / 同步中心 / 联网守卫（依赖 3）
5 全量联调：后端全测+兼容回归+迁移冒烟 / 前端 vitest+组件 / H5+mp-weixin 构建（依赖 1,3,4）
```

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1"] },
    { "id": 1, "tasks": ["1.2", "2.2", "2.3"] },
    { "id": 2, "tasks": ["1.3", "2.4"] },
    { "id": 3, "tasks": ["1.4", "1.5"] },
    { "id": 4, "tasks": ["3.1", "3.2"] },
    { "id": 5, "tasks": ["3.3"] },
    { "id": 6, "tasks": ["3.4"] },
    { "id": 7, "tasks": ["4.1", "4.2", "4.3"] },
    { "id": 8, "tasks": ["4.4", "4.5"] },
    { "id": 9, "tasks": ["5.1", "5.2"] },
    { "id": 10, "tasks": ["5.3"] }
  ]
}
```

## Tasks

- [x] 1. 后端交易创建幂等（Flyway V41）
  - [x] 1.1 新增 Flyway 迁移 `V41__transaction_client_token.sql`（接续 `V38__recurring_transactions.sql`）
    - `ALTER TABLE transactions ADD COLUMN client_token VARCHAR(64) NULL`
    - `CREATE UNIQUE INDEX uk_tx_user_client_token ON transactions (created_by, client_token)`（MySQL 允许多 NULL，历史数据不冲突）
    - 不修改既有列、不复用既有版本号、不建指向既有表的外键；可整块摘除
    - _Requirements: 5.5, 6.4, 6.5, 9.4_
  - [x] 1.2 `Transaction` 实体新增 `clientToken` 字段与 `TransactionRepository.findByUserIdAndClientToken`
    - 实体加 `@Column(name="client_token", length=64) String clientToken` + getter/setter；不进入更新/删除/查询契约
    - Repository 新增 `Optional<Transaction> findByUserIdAndClientToken(Long userId, String clientToken)`
    - _Requirements: 6.1, 6.2, 6.6_
  - [x] 1.3 `TransactionService.create` 增加 `clientToken` 参数并实现幂等
    - `clientToken != null` 且命中→直接返回既有交易（不新建、不改余额、不重设标签）
    - 未命中/为 null→走既有创建链路（账户加锁 `findForUpdateByIdAndUserId` + 单事务原子），落库写入 `clientToken`
    - 并发兜底：捕获唯一约束 `DataIntegrityViolationException` 后重查并返回既有记录（读己所写）
    - _Requirements: 6.2, 6.3, 6.4_
  - [x] 1.4 `TransactionCreateRequest` 增加可选 `clientToken`（`@Size(max=64)`）并在 `TransactionController.create` 透传
    - 为空时行为与今天逐字节一致；响应字段集合与错误码集合不变
    - _Requirements: 6.1, 9.4_
  - [x] 1.5 编写幂等属性测试与兼容回归（**Property 1**）
    - **Feature: offline-sync, Property 1: 创建幂等**：随机合法 payload + 同一 `clientToken` 调用 N≥2 次，断言仅一条落库、账户余额仅变化一次、返回 id 恒等；并发提交用例断言唯一约束兜底；≥100 次迭代
    - 兼容回归：既有交易创建测试全绿；`clientToken` 缺省路径与今天一致；迁移冒烟（历史 NULL 不冲突、多 NULL 允许）
    - _Requirements: 6.2, 6.3, 6.4, 9.4, 9.6_

- [x] 2. 前端纯内核模块（vitest，mock `uni`）
  - [x] 2.1 `utils/offline/token.js`：幂等键 / 临时 id / 乐观记录构造
    - `newClientToken()`、`newLocalId()`（`local_` 前缀）、`buildOptimisticTx(payload, {clientToken, localId})`（构造带 Local_Id、待同步语义的展示记录）
    - _Requirements: 3.1, 3.5, 4.1_
  - [x] 2.2 `utils/offline/cache.js`：读缓存白名单 / 键 / put·get·clear·size（含容错）+ **Property 8、9**
    - 白名单判定（按月流水、首页汇总、分类列表）；键构造 `youyu_cache_<ledgerId>_<pathKey>`（账本隔离，`all` 独立）；put 附时间戳；get 返回 `{at,data}`；`clearCache()` 仅删快照；`cacheSize()`
    - 存储读写全 try/catch 吞异常
    - **Property 8: 缓存往返一致 + 账本隔离**、**Property 9: 清缓存不伤队列**；各 ≥100 次迭代
    - _Requirements: 2.1, 2.2, 2.5, 2.6, 2.7, 8.5_
  - [x] 2.3 `utils/offline/outbox.js`：写队列 CRUD（FIFO，容错）+ **Property 2**
    - `enqueue/list/markSyncing/markFailed/removeByToken/retry/count/failedCount`；`youyu_outbox` 存储；FIFO 顺序保证；`retry` 复用原 `clientToken` 置回 `PENDING`；存储异常吞掉并明确失败（不留半条脏数据）
    - **Property 2: 队列 FIFO 与按 token 出队不扰动其余**；≥100 次迭代
    - _Requirements: 3.1, 3.5, 3.6, 3.7, 7.2, 7.3_
  - [x] 2.4 `utils/offline/syncEngine.js`：串行重放（注入 replayFn/outbox/netState）+ **Property 3、4、5、6**
    - 逐项 `SYNCING`→成功 `removeByToken`→失败按错误类型分流（网络错停止本轮保 `PENDING`；业务错 `FAILED` 记 message 继续）；`running` 防重入；自动重试上限（`retryCount>3` 跳过）
    - **Property 3: 全成功出队**、**Property 4: 网络错即停**、**Property 5: 业务错不阻塞**、**Property 6: token/localId 全程稳定**；各 ≥100 次迭代
    - _Requirements: 5.4, 5.5, 5.6, 7.1, 7.4, 7.5, 6.6_

- [x] 3. 编排与状态（Pinia + offlineHttp 装饰）
  - [x] 3.1 `stores/net.js`：网络态 store
    - 初始化 `uni.getNetworkType`（`none`→OFFLINE）；订阅 `uni.onNetworkStatusChange` 更新 `online`；异常默认 `online=true`；暴露 `isOnline`、`wifiOnly` 偏好（读 `youyu_sync_wifi_only`）
    - _Requirements: 1.1, 1.2, 1.5, 8.4_
  - [x] 3.2 `stores/sync.js`：同步状态 store
    - 暴露 `pendingCount`、`failedCount`、`lastSyncAt`、`syncing`、`progress`；读元信息容错（安全默认 0/「暂无」）；驱动横幅与同步中心
    - _Requirements: 4.5, 8.1, 8.6_
  - [x] 3.3 `utils/offline/offlineHttp.js`：装饰 http.get/post + 联网守卫 + **Property 10、11、12**
    - `get`：白名单先网络后缓存（成功写缓存；`NETWORK_ERROR` 有快照→返回并附 `__fromCache`；无快照→抛原错）；非白名单直通
    - `post`：仅 `/transactions` 且 `type∈{expense,income}` 参与——离线入队+乐观返回；在线遇 `NETWORK_ERROR` 转入队+乐观返回；其它 POST 直通
    - `guardOnlineOnly()`：离线下拒绝放开范围外写操作（提示「该操作需要联网」，不入队）
    - 同步引擎重放使用**原始** `request`（绕过装饰，避免重放失败再入队）
    - **Property 10: 在线等价性**、**Property 11: 放开范围守卫（离线不入队）**、**Property 12: 弱网 POST 转离线入队等价**；各 ≥100 次迭代
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 3.4, 9.1, 9.2, 9.3, 9.5_
  - [x] 3.4 在 `utils/request.js` 接入装饰后的 `http`，并接线同步触发
    - 导出装饰后 `http`（保留原始 `request` 供 syncEngine 使用）；`onNetworkStatusChange`→ONLINE 且队列非空自动同步；App `onShow`/首页加载触发；`wifiOnly` 下蜂窝暂缓自动同步（仍可手动）
    - 全部 `api/*.js` 调用方签名不变
    - _Requirements: 1.4, 5.1, 5.2, 5.3, 8.4, 9.2_

- [x] 4. UI 接入（对应原型六屏）
  - [x] 4.1 `components/NetBanner/NetBanner.vue`：全局横幅四态
    - 离线 / 同步中（进度）/ 已同步（淡出）/ 需处理（含「查看」）四态；读 net+sync store；挂到首页与流水页顶部
    - _Requirements: 1.3, 1.4, 5.7, 5.8_
  - [x] 4.2 记账页离线态
    - 离线时保存按钮文案/配色切换为「离线保存」，保存后 toast「已离线保存，联网后自动同步」；在线失败转离线保存同样提示（不报「保存失败」）
    - _Requirements: 3.1, 3.2_
  - [x] 4.3 流水/首页列表：待同步 pill 与缓存标记
    - Local_Id 记录渲染 待同步/同步中/失败(+重试) pill；缓存回落展示「缓存于 HH:mm」；金额汇总处标注「含未同步 N 笔」
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 2.4_
  - [x] 4.4 `pages/sync/sync.vue`：同步中心 + 从「我的」进入
    - 展示待同步数/需处理数/上次同步时间；失败列表（重试/删除）；「立即同步」；「我的」页新增入口
    - _Requirements: 8.1, 8.2, 8.3, 7.2, 7.3_
  - [x] 4.5 同步中心：仅 Wi-Fi 开关 + 缓存清理
    - 「仅 Wi-Fi 下同步」开关（写 `youyu_sync_wifi_only`）；离线缓存占用展示 + 「清理缓存」（仅删快照，不动 Outbox）
    - _Requirements: 8.4, 8.5, 8.6_

- [x] 5. 全量联调与门禁
  - [x] 5.1 后端全量测试 + 兼容回归 + 迁移冒烟
    - `./mvnw test` 全绿；确认 `clientToken` 缺省路径、既有交易/导入/AA/周期链路无回归；`migration-baseline` 更新
    - _Requirements: 6.5, 9.4, 9.6_
  - [x] 5.2 前端 vitest + 组件测试
    - `cd miniapp && npm test` 全绿（含 Property 2–12 与组件态渲染测试）
    - _Requirements: 9.6_
  - [x] 5.3 H5 与 mp-weixin 构建 + 页面注册 + 平台守卫
    - 注册 `pages/sync/sync`；`npm run build:h5` 与 `npm run build:mp-weixin` 均通过；确认 H5 缺离线能力时行为与今天一致（不报错）；生产 API base 正确
    - _Requirements: 9.5, 9.6_

## Notes

- 属性测试统一 ≥100 次迭代（与既有 spec 口径一致）；Property 1 在后端（jqwik），Property 2–12 在前端（vitest，mock `uni`）。
- 后端唯一改动是交易创建的 `clientToken` 幂等；其余接口契约、错误码集合零改动。删除本特性时 `V41` 列/索引与前端 `utils/offline/*`、`stores/net.js`、`stores/sync.js`、`pages/sync/*`、`NetBanner` 可整块摘除。
- 第一阶段仅 mp-weixin 生效；H5 无离线能力时须与今天行为一致，不得报错。
- 离线只放开 `expense`/`income` 创建；编辑/删除/转账/余额校准/AA/借贷在离线下由 `guardOnlineOnly()` 拦截提示。
- 所有本地存储读写必须容错（吞异常），绝不因缓存/队列故障阻断记账主路径。
