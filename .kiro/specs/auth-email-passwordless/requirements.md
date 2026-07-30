# Requirements Document

## Introduction

现有登录/注册无差异化：非微信登录用任意「用户名 + 密码」字符串作为账号，没有身份限制，也没有验证环节。
本次重构把非微信登录收敛为**邮箱身份 + 邮箱验证码**，并整体改为**无密码**：

- 两种登录方式：① 微信一键登录（openid）；② 邮箱验证码（登录/注册合一）。**取消密码登录与找回密码**。
- 账号身份模型：一个用户可同时持有 `email` 与 `wx_openid`（两者各自全局唯一、均可空，但至少其一）；原 `username` 降级为**昵称**（仅展示，不作为凭证）；移除密码及登录锁定相关字段。
- 邮箱验证码：接 Spring Mail + QQ SMTP（复用 lodestar 现有邮箱与授权码），验证码存 **MySQL 表**，含冷却/限流/单次消费/过期。
- 账号绑定：登录后可在「我的」绑定另一种身份（邮箱↔微信），绑定目标已被他人占用则拒绝（不做账号合并）。
- 账号注销：二次验证后硬删除本人数据并**立即释放**其 email/openid，供在其它账号上重新绑定。

内测阶段数据库已清空、无真实用户，故本次直接调整用户模型，不做历史账号数据迁移/合并。

## Glossary

- **身份（identity）**：用于登录的稳定标识，仅两类——邮箱 `email` 与微信 `wx_openid`。
- **昵称（nickname）**：展示名，可重复、可空、可改，不用于登录（由原 `username` 降级而来）。
- **验证码用途（purpose）**：验证码的使用场景，取值 `LOGIN`（登录/注册）、`BIND`（绑定邮箱）、`DELETE`（注销二次验证）。
- **登录/注册合一**：邮箱验证码校验通过后，若该邮箱未注册则自动创建账号，否则直接登录。
- **无密码（passwordless）**：系统不存储、不校验任何登录密码。

## Requirements

### 需求 1：邮箱验证码发送与防刷

**用户故事：** 作为用户，我希望通过邮箱收到一次性验证码来登录/绑定/注销，且该能力不被滥用刷爆邮件额度。

#### 验收标准

1. WHEN 请求发送验证码 AND 邮箱格式非法 THEN 系统 SHALL 拒绝并返回 `EMAIL_INVALID`，不发送邮件。
2. WHEN 发送验证码 THEN 系统 SHALL 生成 6 位数字验证码，写入验证码存储，设置有效期为 10 分钟。
3. WHERE 同一 `(email, purpose)` 在冷却期（默认 60 秒）内再次请求发送 THE 系统 SHALL 拒绝并返回 `CODE_COOLDOWN`，不发送新邮件。
4. WHEN 同一来源 IP 的发送请求超过每分钟或每日上限 THEN 系统 SHALL 拒绝并返回 `CODE_RATE_LIMITED`。
5. WHEN 邮件发送失败（SMTP 异常）THEN 系统 SHALL 返回 `EMAIL_SEND_FAILED`，且 SHALL NOT 让请求以成功状态返回。
6. WHERE 服务端未配置可用 SMTP（如本地/内测占位配置）THE 系统 SHALL 以降级方式记录验证码到服务端日志而非发送真实邮件，便于内测联调（生产必须配置真实 SMTP）。
7. 发送/校验接口 SHALL NOT 因邮箱是否已注册而返回可区分的结果，避免邮箱枚举。

### 需求 2：邮箱验证码登录/注册合一

**用户故事：** 作为用户，我希望用邮箱 + 验证码直接登录；如果是新邮箱就自动为我创建账号。

#### 验收标准

1. WHEN 提交邮箱 + 验证码 AND 验证码正确且未过期未被使用 THEN 系统 SHALL 判定通过，并 SHALL 立即使该验证码失效（单次消费）。
2. WHEN 验证码错误/过期/已使用 THEN 系统 SHALL 拒绝并返回 `CODE_INVALID`，SHALL NOT 签发令牌。
3. WHEN 同一验证码累计校验失败达到上限（默认 5 次）THEN 系统 SHALL 使该验证码失效，后续校验一律 `CODE_INVALID`。
4. WHEN 验证通过 AND 该邮箱尚无账号 THEN 系统 SHALL 创建新用户（`email` 置该邮箱、`wx_openid` 为空、无密码、`nickname` 缺省取邮箱 @ 前缀、初始化 plan=free/role=user/plan_started_at=当前/plan_expires_at=+365 天）。
5. WHEN 验证通过 AND 该邮箱已有账号 THEN 系统 SHALL 定位该账号并登录（不重复创建）。
6. WHEN 登录/注册成功 THEN 系统 SHALL 签发 JWT，返回结构与微信登录一致（token + 用户摘要）。

### 需求 3：微信一键登录

**用户故事：** 作为微信小程序用户，我希望一键登录，无需邮箱或密码。

#### 验收标准

1. WHEN 用一次性 code 登录 AND 该 openid 尚无账号 THEN 系统 SHALL 创建纯微信用户（`wx_openid` 置该 openid、`email` 为空、无密码，套餐/角色初始化同需求 2.4）。
2. WHEN 该 openid 已有账号 THEN 系统 SHALL 定位并登录，并在获得新 `unionid` 时补写。
3. WHEN code 缺失或换取 openid 失败 THEN 系统 SHALL 返回 `WX_CODE_REQUIRED` / `WX_LOGIN_FAILED`。
4. 微信登录成功 SHALL 与邮箱登录返回一致的 token + 用户摘要结构。

### 需求 4：账号身份模型（无密码）

**用户故事：** 作为用户，我的账号可以同时挂邮箱和微信两种身份，用任一种都能登录到同一账号。

#### 验收标准

1. 用户 SHALL 可同时持有 `email` 与 `wx_openid`；两者各自**全局唯一**、均可空。
2. 系统 SHALL 保证任一账号在任意时刻**至少持有一种**登录身份（email 或 wx_openid）。
3. 系统 SHALL NOT 存储任何登录密码；SHALL NOT 保留登录失败计数/锁定字段。
4. `nickname` SHALL 仅用于展示，可空、可重复、可修改，SHALL NOT 用于登录鉴权。
5. WHEN 返回用户摘要 THEN 系统 SHALL 包含 `id`、`nickname`、`email`（脱敏或原样，见设计）、是否已绑定微信/邮箱的标志、plan、role。

### 需求 5：绑定邮箱到当前账号

**用户故事：** 作为已登录用户（如微信登录进来的），我希望把一个邮箱绑定到当前账号，之后也能用邮箱登录。

#### 验收标准

1. WHEN 已登录用户提交待绑定邮箱 + 验证码（purpose=BIND）AND 验证码通过 THEN 系统 SHALL 将该 `email` 写入当前账号。
2. IF 待绑定邮箱已被**其它账号**持有 THEN 系统 SHALL 拒绝并返回 `IDENTITY_TAKEN`，SHALL NOT 修改任何账号。
3. IF 当前账号已绑定邮箱 THEN 系统 SHALL 拒绝并返回 `IDENTITY_ALREADY_BOUND`（一个账号至多一个邮箱；换绑需先解绑）。
4. 绑定成功后 SHALL 使对应验证码失效（单次消费）。

### 需求 6：绑定微信到当前账号

**用户故事：** 作为已登录用户（如邮箱登录进来的），我希望把微信绑定到当前账号，之后也能微信一键登录。

#### 验收标准

1. WHEN 已登录用户提交微信一次性 code AND 换取 openid 成功 THEN 系统 SHALL 将该 `wx_openid`（及 unionid）写入当前账号。
2. IF 该 openid 已被**其它账号**持有 THEN 系统 SHALL 拒绝并返回 `IDENTITY_TAKEN`。
3. IF 当前账号已绑定微信 THEN 系统 SHALL 拒绝并返回 `IDENTITY_ALREADY_BOUND`。
4. code 缺失/换取失败 SHALL 返回 `WX_CODE_REQUIRED` / `WX_LOGIN_FAILED`。

### 需求 7：解绑身份

**用户故事：** 作为用户，我希望能解绑邮箱或微信，但不能把自己唯一的登录方式解掉。

#### 验收标准

1. WHEN 请求解绑某身份（email 或 wechat）AND 解绑后账号仍至少保留一种登录身份 THEN 系统 SHALL 清除该身份字段。
2. IF 解绑后账号将不再有任何登录身份 THEN 系统 SHALL 拒绝并返回 `LAST_LOGIN_METHOD`。
3. WHEN 解绑成功 THEN 被解绑的 `email`/`openid` SHALL 立即释放，可被其它账号绑定/注册。

### 需求 8：注销账号

**用户故事：** 作为用户，我希望能注销账号，彻底删除我的数据并释放我的邮箱/微信身份（例如把废号注销后，把该邮箱绑到我的主账号）。

#### 验收标准

1. WHEN 发起注销 THEN 系统 SHALL 要求二次验证：邮箱身份用户提交 `DELETE` 用途验证码，纯微信用户提交微信一次性 code 重新授权；验证不通过 SHALL 拒绝。
2. IF 注销者拥有仍有其他成员的协作账本，或其账户被其它账本的他人流水引用 THEN 系统 SHALL 拒绝并返回 `DELETE_BLOCKED_COLLAB`，提示先转交/删除相关账本或处理引用。
3. WHEN 注销通过且无上述协作牵连 THEN 系统 SHALL 在单个事务内硬删除该用户及其名下全部数据（拥有的账本、账户、`account_ledger` 关联、交易、分类、预算、借贷、项目、商家、标签、标签关联、记账模板、其账本成员/邀请记录、用户行本身）。
4. WHEN 注销完成 THEN 该用户的 `email` 与 `wx_openid` SHALL 立即释放，可被重新注册/绑定。
5. 注销 SHALL 不可逆，SHALL NOT 保留可恢复的软删副本（内测阶段不做宽限期）。

### 需求 9：数据模型迁移与端点安全

**用户故事：** 作为开发者，我需要把用户模型平滑切到新结构，并保证鉴权端点的公开/受保护边界正确。

#### 验收标准

1. 系统 SHALL 提供迁移脚本：`users` 增加 `email`（唯一）与 `nickname`，删除 `password_hash`、`failed_login_count`、`locked_until`，移除 `username` 作为唯一凭证（降级为昵称或删除并回填 nickname）；新增 `verification_code` 表。
2. `POST /api/auth/send-code`、`/api/auth/email-login`、`/api/auth/wx-login` SHALL 为公开端点（无需令牌）。
3. 绑定/解绑/注销端点 SHALL 要求有效令牌（当前会话用户），越权 SHALL 返回 `UNAUTHENTICATED`。
4. 移除已废弃的密码注册/登录端点（`/api/auth/register`、`/api/auth/login`），或使其返回明确的不支持错误。
5. Hibernate `ddl-auto=validate` 下，实体 SHALL 与迁移后的 `users`/`verification_code` 表结构一致。
