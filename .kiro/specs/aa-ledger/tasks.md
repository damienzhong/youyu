# Implementation Plan

## Overview

按「后端数据与算法 → 接口 → 前端 → 联调」增量实现，每步可独立编译 / 测试。账户扣款、结算增减复用既有 `TransactionService` 的账户加锁与事务性写法；核心算法（分摊 / 净额 / 清算）先行且以 jqwik 属性测试校验守恒、闭合、可清零。

## Task Dependency Graph

```
1 数据模型/迁移
├─ 2 分摊/净额/清算核心（依赖 1）
│   └─ 3 AA 记账服务/接口（依赖 1,2）
│       └─ 4 净额/清算/结算接口（依赖 2,3）
├─ 5 账本类型/成员/生命周期（依赖 1；部分依赖 4 的结清判定）
6 隔离与兼容（依赖 3）
7 前端（依赖 3,4,5）
8 端到端联调（依赖 3,4,5,6,7）
```

```json
{
  "waves": [
    { "wave": 1, "tasks": ["1"] },
    { "wave": 2, "tasks": ["2"] },
    { "wave": 3, "tasks": ["3"] },
    { "wave": 4, "tasks": ["4", "5"] },
    { "wave": 5, "tasks": ["6", "7"] },
    { "wave": 6, "tasks": ["8"] }
  ]
}
```

## Tasks

- [x] 1. 数据模型与迁移
  - [x] 1.1 新增 Flyway 迁移 V37：`ledgers` 增加 `archived_at`；`transactions` 增加 `payer_user_id`，`type` 放宽至 VARCHAR(20)
  - [x] 1.2 新增表 `transaction_splits`（transaction_id, participant_user_id, share_amount, 唯一键+索引）
  - [x] 1.3 新增表 `aa_settlements`（ledger_id, from_user_id, to_user_id, amount, from/to_account_id, settled_by, settled_at, reverted_at, 索引）
  - [x] 1.4 实体与 Repository：`TransactionSplit`、`AaSettlement`；`Ledger` 增 `TYPE_AA`/`archivedAt`/`isAa()`；`Transaction` 增 `payerUserId`；`TransactionType` 增 `AA_EXPENSE`/`AA_SETTLEMENT`
  - _Requirements: 10.1, 10.2, 1.1_

- [x] 2. 分摊与净额/清算核心（纯算法，先行可测）
  - [x] 2.1 分摊分配器 `AaMath.splitEven`（均分以分守恒、余数校正）、`isValidCustomSplit`（Σ=总额校验）
  - [x] 2.2 净额计算 `AaMath.netAmounts`：paid/consumed/settledIn/settledOut → net
  - [x] 2.3 最小化清算 `AaMath.minimalSettlements`：贪心配对，输出转账建议（≤ n−1 笔）
  - [x] 2.4 jqwik 属性测试 `AaMathPropertyTest`：Property 1（分摊守恒）、2（净额闭合）、3（清算可清零）— 500/500、300/300 全绿
  - _Requirements: 3.3, 3.4, 4.1, 4.2, 4.3, 4.5, 5.1, 5.3_

- [ ] 3. AA 记账服务与接口
  - [ ] 3.1 `AaExpenseService.create`：建 `aa_expense` + splits；付款人为本人时锁账户并按实付扣款；付款人非本人不触本人账户
  - [ ] 3.2 编辑/删除：未涉及结算才可删，回滚账户与分摊并重算；已涉结算拒删 `AA_EXPENSE_SETTLED`
  - [ ] 3.3 `AaExpenseController`：POST/PUT/DELETE `/api/aa/expenses`；成员校验（越权 NOT_FOUND）、只读账本拒写 `AA_LEDGER_ARCHIVED`
  - [ ] 3.4 集成测试：本人付款扣账户、他人付款不动本人账户、消费口径（只计自摊）、只读/越权
  - _Requirements: 3.1, 3.2, 3.5, 3.6, 3.7, 4.4, 7.1, 7.2, 9.1, 9.2a, 9.2b, 9.4, 9.5_

- [ ] 4. 净额/清算/结算接口
  - [ ] 4.1 `GET /api/aa/{ledgerId}/settlement`：每人净额 + 建议转账（派生）
  - [ ] 4.2 `POST /api/aa/settlements`：结清一条，本人侧账户增减 + 落 `aa_settlements` + 生成 `aa_settlement` 展示流水；校验金额/对象 `AA_SETTLEMENT_INVALID`
  - [ ] 4.3 `POST /api/aa/settlements/{id}/revert`：撤销，回滚账户与债务
  - [ ] 4.4 集成 + 属性测试：Property 4（账户守恒）、执行建议后 net 全 0；撤销后精确回滚
  - _Requirements: 5.2, 5.4, 5.5, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

- [ ] 5. 账本类型、成员与生命周期
  - [ ] 5.1 创建 `type=AA`（无预算入口）；概览 `GET /api/aa/{ledgerId}/overview`（三口径 + 成员净额 + 流水）
  - [ ] 5.2 邀请/加入复用既有邀请，强制登录后加入；成员列表
  - [ ] 5.3 退出/移除：净额=0 才可（`AA_MEMBER_UNSETTLED`），保留历史、退出后不参与新笔，禁止移除 owner
  - [ ] 5.4 归档/解档：`archived_at` 只读判定；未结清归档需 `force`；只读账本拒一切写
  - [ ] 5.5 集成测试：未结清阻止退出/归档、只读拒写、结清状态动态回退
  - _Requirements: 1.1, 1.3, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 6. 隔离与兼容
  - [ ] 6.1 报表/「全部账本」聚合排除 AA 账本；AA 支出不计入家庭/个人消费与预算
  - [ ] 6.2 回归：个人/家庭账本记账、报表、预算、转账、余额调整不受影响
  - _Requirements: 1.4, 7.3, 7.4, 10.3, 10.4_

- [ ] 7. 前端（miniapp）
  - [ ] 7.1 账本类型选择新增 AA 卡片；创建流程
  - [ ] 7.2 成员页：邀请链接/二维码、成员列表、退出/移除（含未结清拦截提示）
  - [ ] 7.3 记一笔（AA）：付款人、付款账户（AccountBadge）、参与分摊多选、均分/自定义、实时「本笔影响」拆解
  - [ ] 7.4 AA 首页：hero 三口径 + 流水（付款人/我摊标注）
  - [ ] 7.5 结算页：每人净额 + 建议转账 + 逐条结清（涉及本人选账户）+ 撤销
  - [ ] 7.6 归档/解档入口与「已归档」只读态
  - [ ] 7.7 与 `design/aa-ledger-prototype.html` 视觉对齐；H5 构建通过
  - _Requirements: 1.2, 2.1, 3.1, 3.6, 5.2, 6.1, 8.3, 8.5_

- [ ] 8. 端到端联调与验收
  - [ ] 8.1 全流程：建 AA → 邀请加入 → 多笔（本人/他人付、均分/自定义）→ 概览三口径 → 结算清零 → 归档
  - [ ] 8.2 校验 Property 1–6 在真实链路成立；金额闭合
  - [ ] 8.3 全量测试与 H5/mp-weixin 构建通过
  - _Requirements: 全部_

## Notes

- 迁移脚本版本号接续现有最大版本；不改既有表已有列语义，仅新增列 / 表。
- 金额分配一律以「分」为单位，末位余数校正保证 Σ 闭合。
- 账户写操作复用 `accountRepository.findForUpdateByIdAndUserId`（本人账户加锁），与转账 / 余额调整同口径。
- 每个含写操作的服务方法在单事务内保证「账户增减 + 分摊 / 结算记录」一致，失败整体回滚。
- 前端与 `design/aa-ledger-prototype.html` 视觉对齐；账户徽标复用 `AccountBadge`。
- 测试策略见 design.md「Testing Strategy」；属性对应 design.md「Correctness Properties」Property 1–6。
