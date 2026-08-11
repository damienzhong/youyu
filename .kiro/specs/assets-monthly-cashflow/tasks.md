# Implementation Plan: 资产现金流（Assets_Cashflow_System）

## Overview

纯只读增量：先落无状态的口径归类纯函数与只读聚合服务（数据基座为既有 `transactions`/`accounts`，无迁移、无新表），
再补只读查询与接口，最后接上 miniapp 资产页的「本月现金流」区块与切月。金额口径复杂（AA 实付、结算方向、转账排除、
时区月界），故**口径计算的属性测试列为必做**（任务 1.2、2.2）；接口鉴权与前端交互测试标为可选 `*`。

实现语言：**Java（Spring Boot）**，包根 `com.damien.youyu`；miniapp 为 uni-app / Vue 3。金额一律 `BigDecimal`，
时区 `Asia/Shanghai`（注入 `Clock`）。**不新增迁移、不写库、不改动 `AggregateService` 与既有收支/净资产口径。**

## Tasks

- [x] 1. 口径归类纯函数 + 只读查询
  - [x] 1.1 实现 `CashflowClassifier.classify(type, amount, payerUserId, createdBy)` 纯函数
    - 归类规则：`expense`/`aa_expense` → 流出全额；`income` → 流入全额；`aa_settlement` → `payerUserId == createdBy` ? 流出 : 流入；`transfer` → 不计入
    - 纯函数、无副作用、无 IO，便于属性测试；金额用 `BigDecimal`
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 1.2 `CashflowClassifier` 属性测试（**必做**）
    - **Property 1: 归类与逐笔口径一致** — **Validates: Requirements 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.9, 6.1, 6.2**
    - **Property 2: 转账零贡献** — **Validates: Requirements 1.7**
    - **Property 3: AA 实付归属** — **Validates: Requirements 1.3, 1.10, 1.11**
    - **Property 4: AA 结算方向** — **Validates: Requirements 1.4, 1.6**
    - **Property 5: 净流入恒等与可负** — **Validates: Requirements 1.8**
    - jqwik ≥100 次迭代，生成混合 type、随机 `payer_user_id`/`created_by`、金额边界；每条属性单独 `@Property`，注释标注 `// Feature: assets-monthly-cashflow, Property N: ...`

  - [x] 1.3 新增 `TransactionRepository` 只读查询方法
    - 按 `account_id IN (:accountIds)` + `occurredAt` 半开区间查询；软删除由既有 `@SQLRestriction(deleted_at IS NULL)` 自动排除
    - 若已有可复用的按账户 + 区间查询则复用；否则新增不破坏既有签名的方法
    - _Requirements: 1.9, 1.11_

- [x] 2. 只读聚合服务
  - [x] 2.1 实现 `AssetsCashflowService.cashflow(userId, month)`
    - `@Transactional(readOnly = true)`；取本人账户 id 集合（`accountRepository.findByUserId`），空集直接返回全 `0.00`
    - 用注入 `Clock`（`Asia/Shanghai`）算自然月半开区间 `[1日00:00, 次月1日00:00)`；查当月、`account_id∈本人账户`、未软删交易
    - 逐笔经 `CashflowClassifier.classify` 累加流出/流入；`netInflow = 流入 − 流出`（可为负）
    - 今日子集：仅当 `month == YearMonth.now(clock)` 时对 `occurredAt` 落在今日的交易同法累加，否则今日两值 `0.00`
    - 全程 `BigDecimal`，输出 `setScale(2)`
    - _Requirements: 1.8, 1.9, 1.10, 1.11, 1.12, 1.13, 2.2, 2.3, 2.4, 2.7_

  - [x] 2.2 `AssetsCashflowService` 聚合属性测试（**必做**）
    - **Property 6: 时区月界** — **Validates: Requirements 1.12**
    - **Property 7: 今日子集与选定月的关系** — **Validates: Requirements 2.3, 2.4**
    - **Property 8: 空集归零** — **Validates: Requirements 2.7**
    - **Property 9: 仅本人账户** — **Validates: Requirements 1.11, 3.4**
    - jqwik ≥100 次迭代，生成月界/今日边界时刻、他人账户交易、空集；每条属性单独 `@Property`，注释标注

- [x] 3. 只读接口
  - [x] 3.1 实现 `AssetsCashflowController`（`GET /api/all/cashflow`）
    - 首步 `currentUser.requireUserId()`：无效令牌/用户不存在 → `UNAUTHENTICATED`（先于参数校验），响应不含现金流数值
    - `parseMonth(month)` 非法 → 复用 `ApiException.reportParamInvalid("month", ...)`，不新增错误码
    - 数据归属只认令牌 userId，忽略任何指定目标身份的参数/头；不要求 `X-Ledger-Id`
    - 返回 `CashflowResponse`（month/outflow/inflow/netInflow/todayOutflow/todayInflow，两位小数字符串）
    - **不改动** `AggregateController` / `AggregateService`
    - _Requirements: 2.1, 2.5, 2.6, 2.8, 3.1, 3.2, 3.3_

  - [x] 3.2 新增 `CashflowResponse` DTO
    - record，6 个字符串字段；金额两位小数格式化
    - _Requirements: 2.1, 2.6_

  - [ ]* 3.3 接口鉴权与形态单元测试（可选）
    - **Property 10: 鉴权优先且零数值泄漏** — **Validates: Requirements 3.1, 3.2**
    - **Property 11: 归属只认令牌、忽略目标身份与账本头** — **Validates: Requirements 2.8, 3.3**
    - EXAMPLE：字段集与两位小数（2.1、2.6）、空集归零（2.7）、历史月今日为 0（2.4）、月份非法错误码（2.5）

- [x] 4. Checkpoint - 后端口径与接口通过
  - 确保 `./mvnw -q -o compile` 通过、相关测试绿；如有疑问询问用户

- [x] 5. miniapp 资产页现金流区块
  - [x] 5.1 新增 `miniapp/src/api/cashflow.js`
    - `fetchCashflow(month)`，`noLedger:true`，复用既有 `http` 封装
    - _Requirements: 5.9_

  - [x] 5.2 在 `pages/accounts/accounts.vue` 净资产下方、账户列表上方新增「本月现金流」区块
    - 展示选定月流出/流入/净流入 + 今日流出/今日流入；onShow 以当前月请求一次，返回前占位、返回后渲染
    - 左右切月复用账本页交互，切月后以新月请求；历史月今日值以 0 呈现或隐藏
    - 金额两位小数，净流入为负以「净流出」/负号区分；金额隐藏与净资产一致
    - 区块内简短说明「账户实际收支（含 AA 实付、不含转账）」，与账本收支区分
    - 请求出错或 3000ms 超时 → 区块内失败 + 重试、自动重试 0 次，不影响净资产/借贷/账户列表
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_

  - [ ]* 5.3 miniapp 区块交互单元测试（可选）
    - 占位→渲染、切月、历史月今日处理、金额隐藏、超时失败态 + 重试、不影响其余区块
    - _Requirements: 5.2, 5.3, 5.4, 5.6, 5.8_

- [x] 6. Final checkpoint - 全量构建与测试通过
  - 后端 `./mvnw -o test` 全绿；miniapp `npx uni build -p mp-weixin` 与 H5 构建通过；确认无迁移、无写库、既有口径不变（需求 4.1–4.4）

## Notes

- 标 `*` 的子任务为可选（接口鉴权测试、前端交互测试）；**口径计算属性测试（1.2、2.2）为必做**，因为算错即算错钱。
- 纯只读增量：不新增表 / 迁移，不写库，不改动 `AggregateService` 与既有账本收支 / 净资产口径。
- 属性测试逐条对齐设计的 11 条正确性属性，每条单独 jqwik `@Property`（≥100 次迭代），注释标注 `// Feature: assets-monthly-cashflow, Property N: ...`。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3"] },
    { "id": 1, "tasks": ["1.2", "2.1", "3.2"] },
    { "id": 2, "tasks": ["2.2", "3.1"] },
    { "id": 3, "tasks": ["3.3", "5.1"] },
    { "id": 4, "tasks": ["5.2"] },
    { "id": 5, "tasks": ["5.3"] }
  ]
}
```
