# Requirements Document

## Introduction

有余（youyu）现有两种财务口径彼此独立：

- **账本收支（已实现）**：「账本」tab 与首页按**账本维度**统计某自然月的收入/支出；选「全部账本」时为跨账本聚合，
  且 `AggregateService` 明确**排除 AA 账本**（AA 的支出语义为「个人应摊份额/债权债务」，与家庭/个人合计口径不同）。
- **净资产（已实现）**：「资产」tab 按**账户维度**展示所有账户余额之和（存量），独立于账本。

由此产生一个缺口：**没有任何视图能回答「这个月/今天，我的账户实际付出去、收进来多少钱」**。尤其 AA 账本里我实付的钱
真实减少了我的账户余额，却不计入任何一个「本月支出」合计。

本规格新增 **资产现金流（Assets_Cashflow_System）**：在「资产」tab 净资产下方，按**账户维度**展示所选自然月的
**实际流出、实际流入与净流入**，并支持像账本页一样左右切月。这是**钱包视角的流量指标**，与账本收支刻意不同、
互为补充。本功能为**纯只读聚合**，不新增数据表、不改变任何既有写入与既有收支/净资产口径。

### 范围与前提约定（影响验收标准的关键决策）

1. **口径为「账户维度的实际现金流」，不是账本收支。** 只统计**真实改变当前用户账户余额**的金额，跨用户全部账本
   （个人、协作、**含 AA**）合并计算，与「账本」tab 的账本维度收支互不替代、允许数字不同。
2. **实际流出 = 我账户真实付出去的钱。** 具体为本月内使当前用户某账户余额**减少**的金额之和，来源包括：普通支出
   （`expense`）、AA 支出中 `payer_user_id` 为当前用户的**实付全额**（`aa_expense`）、当前用户付出方向的 AA 结算
   （`aa_settlement`）。
3. **实际流入 = 我账户真实收到的钱。** 为本月内使当前用户某账户余额**增加**的金额之和，来源包括：普通收入
   （`income`）、当前用户收款方向的 AA 结算（`aa_settlement`）。
4. **账户间转账不计入。** 同一用户两账户之间的转账（`transfer`）钱未离开其钱包，既不计流出也不计流入。
5. **净流入 = 实际流入 − 实际流出**，可为负（净流出）。
6. **仅统计当前用户本人的账户余额变动。** 多人账本（协作/AA）中，只计入使**本人账户**发生增减的部分，
   不计入其他成员的账户变动。
7. **时区口径为 `Asia/Shanghai`。** 自然月与「今日」的边界均按 `Asia/Shanghai`（UTC+08:00）计算，
   与账本收支、净资产同一口径。
8. **纯增量、纯只读。** 本规格只**读取** `transactions`、`accounts`、`ledgers`、`ledger_members` 等既有数据，
   **不新增数据表、不新增迁移脚本、不写入任何数据**；删掉本功能后其余功能原样成立。
9. **不改变既有口径。** 「账本」tab 收支、`AggregateService` 全部账本聚合、「资产」tab 净资产的既有取值与行为
   全部保持不变。

### 与其它 spec / 既有实现的关系

- **依赖账户体系（已实现）**：账户为用户级一等实体，`accounts.user_id` 归属当前用户。
- **依赖交易模型（已实现）**：复用 `transactions` 的 `type`（`expense/income/transfer/aa_expense/aa_settlement`）、
  `account_id`、`source_account_id`、`destination_account_id`、`payer_user_id`、`occurred_at`、软删除 `deleted_at`。
- **有别于 `AggregateService`**：后者按账本聚合且排除 AA；本功能按账户维度聚合且含 AA 实付，故**新增独立只读服务/接口**，
  不复用也不修改 `AggregateService`。
- **无迁移**：`db/migration` 当前最大版本为 `V43`；本规格为只读聚合，不新增 `V44` 或任何迁移脚本。

## Glossary

- **资产现金流系统（Assets_Cashflow_System）**：本规格涉及的账户维度现金流聚合、只读接口与「资产」tab 现金流区块的整体。
- **当前用户（current user）**：由有效令牌标识、在 `users` 表存在的登录用户。
- **有效令牌**：签名校验通过、未过期，且其标识的用户在 `users` 表中存在的访问令牌。
- **拥有账户（owned account）**：`accounts.user_id` 等于当前用户 id 的账户。
- **账户维度（account dimension）**：以「拥有账户余额是否真实增减」为口径，独立于账本归属。
- **实际流出（actual outflow）**：某自然月内使当前用户任一拥有账户余额减少的金额之和，来源为普通支出、当前用户实付的
  AA 支出全额、当前用户付出方向的 AA 结算；不含账户间转账。
- **实际流入（actual inflow）**：某自然月内使当前用户任一拥有账户余额增加的金额之和，来源为普通收入、当前用户收款方向的
  AA 结算；不含账户间转账。
- **净流入（net inflow）**：实际流入减去实际流出，可为负（净流出）。
- **内部转账（internal transfer）**：`type=transfer` 的交易，在当前用户两账户之间转移资金，不改变其账户总额。
- **AA 实付支出（AA paid expense）**：`type=aa_expense` 且 `payer_user_id` 为当前用户的交易，其 `amount` 为当前用户
  实付的总额，并已使其付款账户余额减少。
- **AA 结算（AA settlement）**：`type=aa_settlement` 的成员间清账流水，使清账双方本人侧账户余额增减。
- **选定自然月（selected month）**：现金流区块当前展示的 `Asia/Shanghai` 自然月，格式 `YYYY-MM`。
- **当前自然月（current month）**：服务端当前时刻按 `Asia/Shanghai` 折算所得的 `YYYY-MM`。
- **今日（today）**：服务端当前时刻按 `Asia/Shanghai` 折算所得的自然日。
- **软删除交易（soft-deleted transaction）**：`deleted_at` 非空的交易（回收站），不计入任何现金流。
- **API_Error**：后端统一错误体，字段为 `code`、`message` 与可选 `field`。
- **miniapp**：微信小程序端（uni-app / Vue 3）。
- **资产页（assets tab）**：miniapp 展示净资产与账户列表的一级 tab（`pages/accounts/accounts`）。

## Requirements

### 需求 1：账户维度现金流口径

**User Story:** 作为用户，我想知道这个月我的账户实际付出去、收进来多少钱，而不是某个账本记了多少。

#### 验收标准

1. THE Assets_Cashflow_System SHALL 以账户维度计算实际流出、实际流入与净流入，其口径独立于账本维度收支。
2. WHEN 计算某选定自然月的实际流出 THEN THE Assets_Cashflow_System SHALL 计入该自然月内当前用户全部拥有账户上的
   普通支出（`type=expense`）金额之和。
3. WHEN 计算某选定自然月的实际流出 THEN THE Assets_Cashflow_System SHALL 计入该自然月内 `type=aa_expense` 且
   `payer_user_id` 为当前用户的交易 `amount` 全额（AA 实付支出）。
4. WHEN 计算某选定自然月的实际流出 THEN THE Assets_Cashflow_System SHALL 计入该自然月内使当前用户本人侧账户余额
   减少的 AA 结算（`type=aa_settlement`，当前用户为付出方）金额。
5. WHEN 计算某选定自然月的实际流入 THEN THE Assets_Cashflow_System SHALL 计入该自然月内当前用户全部拥有账户上的
   普通收入（`type=income`）金额之和。
6. WHEN 计算某选定自然月的实际流入 THEN THE Assets_Cashflow_System SHALL 计入该自然月内使当前用户本人侧账户余额
   增加的 AA 结算（`type=aa_settlement`，当前用户为收款方）金额。
7. THE Assets_Cashflow_System SHALL 不将内部转账（`type=transfer`）计入实际流出或实际流入。
8. THE Assets_Cashflow_System SHALL 使净流入等于实际流入减去实际流出，并允许其为负值（净流出）。
9. THE Assets_Cashflow_System SHALL 不计入软删除交易（`deleted_at` 非空）。
10. WHERE 某笔 AA 支出的 `payer_user_id` 不是当前用户 THE Assets_Cashflow_System SHALL 不将该笔计入当前用户的
    实际流出（该笔未使当前用户账户减少）。
11. THE Assets_Cashflow_System SHALL 只计入当前用户拥有账户（`accounts.user_id` 为当前用户）的余额变动，
    不计入其他成员账户的变动。
12. THE Assets_Cashflow_System SHALL 以 `Asia/Shanghai`（UTC+08:00）划定自然月半开区间
    `[当月 1 日 00:00, 次月 1 日 00:00)`，且不依赖 JVM、数据库会话或操作系统默认时区。
13. THE Assets_Cashflow_System SHALL 以定点十进制（`DECIMAL(18,2)` 语义）累加金额，不使用二进制浮点运算。

### 需求 2：现金流查询接口

**User Story:** 作为开发者，我需要一个只读接口按自然月返回账户维度现金流，供资产页调用。

#### 验收标准

1. THE Assets_Cashflow_System SHALL 提供一个只读接口，接收 `month`（`YYYY-MM`）并返回该自然月的实际流出、实际流入、
   净流入，以及当前用户在「今日」的实际流出与实际流入。
2. WHEN 接口收到合法的 `month` 参数 THEN THE Assets_Cashflow_System SHALL 返回该自然月按需求 1 口径计算的
   实际流出、实际流入与净流入。
3. WHEN 接口返回某自然月现金流 THEN THE Assets_Cashflow_System SHALL 一并返回「今日」实际流出与实际流入
   （今日按 `Asia/Shanghai` 计），且仅当选定自然月等于当前自然月时今日值可能非零。
4. WHERE 选定自然月不是当前自然月 THE Assets_Cashflow_System SHALL 返回该月完整的月度现金流，且今日实际流出与
   今日实际流入均为 0（今日不落在该历史月内）。
5. IF `month` 缺失、为空或不能解析为 `YYYY-MM` THEN THE Assets_Cashflow_System SHALL 返回 API_Error（复用既有
   月份参数校验错误码），且不返回现金流数值。
6. THE Assets_Cashflow_System SHALL 以定点十进制字符串（两位小数）传输所有金额字段。
7. WHEN 当前用户在该自然月没有任何计入的交易 THEN THE Assets_Cashflow_System SHALL 返回实际流出、实际流入、
   净流入、今日流出、今日流入均为 `0.00`。
8. THE Assets_Cashflow_System SHALL 使该接口与会话账本无关，SHALL 不要求请求携带 `X-Ledger-Id` 头，
   SHALL 不因该头缺失或取值不可访问而拒绝请求（现金流为用户级账户维度）。

### 需求 3：数据归属与权限

**User Story:** 作为用户，我的现金流只反映我自己的账户，别人看不到也影响不了。

#### 验收标准

1. THE Assets_Cashflow_System SHALL 要求现金流接口携带有效令牌。
2. IF 请求未携带令牌、令牌签名校验失败、令牌已过期、或其标识的用户在 `users` 表中不存在 THEN THE
   Assets_Cashflow_System SHALL 返回 `UNAUTHENTICATED`（优先于任何参数校验），且响应不含任何现金流数值。
3. THE Assets_Cashflow_System SHALL 从有效令牌解析用户 id 作为唯一数据归属依据，SHALL 忽略请求中任何用于指定
   目标用户身份的查询参数、路径参数、请求体字段与自定义请求头。
4. THE Assets_Cashflow_System SHALL 只聚合当前用户拥有账户与其参与账本中归属本人的余额变动，
   SHALL 不返回其他用户的任何金额。

### 需求 4：既有口径与数据的隔离

**User Story:** 作为开发者，我要确认新增现金流是纯只读增量，不动既有任何口径与数据。

#### 验收标准

1. THE Assets_Cashflow_System SHALL 不新增数据表、不新增迁移脚本、不写入任何数据。
2. THE Assets_Cashflow_System SHALL 不改变「账本」tab 收支、`AggregateService` 全部账本聚合、「资产」tab 净资产的
   既有响应字段集、字段取值与行为。
3. THE Assets_Cashflow_System SHALL 只读取 `transactions`、`accounts`、`ledgers`、`ledger_members` 及必要的成员/分摊
   只读数据用于聚合，SHALL 不对上述任一表执行插入、更新或删除。
4. IF 现金流接口发生任何异常 THEN THE Assets_Cashflow_System SHALL 以 API_Error 返回，且 SHALL 不影响记账、账本、
   资产、登录、注销等其它路径的响应与状态码。

### 需求 5：资产页现金流区块

**User Story:** 作为用户，我希望在资产页净资产下方看到本月现金流，并能像账本页一样左右切月。

#### 验收标准

1. THE miniapp SHALL 在资产页净资产区之下、账户列表之上新增「本月现金流」区块，展示选定自然月的实际流出、
   实际流入与净流入，并展示今日实际流出与今日实际流入。
2. WHEN 资产页加载 THEN THE miniapp SHALL 以当前自然月为选定自然月请求一次现金流，请求返回前展示占位状态，
   返回后以真实取值渲染。
3. WHEN 用户切换到上一月或下一月 THEN THE miniapp SHALL 以新的选定自然月请求现金流并更新区块展示，
   其切月交互与账本页保持一致。
4. WHERE 选定自然月不是当前自然月 THE miniapp SHALL 隐藏或以 0 呈现今日实际流出/流入（今日不落在该历史月内）。
5. WHEN 现金流数值渲染 THEN THE miniapp SHALL 以两位小数展示每个金额，净流入为负时以「净流出」语义或负号明确区分。
6. WHERE 用户开启金额隐藏 THE miniapp SHALL 对现金流区块的每个金额施加与资产页净资产一致的隐藏展示。
7. THE miniapp SHALL 在区块内以简短说明标示该口径为「账户实际收支（含 AA 实付、不含转账）」，与账本收支区分，
   避免用户因两处数字不同而困惑。
8. IF 现金流请求返回错误或自发出起 3000 毫秒内无响应 THEN THE miniapp SHALL 在该区块展示失败提示与重试入口、
   SHALL 使该请求自动重试次数为 0，且 SHALL 不影响资产页其余已加载内容（净资产、借贷、账户列表）。
9. THE miniapp SHALL 复用项目既有请求封装获取现金流数据，SHALL 不在现金流区块展示任何其他用户的信息。

### 需求 6：正确性与测试

**User Story:** 作为交付团队，我要确保现金流口径在各种交易组合下都算得对。

#### 验收标准

1. THE Assets_Cashflow_System SHALL 使实际流出、实际流入的聚合结果，等于对当月计入交易按需求 1 口径逐笔累加之和
   （与逐笔求和口径一致）。
2. THE Assets_Cashflow_System SHALL 对任意包含 `expense`/`income`/`transfer`/`aa_expense`/`aa_settlement` 及软删除的
   交易集合，产出与需求 1 规则一致的流出、流入与净流入。
3. THE Client_Project SHALL 为现金流的口径计算（含 AA 实付、结算方向、转账排除、软删除排除、时区月界）提供自动化测试。
