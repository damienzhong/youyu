# Implementation Plan: 账户与账本解耦重构

## Overview

无存量数据迁移；每完成一组任务运行 `./mvnw test` 验证，破坏性改动分步推进。
后端先行（模型 → 仓储 → 解析器 → 服务 → API），前端 miniapp 随后适配，最后补齐测试。

## Tasks

- [x] 1. 数据模型与迁移
  - [x] 1.1 新增迁移 `V19__account_ledger_redesign.sql`：`accounts` 去 `ledger_id`；建 `account_ledger` 表；`transactions.ledger_id` 改可空；`ledgers.type` 归一化 `INDEPENDENT`→`PERSONAL`
  - [x] 1.2 `Account` 实体删除 `ledgerId` 字段与访问器
  - [x] 1.3 新增 `AccountLedger` 实体（`accountId/ledgerId/visibleToOthers/showBalance/createdAt`）
  - [x] 1.4 `Ledger` 类型常量改为 `PERSONAL`/`COLLABORATIVE`；`Transaction` 保持 `ledgerId` 可空
  - _需求: 1, 2, 3, 6_

- [x] 2. 仓储层
  - [x] 2.1 `AccountRepository` 删除 scope 系查询，保留/补齐按 owner 查询与 `findForUpdateByIdAndUserId`
  - [x] 2.2 新增 `AccountLedgerRepository`（按账本/账户查询、删除、成员退出批量取消暴露）
  - _需求: 1, 3, 8_

- [x] 3. 可见性解析器
  - [x] 3.1 新增 `LedgerAccountResolver`：`selectableAccounts` / `lockUsableAccount` / `canSeeBalance` / `visible`
  - [x] 3.2 删除 `AccountScope`
  - _需求: 3, 4_

- [x] 4. 账户服务
  - [x] 4.1 `AccountService` 去 `AccountScope`，CRUD 基于 owner；删除级联删关联行并保留"在用不可删"校验
  - [x] 4.2 新增可见性管理：`attachToLedger` / `updateVisibility` / `detachFromLedger`（detach 前检查历史流水并返回提示）
  - [x] 4.3 新增 `transferOwnership`（改 owner，保留余额/历史/关联行）
  - [x] 4.4 `recomputeBalance` 保持全局余额语义（跨账本 + 转账）
  - _需求: 1, 3, 4, 9_

- [x] 5. 交易服务
  - [x] 5.1 `create`/`update` 账户校验与加锁改用 `resolver.lockUsableAccount`，余额落全局账户
  - [x] 5.2 新增独立 `transfer(userId, sourceId, destId, amount, occurredAt, note)`：源/目标限本人、不等、`ledger_id=null`、同事务守恒
  - [x] 5.3 从账本记账路径移除转账分支
  - [x] 5.4 账户明细查询：owner 全量、协作成员按账本过滤（含 `/api/accounts/{id}/transactions` 端点）
  - _需求: 4, 5, 6_

- [x] 6. 账本服务
  - [x] 6.1 `type` 归一化 `PERSONAL`/`COLLABORATIVE`
  - [x] 6.2 `create` 接受 `accountIds`（默认全选），建 `account_ledger` 行
  - [x] 6.3 `delete` 删关联行 + 账本流水、不删账户、重算受影响账户余额；移除删账本级账户逻辑
  - [x] 6.4 `removeMember` 退出/移除时取消该成员账户在此账本的暴露
  - _需求: 2, 3, 8_

- [x] 7. 导出/导入/聚合适配
  - [x] 7.1 `ExportService` / `ImportService` 去 `AccountScope`，账户列表按新语义
  - [x] 7.2 `AggregateService` / `ReportService` 确认转账（`ledger_id` 空）不入收支统计
  - _需求: 1, 6_

- [x] 8. API 与 DTO
  - [x] 8.1 `AccountController`：CRUD、可见性管理端点、`transfer`、`transfer-ownership`、可选账户端点
  - [x] 8.2 `LedgerController`：创建接受 `accountIds`
  - [x] 8.3 `TransactionController`：记账仅收支（转账移至账户端点）
  - [x] 8.4 DTO：`AccountResponse.canSeeBalance` + 余额脱敏、`AccountVisibilityRequest`、`TransferRequest`、`TransferOwnershipRequest`、`LedgerCreateRequest.accountIds`
  - _需求: 3, 4, 5, 6, 9_

- [x] 9. 默认账户记忆
  - [x] 9.1 按 `(user, ledger)` 记忆最近使用账户（`/api/accounts/default`），失效回退到可选集第一个
  - _需求: 7_

- [x] 10. 前端 miniapp 适配（API 客户端全量对接新端点；H5 构建通过）
  - [x] 10.1 账户管理页：协作账本内账户共享开关（对成员可见/显示余额，`GET/PUT /accounts/{id}/visibility`）+ 转交账户（从成员中选择）
  - [x] 10.2 新建账本：账户多选面板（默认全选，`createLedger(name, type, accountIds)`）
  - [x] 10.3 转账入口迁至账户/资产页（资产页「账户转账」→ 记账页 transfer 模式，走 `/accounts/transfer`）
  - [x] 10.4 记账账户选择器按账本可选集（`/accounts/selectable`）+ 默认账户（`/accounts/default`）；余额不可见字段为 null
  - _需求: 3, 4, 6, 7, 9_

- [x] 11. 测试（既有测试套件已按新模型全部迁移并通过：217 passed）
  - [x] 11.1 服务/单元：账户 CRUD、转账守恒与回滚、余额重算、删账本重算（不删账户）、账本创建选账户
  - [x] 11.2 属性测试：随机流水下重算余额==累计余额；转账不入收支
  - [x] 11.3 服务测试：账户明细 owner 全量 / 协作成员仅本账本 / 不可见 NOT_FOUND；默认账户记忆与回退（AccountDetailAndDefaultTest）
  - [x] 11.4 边界：源=目标、跨用户转账、可选集外记账、最后一个账本不可删
  - _需求: 全部_

## Task Dependency Graph

```json
{
  "waves": [
    { "wave": 1, "tasks": ["1"], "dependsOn": [] },
    { "wave": 2, "tasks": ["2"], "dependsOn": ["1"] },
    { "wave": 3, "tasks": ["3"], "dependsOn": ["2"] },
    { "wave": 4, "tasks": ["4", "5"], "dependsOn": ["3"] },
    { "wave": 5, "tasks": ["6", "7"], "dependsOn": ["4", "5"] },
    { "wave": 6, "tasks": ["8"], "dependsOn": ["4", "5", "6"] },
    { "wave": 7, "tasks": ["9"], "dependsOn": ["8"] },
    { "wave": 8, "tasks": ["10"], "dependsOn": ["8", "9"] },
    { "wave": 9, "tasks": ["11"], "dependsOn": ["8"] }
  ]
}
```

说明：任务 11（测试）随各后端任务推进增量补齐，其 API 测试子项（11.3）依赖任务 8 完成。

## Notes

- 破坏性重构：优先保证后端每个阶段可编译、`./mvnw test` 可跑通再进入下一步。
- 无数据迁移：迁移脚本仅建新结构，测试夹具按新模型重建。
- 现有 `AccountScope` 相关测试会大量失效，属预期，随对应任务改写。
- 前端（任务 10）可在后端 API（任务 8）稳定后并行推进。
