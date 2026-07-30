# Design Document

## Overview

把非微信登录从「任意用户名 + 密码」收敛为「邮箱 + 一次性验证码」，并整体无密码化。账号成为可挂载
多身份（email / wx_openid）的实体，支持绑定、解绑与注销。邮件验证码复用 lodestar 的 Spring Mail +
QQ SMTP 方案，但验证码状态存 **MySQL 表**（youyu 不引入 Redis）。

设计不变式：

- **单一身份唯一性**：`email` 与 `wx_openid` 各自全局唯一；不同账号不得共享同一身份。
- **至少一种登录方式**：任何存活账号至少有 email 或 wx_openid 之一。
- **验证码单次消费**：校验成功即失效，防重放；过期/超次即失效。
- **无密码**：不存储/不校验密码；不保留失败计数与锁定。
- **注销即释放**：注销后身份立即可被复用。

## Architecture

### 登录/注册流程（合一）

```
邮箱验证码：
  POST /api/auth/send-code {email, purpose=LOGIN}
     → 校验邮箱格式 → 冷却/IP 限流 → 生成码存表 → SMTP 发送（未配置则日志降级）
  POST /api/auth/email-login {email, code}
     → verifyCode(email, LOGIN, code)  单次消费
     → 按 email 查用户：有则登录，无则建号
     → JwtService 签发 → 返回 {token, user}

微信：
  POST /api/auth/wx-login {code}
     → jscode2session → openid → 按 openid 查/建 → 签发（不变）
```

### 绑定 / 解绑 / 注销（需登录）

```
POST /api/me/bind-email   {email, code(BIND)}   → 冲突检查 → 写 email
POST /api/me/bind-wechat  {code}                → jscode2session → 冲突检查 → 写 openid
POST /api/me/unbind       {type: email|wechat}  → 保底≥1身份 → 清字段
POST /api/me/delete       {code(DELETE)? | wxCode?} → 二次验证 → 协作牵连检查 → 级联硬删 + 释放身份
```

### 分层

- **VerificationCodeService**：发码（冷却/IP 限流/存表）、校验（过期/次数/单次消费）。依赖 `VerificationCodeSender`。
- **VerificationCodeSender**（接口）：`SmtpVerificationCodeSender`（JavaMailSender）；`LoggingVerificationCodeSender`（SMTP 未配置时降级，打印到日志）。
- **AuthService**：`emailLogin(email, code)`、`wxLogin(code)`（沿用）、`bindEmail`、`bindWechat`、`unbind`。
- **AccountDeletionService**：注销的前置校验与级联删除。
- **安全**：`/api/auth/**` 公开；`/api/me/**` 需令牌（沿用 `CurrentUser`/JWT 过滤链）。

## Components and Interfaces

### 领域实体

- `User`（改）：新增 `email`（唯一，可空）、`nickname`（可空、不唯一）；删除 `passwordHash`、`failedLoginCount`、`lockedUntil`；保留 `wxOpenid`/`wxUnionid`/`plan`/`role`/`planStartedAt`/`planExpiresAt`。
- `VerificationCode`（新）：`id/email/purpose/code/expiresAt/consumed/attemptCount/ip/createdAt`。
- `EmailCodePurpose`（枚举）：`LOGIN` / `BIND` / `DELETE`。

### 仓储

- `VerificationCodeRepository`：
  - `findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)`（取当前有效码）
  - `existsByEmailAndPurposeAndCreatedAtAfter(email, purpose, since)`（冷却判定）
  - 计数：`countByIpAndCreatedAtAfter(ip, since)`（IP 分钟/日限流）
  - `deleteByEmail(email)` / 过期清理（可选定时）
- `UserRepository`：新增 `findByEmail(email)`、`existsByEmail(email)`；保留 `findByWxOpenid`；移除 `findByUsername`/`existsByUsername`。

### 服务

**`VerificationCodeService`**
```java
void sendCode(String email, EmailCodePurpose purpose, String ip);   // 校验/冷却/限流/存表/发送
boolean verifyConsume(String email, EmailCodePurpose purpose, String code); // 校验并单次消费
```
- 6 位码；TTL 10 分钟；同 `(email,purpose)` 60s 冷却；IP 每分钟/每日上限（默认 3 / 30）；单码最多校验 5 次后失效。
- 邮箱格式正则校验（非法即 `EMAIL_INVALID`）。

**`VerificationCodeSender`**
```java
interface VerificationCodeSender { void send(String email, String code, EmailCodePurpose purpose); }
```
- `SmtpVerificationCodeSender`：`MimeMessageHelper`，from=`spring.mail.username`，主题「有余 验证码」，HTML 正文含码与用途。
- 选择策略：当 `spring.mail.host`/`username` 为占位（未配置）时注入 `LoggingVerificationCodeSender`（打印 `email code=...`），否则 SMTP 实现。

**`AuthService`（改）**
```java
User emailLogin(String email, String code);         // verifyConsume(LOGIN) → 查/建 → 返回用户
User wxLogin(String code);                            // 沿用
User bindEmail(Long userId, String email, String code); // verifyConsume(BIND) → 冲突检查 → 写 email
User bindWechat(Long userId, String wxCode);          // jscode2session → 冲突检查 → 写 openid
User unbind(Long userId, String type);                // 保底≥1身份
```

**`AccountDeletionService`（新）**
```java
void requireDeletable(Long userId);   // 协作牵连检查，违背则 DELETE_BLOCKED_COLLAB
void deleteAccount(Long userId);       // 单事务级联硬删 + 用户行删除
```

### API 与 DTO

- `AuthController`：`POST /auth/send-code`、`/auth/email-login`、`/auth/wx-login`；移除 `/auth/register`、`/auth/login`（密码）。
- `MeController`（或新 `AccountController`）：`POST /me/bind-email`、`/me/bind-wechat`、`/me/unbind`、`/me/delete`；`GET /me` 返回新摘要。
- DTO：`SendCodeRequest{email,purpose}`、`EmailLoginRequest{email,code}`、`BindEmailRequest{email,code}`、`BindWechatRequest{code}`、`UnbindRequest{type}`、`DeleteAccountRequest{code}`；`UserSummaryResponse{ id, nickname, email, hasEmail, hasWechat, plan, role, planStartedAt, planExpiresAt }`。

### 前端（miniapp）

- 登录页：微信一键登录 + 邮箱验证码（登录/注册合一，Tab 或单表单）；移除密码登录/找回密码。
- 「我的」页：显示已绑定的邮箱/微信状态，提供「绑定邮箱」「绑定微信」「解绑」「注销账号」入口与二次确认。
- `api/auth.js`：`sendCode`、`emailLogin`、`wxLogin`、`bindEmail`、`bindWechat`、`unbind`、`deleteAccount`。

## Data Models

**`users`（调整）**

| 列 | 变化 |
|----|------|
| email | 新增 VARCHAR(255) UNIQUE NULL |
| nickname | 新增 VARCHAR(64) NULL（回填自旧 username） |
| password_hash | 删除 |
| failed_login_count | 删除 |
| locked_until | 删除 |
| username | 删除（其唯一约束一并移除；展示改用 nickname） |
| wx_openid / wx_unionid / plan / role / plan_started_at / plan_expires_at | 保留 |

约束：`email` 唯一、`wx_openid` 唯一；应用层保证「至少一种身份」。

**`verification_code`（新增）**

| 列 | 类型 | 说明 |
|----|------|------|
| id | BIGINT PK | |
| email | VARCHAR(255) NOT NULL | 目标邮箱 |
| purpose | VARCHAR(16) NOT NULL | LOGIN/BIND/DELETE |
| code | VARCHAR(8) NOT NULL | 6 位数字 |
| expires_at | DATETIME NOT NULL | 过期时刻（+10min） |
| consumed | TINYINT(1) NOT NULL DEFAULT 0 | 是否已消费/失效 |
| attempt_count | INT NOT NULL DEFAULT 0 | 校验失败累计 |
| ip | VARCHAR(45) NULL | 请求来源 IP（限流/审计） |
| created_at | DATETIME NOT NULL | |

索引：`idx_vc_email_purpose (email, purpose, id)`、`idx_vc_ip_created (ip, created_at)`。

迁移 `V20__auth_email_passwordless.sql`：ALTER users（加 email/nickname、回填 nickname、删列与旧唯一键）、CREATE verification_code。

## Correctness Properties

### Property 1: 验证码单次消费
校验成功的验证码立即失效，任何二次校验都失败。
**Validates: Requirements 2.1, 5.4**

### Property 2: 验证码过期与次数上限
超过 10 分钟或失败达上限（5 次）后，验证码一律校验失败。
**Validates: Requirements 1.2, 2.2, 2.3**

### Property 3: 冷却与限流
同 `(email,purpose)` 冷却期内二次发码被拒；同 IP 超过分钟/日上限被拒。
**Validates: Requirements 1.3, 1.4**

### Property 4: 身份唯一性
任意操作序列后，`email` 与 `wx_openid` 各自在所有账号中唯一；绑定已被他人占用的身份必被拒。
**Validates: Requirements 4.1, 5.2, 6.2**

### Property 5: 至少一种登录方式
对任意账号，解绑操作不会使其失去全部登录身份；否则被拒且账号身份不变。
**Validates: Requirements 4.2, 7.1, 7.2**

### Property 6: 注销释放身份
账号注销后，其原 email/openid 可被重新注册或绑定成功。
**Validates: Requirements 7.3, 8.4**

## Error Handling

统一错误体 `{code, message, field}`。新增/复用错误码：

- `EMAIL_INVALID`（邮箱格式非法）
- `CODE_COOLDOWN`（发码冷却中）
- `CODE_RATE_LIMITED`（IP 超限）
- `EMAIL_SEND_FAILED`（SMTP 发送失败）
- `CODE_INVALID`（验证码错误/过期/已用）
- `IDENTITY_TAKEN`（目标身份已被其它账号占用）
- `IDENTITY_ALREADY_BOUND`（当前账号已绑定该类身份）
- `LAST_LOGIN_METHOD`（解绑将失去唯一登录方式）
- `DELETE_BLOCKED_COLLAB`（注销存在协作牵连，需先处理）
- 复用：`WX_CODE_REQUIRED`、`WX_LOGIN_FAILED`、`UNAUTHENTICATED`、`FIELD_REQUIRED`。

原则：发码/校验不因邮箱是否注册而给出可区分结果；失败一律零副作用（不改账号、不签发令牌）。

## Testing Strategy

- **服务/单元**：发码冷却与 IP 限流、校验的过期/次数/单次消费、邮箱登录的查/建分支、绑定冲突（IDENTITY_TAKEN / ALREADY_BOUND）、解绑保底、注销级联与协作牵连拦截。邮件发送用 `LoggingVerificationCodeSender` 或测试替身，不发真实邮件。
- **属性测试（jqwik，≥100 迭代）**：Property 1-6。
- **API/安全**：`/api/auth/**` 公开可访问；`/api/me/**` 无令牌返回 `UNAUTHENTICATED`；邮箱登录合一（新邮箱建号、老邮箱登录）；注销后身份可复用。
- **迁移**：dev/test 由实体生成 H2 表（`ddl-auto=create-drop`），保证实体与新表结构一致；生产 Flyway V20。
