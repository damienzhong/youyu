# Design Document

## Overview

AA 账本在既有「账本 / 账户 / 交易」体系上，新增**第三种账本类型 `AA`**，并引入两类新数据：**交易分摊（transaction_splits）** 与 **结算记录（aa_settlements）**。核心不变量：

- **账户余额**只反映真实现金进出（付款扣款、结算收付）。
- **消费**只计各成员自身分摊份额；**应收 / 应付**为派生的债权 / 债务，不落「余额」。
- **净额**（Σ付款 − Σ应摊）实时派生，全体之和恒为 0；**清算方案**实时派生、不落库；**结算流水**落库以追溯并驱动账户与债务变化。

设计目标：与个人 / 家庭账本**完全隔离且向后兼容**；所有写操作单事务原子；金额以「分」为单位分配、余数校正保证闭合。

## Architecture

```
miniapp (AA 页面：新建/成员/记一笔/首页/结算/归档)
  │  REST + X-Ledger-Id
  ▼
AaLedgerController / AaExpenseController / AaSettlementController
  ▼
AaLedgerService（成员/生命周期） AaExpenseService（记账+分摊） AaSettlementService（净额/清算/结算）
  ▼
Repositories：Ledger / LedgerMember / Transaction / TransactionSplit / AaSettlement / Account
  ▼
MySQL（Flyway 增量迁移新增表/字段）
```

复用既有：`Ledger`、`LedgerMember`、`Transaction`、`Account`、账户加锁与余额更新（`TransactionService` 中 `lockOwnedAccounts`/`applyDeltas` 同款事务性写法）、`CurrentUser`/`CurrentLedger`、`ApiException` 错误体。

## Data Models

Flyway 增量迁移新增表 / 字段：

新增迁移脚本（版本号接续现有最大版本）：

1. `ledgers.type` 取值新增 `AA`（应用层常量 `Ledger.TYPE_AA`）。新增 `archived_at TIMESTAMP NULL`（归档时间；NULL=未归档，用于只读判定）。
2. 交易表 `transactions` 复用：
   - `type` 新增语义值 `aa_expense`（AA 支出）与 `aa_settlement`（结算流水）。
   - 复用 `created_by`（记账人）、`account_id`（付款人为本人时的付款账户）、`ledger_id`、`amount`、`category_id`、`occurred_at`。
   - 新增 `payer_user_id BIGINT NULL`（AA 支出的付款人；结算流水用 source/destination 表达付/收成员，见下）。
   - 结算流水复用 `source_account_id`/`destination_account_id` 语义不足以表达「成员」，故结算落 `aa_settlements`（下）并生成一条 `type=aa_settlement` 的展示用交易（可选，用于统一流水列表）。
3. `transaction_splits`（分摊）：
   ```
   id, transaction_id (FK), participant_user_id, share_amount DECIMAL(18,2),
   created_at
   唯一键 (transaction_id, participant_user_id)
   索引 (transaction_id)
   ```
4. `aa_settlements`（结算记录）：
   ```
   id, ledger_id, from_user_id, to_user_id, amount DECIMAL(18,2),
   from_account_id NULL, to_account_id NULL,  -- 各方结清时所选账户（本人侧才有值）
   settled_by, settled_at, reverted_at NULL,  -- reverted_at 支持撤销结算
   索引 (ledger_id), (ledger_id, reverted_at)
   ```

> 说明：AA 支出的**付款账户扣款**沿用既有账户余额更新（付款人为本人时 `account_id` 记录并扣款）；付款人非本人时该笔不触本人账户。结算的账户增减在确认时对**本人侧账户**执行。

## 关键口径与算法

### 分摊分配（均分 / 自定义，以「分」守恒）
- 均分：`base = floor(total_cents / n)`；前 `total_cents - base*n` 个参与人各 +1 分（稳定顺序，保证 Σ=total）。
- 自定义：直接采用各输入额，服务端校验 `Σ = total`，否则 `AA_SPLIT_MISMATCH`。

### 净额（派生）
对账本内**未撤销**的 AA 支出与结算：
```
paid[u]   = Σ 该用户作为 payer 的 aa_expense.amount
consumed[u] = Σ 各 aa_expense 中 u 的 share_amount
settledIn[u]  = Σ aa_settlements(to=u, 未撤销).amount
settledOut[u] = Σ aa_settlements(from=u, 未撤销).amount
net[u] = paid[u] - consumed[u] - settledIn[u] + settledOut[u]
```
（收款减少你的应收 → net 下降；付款减少你的应付 → net 上升。）全体 `Σ net = 0`（不变量，测试校验）。

### 最小化清算（贪心）
输入各 `net[u]`：债权集 C（net>0，降序）、债务集 D（net<0 取绝对值，降序）。循环取 `min(D.top, C.top)` 生成一条 `from=D.top → to=C.top`，扣减两侧，归零者出队。产出转账笔数 ≤ n−1；金额之和 = Σ 正 net。属性测试：任意随机净额（和为 0）→ 方案可使所有净额归零。

## Components and Interfaces

服务组件：`AaLedgerService`（成员/生命周期/归档）、`AaExpenseService`（记账+分摊+账户扣款）、`AaSettlementService`（净额/清算/结算/撤销）；控制器按下方接口划分。接口如下（均按 `X-Ledger-Id` 隔离；仅成员可访问，越权 NOT_FOUND；只读账本拒写）：

- `POST /api/ledgers`（既有，扩展）：`type=AA` 创建。
- `POST /api/ledgers/{id}/invite`、`POST /api/ledgers/{id}/join`（复用/扩展既有邀请；加入要求登录用户）。
- `GET  /api/ledgers/{id}/members`；`DELETE /api/ledgers/{id}/members/{userId}`（移除，需净额=0，非 owner）。
- `POST /api/aa/expenses`：`{ amount, categoryId, payerUserId, payerAccountId?, occurredAt?, note?, splitMode: even|custom, participants:[userId], customShares?:[{userId,amount}] }` → 建 `aa_expense` + splits，付款人为本人则扣账户。
- `PUT/DELETE /api/aa/expenses/{id}`：编辑/删除（未涉及结算才可删；回滚重算）。
- `GET  /api/aa/{ledgerId}/overview`：我的净额（账户已支出/我的消费/待收回）、成员净额、流水。
- `GET  /api/aa/{ledgerId}/settlement`：每人净额 + 最小化清算建议（派生）。
- `POST /api/aa/settlements`：`{ toUserId|fromUserId, amount, myAccountId }` 结清一条（本人侧扣/加账户，落 `aa_settlements`，生成结算流水）。
- `POST /api/aa/settlements/{id}/revert`：撤销结算（回滚账户与债务）。
- `POST /api/ledgers/{id}/archive`、`POST /api/ledgers/{id}/unarchive`：归档/解档（未结清归档需 `?force=true`）。

响应金额一律字符串化 `BigDecimal`(2dp)，与既有 `TransactionResponse` 风格一致。

## 事务边界（原子性）
- 记 AA 支出：单事务内 = 建交易 + 写 splits +（付款人为本人时）锁账户并扣款。任一失败整体回滚。
- 结算：单事务 = 写 `aa_settlements` +（生成展示流水）+ 本人侧账户增减。
- 撤销结算 / 删除支出：单事务回滚对应账户与派生（splits/settlement 状态）。
- 账户加锁复用 `accountRepository.findForUpdateByIdAndUserId`（本人账户，与转账/余额调整同口径）。

## 前端（miniapp）
- 账本类型选择新增 AA 卡片（复用 `design/aa-ledger-prototype.html` 视觉）。
- 记一笔（AA 模式）：付款人选择、付款账户（`AccountBadge`）、参与分摊多选、均分/自定义分段、实时「本笔影响」拆解。
- AA 账本首页：hero 三口径（账户已支出/我的消费/待收回）+ 流水（标注付款人与我摊）。
- 结算页：每人净额 + 建议转账 + 逐条结清（涉及本人选账户）。
- 归档/解档入口与「已归档」只读态。
- 复用 `AccountBadge`、金额 `tabular-nums`、既有分类图标。

## Error Handling

新增错误码，沿用 ApiException 风格：
- `AA_SPLIT_MISMATCH`（自定义分摊之和 ≠ 总额）
- `AA_NOT_MEMBER`（越权，实际对外表现为 NOT_FOUND）
- `AA_LEDGER_ARCHIVED`（只读账本写操作被拒）
- `AA_MEMBER_UNSETTLED`（未结清不可退出/移除）
- `AA_EXPENSE_SETTLED`（已涉及结算的笔不可直接删）
- `AA_SETTLEMENT_INVALID`（结算金额/对象非法或超出应结）

## Correctness Properties

### Property 1: 分摊守恒
任一 AA 支出，Σ(各参与人 share_amount) = 总额（以「分」为单位精确相等）。
**Validates: Requirements 3.3, 3.4, 4.5**

### Property 2: 净额闭合
任一账本，Σ(全体成员 net) = 0，在记账、结算、撤销、删除之后均成立。
**Validates: Requirements 5.1**

### Property 3: 清算可清零
对任意满足 Σnet=0 的净额向量，贪心清算方案执行后所有成员 net = 0，且转账笔数 ≤ n−1。
**Validates: Requirements 5.3**

### Property 4: 账户守恒
付款人为本人的 AA 支出使其账户恰减实付额；结算使本人侧账户恰变动结算额；撤销结算精确回滚，无漂移。
**Validates: Requirements 6.2, 6.3, 7.1**

### Property 5: 消费口径隔离
某成员消费统计 = Σ其自身 share_amount；应收 / 应付不计入消费，也不计入账户余额。
**Validates: Requirements 4.4, 7.2**

### Property 6: 特性隔离
AA 账本数据不进入「全部账本」聚合与家庭 / 个人报表；移除 AA 特性后其余功能原样成立。
**Validates: Requirements 7.4, 10.3**

## Testing Strategy
- 单元：分摊分配（均分余数、自定义校验）、净额计算、最小化清算（笔数 ≤ n−1、可全部归零）。
- 属性测试（jqwik）：随机金额/参与人/分摊 → ①Σ分摊=总额 ②Σnet=0 ③执行清算方案后所有 net=0 ④账户/债务在记账+结算后守恒。
- 集成：记账扣账户、结算增减账户、撤销回滚、只读拒写、越权 NOT_FOUND、未结清阻止退出/归档确认。
- 兼容回归：个人/家庭账本记账/报表/预算不受影响。

## Glossary
见 `requirements.md` 术语表（AA 账本 / 参与人 / 付款人 / 分摊 / 我的消费 / 应收应付 / 净额 / 清算 / 结算 / 结清 / 归档）。本设计另定：
- **aa_expense / aa_settlement**：交易 `type` 的两个新语义值，分别表示 AA 支出与结算展示流水。
- **transaction_splits**：一笔 AA 支出对各参与人的分摊额表。
- **aa_settlements**：结算记录表（含撤销标记 `reverted_at`）。
- **archived_at**：账本归档时间；非空即只读。
