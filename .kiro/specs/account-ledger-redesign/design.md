# Design Document

## Overview

本设计将账户从"随账本类型分裂的两套池子"重构为"用户拥有的独立资产 + 账户/账本多对多可见性"。
核心是用一张 `account_ledger` 关联表 + 一个可见性解析器，替换掉贯穿多个 service 的 `AccountScope`
分支逻辑，并把转账从账本维度下沉到账户维度。

设计遵循以下不变式：

- **账户单一全局余额**：一个账户始终只有一个 `current_balance`，等于初始余额 + 引用它的全部流水（跨账本 + 转账）。
- **归属清晰**：账户始终有唯一 owner（用户）；账本始终有唯一创建者（OWNER 成员）。
- **可见性显式化**：账户能否在某账本使用、能否被他人看到、能否显示余额，都由 `account_ledger` 的行与标志显式表达，不再依赖账本类型隐式推导。
- **隐私边界**：协作成员对共享账户只能看到本账本内的流水；跨账本明细仅 owner 可见。

## Architecture

### 领域模型关系

```
User 1 ──< owns >── * Account
                        │
                        * account_ledger (visible_to_others, show_balance)
                        │
User 1 ──< creates >── * Ledger *──< members >──* LedgerMember

Transaction:
  expense/income → ledger_id NOT NULL, account_id → Account
  transfer       → ledger_id NULL, source/destination → Account(本人)
```

### 分层职责

- **可见性解析器 `LedgerAccountResolver`**：集中解析"某用户在某账本能用/能看哪些账户、能否看余额、能否用于记账更新余额"，替换 `AccountScope`。
- **服务层**：`AccountService` / `TransactionService` / `LedgerService` 去 scope，改用 owner + resolver。
- **API 层**：账户 CRUD、可见性管理、转账、转交端点；返回给非 owner 成员时做账户/余额脱敏。

### 迁移

新增 `V19__account_ledger_redesign.sql`（无存量迁移，仅建新结构）：

```sql
-- 账户去账本级概念：owner 即 user_id
ALTER TABLE accounts DROP COLUMN ledger_id;   -- 若有外键先 DROP FOREIGN KEY

-- 账户/账本多对多可见性
CREATE TABLE account_ledger (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    account_id        BIGINT      NOT NULL,
    ledger_id         BIGINT      NOT NULL,
    visible_to_others TINYINT(1)  NOT NULL DEFAULT 1,
    show_balance      TINYINT(1)  NOT NULL DEFAULT 1,
    created_at        DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_ledger (account_id, ledger_id),
    KEY idx_al_ledger (ledger_id),
    CONSTRAINT fk_al_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_al_ledger  FOREIGN KEY (ledger_id)  REFERENCES ledgers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 转账脱离账本
ALTER TABLE transactions MODIFY COLUMN ledger_id BIGINT NULL;

-- 账本类型枚举更新
UPDATE ledgers SET type='PERSONAL' WHERE type='INDEPENDENT';
```

## Components and Interfaces

### 领域实体

- `Account`：删除 `ledgerId` 字段与访问器。
- `AccountLedger`（新）：映射 `account_ledger`，字段 `id/accountId/ledgerId/visibleToOthers/showBalance/createdAt`。
- `Transaction`：`ledgerId` 保持 `Long`，但列改可空；服务层保证形态约束。
- `Ledger`：`type` 常量改为 `PERSONAL`/`COLLABORATIVE`（保留字符串存储）。

### 仓储

**`AccountRepository`（重写查询）**
- 删除全部 `...LedgerIdIsNull...`、`findByLedgerId...`、`deleteByLedgerId`、`findForUpdateByIdAndLedgerId`。
- 保留/使用按 owner 的查询：`findByUserIdOrderBySortOrderAscIdAsc`、`findByIdAndUserId`、`findForUpdateByIdAndUserId`（悲观写锁）、`findById`（记账时按可用性校验后按主键加锁）。

**`AccountLedgerRepository`（新）**
- `findByLedgerId` / `findByAccountIdAndLedgerId` / `findByAccountId`
- `deleteByLedgerId` / `deleteByAccountId` / `deleteByAccountIdInAndLedgerId`（成员退出取消暴露）

### 可见性解析器 `LedgerAccountResolver`（替换 `AccountScope`）

```java
/** 某用户在某账本记账时可选的账户（含自己纳入的 + 他人可见的）。 */
List<Account> selectableAccounts(Long userId, Long ledgerId);

/** 校验并锁定：账户在该账本对该用户可用则返回加锁实体，否则 NOT_FOUND。用于记账余额更新。 */
Account lockUsableAccount(Long userId, Long ledgerId, Long accountId);

/** 该账户在该账本对该 viewer 是否可见余额（owner 或 show_balance=1）。 */
boolean canSeeBalance(Long viewerUserId, Long ledgerId, Account account);

/** 该账户在该账本对该 viewer 是否可见（owner 或 visible_to_others=1）。 */
boolean visible(Long viewerUserId, Long ledgerId, Account account);
```

"账户在账本对用户可用"判定：owner==用户且存在关联行；或存在关联行且 `visible_to_others=1`。

### 服务层改动

- **`AccountService`**：去 `AccountScope`，CRUD 基于 owner；`delete` 校验无引用后级联删关联行；新增 `transferOwnership`；新增 `attachToLedger`/`updateVisibility`/`detachFromLedger`（detach 前检查历史流水并返回提示）；`recomputeBalance` 全局语义不变。
- **`TransactionService`**：`create`/`update` 用 `resolver.lockUsableAccount`；新增独立 `transfer(userId, sourceId, destId, amount, occurredAt, note)`（源/目标限本人、`ledger_id=null`、同事务守恒）；账本记账路径移除转账分支。
- **`LedgerService`**：`type` 归一化；`create(userId,name,type,accountIds)` 建关联行；`delete` 删关联行+账本流水、不删账户、重算余额；`removeMember` 取消该成员账户暴露。
- **`ExportService`/`ImportService`/`AggregateService`**：去 `AccountScope`，账户列表按新语义；收支按 `ledger_id` 过滤（转账天然排除）。

### API 层

- **`AccountController`**：账户 CRUD；可见性管理端点；`POST /accounts/transfer`；`POST /accounts/{id}/transfer-ownership`；账户明细（owner 全量 / 带 `ledgerId` 按账本过滤）。
- **`LedgerController`**：创建接受 `accountIds`（空=默认全选）；账户选择器返回可选集 + `canSeeBalance`。
- **`TransactionController`**：记账仅收支；列表/明细对非 owner 隐藏 `visible_to_others=0` 账户信息、隐藏 `show_balance=0` 余额。
- **DTO**：`AccountDto.canSeeBalance` + 余额脱敏、`AccountVisibilityDto`、`TransferRequest`、`TransferOwnershipRequest`、`CreateLedgerRequest.accountIds`。

## Data Models

**`accounts`（调整）**：移除 `ledger_id`；`user_id`（owner）NOT NULL；其余字段不变。

**`account_ledger`（新增）**

| 列 | 类型 | 说明 |
|----|------|------|
| id | BIGINT PK | |
| account_id | BIGINT NOT NULL | 账户，FK → accounts |
| ledger_id | BIGINT NOT NULL | 账本，FK → ledgers |
| visible_to_others | TINYINT(1) NOT NULL DEFAULT 1 | 协作账本内是否对其他成员可见/可选 |
| show_balance | TINYINT(1) NOT NULL DEFAULT 1 | 是否对其他成员显示真实余额 |
| created_at | DATETIME NOT NULL | |

唯一键 `uk_account_ledger (account_id, ledger_id)`；索引 `idx_al_ledger (ledger_id)`。

**`transactions`（调整）**：`ledger_id` 可空；`expense`/`income` 非空 + `account_id`/`category_id` 非空；`transfer` 为空 + `source_account_id`/`destination_account_id` 非空。

**`ledgers`（调整）**：`type` 取值 `PERSONAL`/`COLLABORATIVE`。

## Correctness Properties

### Property 1: 余额守恒
对任一账户，`current_balance == initial_balance + Σ(引用它的收支与转账的方向增量)`，与流水操作顺序无关。
**Validates: Requirements 1.4**

### Property 2: 转账中立
任一转账使 `源+目标` 余额之和不变，且不改变任何账本的收支统计。
**Validates: Requirements 6.5, 6.6**

### Property 3: 可见性单调
非 owner 成员可见的账户/余额集合 ⊆ owner 可见集合；`visible_to_others=0` 的账户绝不出现在他人选择器或他人可见的流水账户字段中。
**Validates: Requirements 4.3, 4.4**

### Property 4: 明细隔离
非 owner 成员对某账户可见的流水 ⊆ 该账户在其所属账本内的流水集合。
**Validates: Requirements 5.2**

### Property 5: 删除安全
删除账本或成员退出后，账户实体与其历史流水仍存在，受影响账户余额等于重算值。
**Validates: Requirements 8.1, 8.2, 8.3**

## Error Handling

沿用现有 `ApiException` 语义：
- 越权/不存在：`NOT_FOUND`（账户、账本、交易、成员）。
- 账户在用不可删：`ACCOUNT_IN_USE`。
- 转账：`FIELD_REQUIRED`（缺源/目标/金额）、`TRANSFER_SAME_ACCOUNT`、`NOT_FOUND`（账户不属本人）。
- 金额：`AMOUNT_INVALID`。
- 账本管理越权：`FORBIDDEN`（非 OWNER）。

余额更新一律事务内前置校验、失败零副作用；涉及多账户按 id 升序加悲观写锁避免死锁（沿用现有策略）。

## Testing Strategy

- **服务/单元测试**：可见性组合（own/shared/hidden）、记账更新共享账户余额、转账守恒与回滚、余额重算跨账本+转账、删账本不删账户并重算、成员退出取消暴露、账户转交。
- **属性测试（jqwik）**：随机流水序列下"重算余额 == 事务累计余额"；转账不影响收支统计。
- **API 测试**：非 owner 成员看不到 `visible_to_others=0` 账户与 `show_balance=0` 余额；协作成员账户明细仅含本账本流水。
- **边界**：转账源=目标拒绝、跨用户转账拒绝、可选集外账户记账拒绝、最后一个账本不可删。
- 无数据迁移测试；测试夹具按新模型重建。
