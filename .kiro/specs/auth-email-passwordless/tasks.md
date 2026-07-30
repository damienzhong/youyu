# Implementation Plan: 邮箱验证码无密码鉴权 + 身份绑定/注销

## Overview

内测阶段、数据库已清空，可直接切换用户模型，无历史账号迁移。后端先行（模型/迁移 → 验证码 →
鉴权/绑定/注销 → API/安全），前端 miniapp 随后适配。每完成一组运行 `./mvnw test`。
邮件复用 lodestar 的 QQ SMTP（授权码），验证码存 MySQL；未配置 SMTP 时日志降级，便于内测。

## Tasks

- [x] 1. 数据模型与迁移
  - [x] 1.1 迁移 `V20__auth_email_passwordless.sql`：`users` 加 `email`(UNIQUE NULL)、`nickname`(NULL, 回填自 username)，删 `password_hash`/`failed_login_count`/`locked_until`，移除 `username` 及其唯一键；建 `verification_code` 表 + 索引
  - [x] 1.2 `User` 实体：加 `email`/`nickname`，删密码与锁定字段与访问器；保留 wx/plan/role
  - [x] 1.3 新增 `VerificationCode` 实体与 `EmailCodePurpose` 枚举（LOGIN/BIND/DELETE）
  - _需求: 4, 9_

- [x] 2. 仓储层
  - [x] 2.1 `UserRepository`：加 `findByEmail`/`existsByEmail`，去 `findByUsername`/`existsByUsername`
  - [x] 2.2 新增 `VerificationCodeRepository`：取有效码、冷却存在性、IP 计数、按邮箱删除
  - _需求: 1, 2, 5, 6_

- [x] 3. 验证码服务与发送器
  - [x] 3.1 `VerificationCodeSender` 接口 + `SmtpVerificationCodeSender`（JavaMailSender/QQ SMTP）+ `LoggingVerificationCodeSender`（未配置降级）；按配置选择注入
  - [x] 3.2 `VerificationCodeService`：`sendCode`（邮箱校验/冷却/IP 限流/存表/发送）与 `verifyConsume`（过期/次数/单次消费）
  - [x] 3.3 `application.yml` 增 `spring.mail.*`（QQ SMTP，env 注入）与 `app.auth.email-code.*`（ttl/cooldown/ip 限额）
  - _需求: 1, 2_

- [x] 4. 鉴权服务（无密码）
  - [x] 4.1 `AuthService.emailLogin(email, code)`：verifyConsume(LOGIN) → 按 email 查/建 → 返回用户（建号初始化 plan/role/nickname）
  - [x] 4.2 `wxLogin` 沿用；移除密码 `register`/`login` 逻辑
  - [x] 4.3 `bindEmail`/`bindWechat`：verify/换取 openid → 冲突检查（IDENTITY_TAKEN / ALREADY_BOUND）→ 写身份
  - [x] 4.4 `unbind(type)`：保底「至少一种登录方式」（LAST_LOGIN_METHOD）
  - _需求: 2, 3, 5, 6, 7_

- [x] 5. 注销服务
  - [x] 5.1 `AccountDeletionService.requireDeletable`：协作账本（有他人成员）/ 账户被他人流水引用 → `DELETE_BLOCKED_COLLAB`
  - [x] 5.2 二次验证：邮箱用户 DELETE 验证码 / 微信用户重新授权
  - [x] 5.3 `deleteAccount`：单事务级联硬删本人全部数据 + 用户行，释放 email/openid
  - _需求: 8_

- [x] 6. 错误码
  - [x] 6.1 `ApiException` 增 `EMAIL_INVALID`/`CODE_COOLDOWN`/`CODE_RATE_LIMITED`/`EMAIL_SEND_FAILED`/`CODE_INVALID`/`IDENTITY_TAKEN`/`IDENTITY_ALREADY_BOUND`/`LAST_LOGIN_METHOD`/`DELETE_BLOCKED_COLLAB`
  - _需求: 1, 2, 5, 6, 7, 8_

- [x] 7. API 与 DTO 与安全
  - [x] 7.1 `AuthController`：`send-code`/`email-login`/`wx-login`；移除 `register`/`login`
  - [x] 7.2 `MeController`/`AccountController`：`bind-email`/`bind-wechat`/`unbind`/`delete`；`GET /me` 新摘要
  - [x] 7.3 DTO：SendCode/EmailLogin/BindEmail/BindWechat/Unbind/DeleteAccount；`UserSummaryResponse` 增 nickname/email/hasEmail/hasWechat
  - [x] 7.4 `SecurityConfig`：`/api/auth/**` 公开，`/api/me/**` 需令牌
  - _需求: 2, 3, 5, 6, 7, 8, 9_

- [x] 8. 前端 miniapp 适配
  - [x] 8.1 `api/auth.js`：sendCode/emailLogin/wxLogin/bindEmail/bindWechat/unbind/deleteAccount
  - [x] 8.2 登录页重构：微信一键 + 邮箱验证码（登录/注册合一），去密码/找回密码
  - [x] 8.3 「我的」页：绑定邮箱/微信、解绑、注销账号（二次确认）
  - [x] 8.4 用户展示改用 nickname；登录成功存 token 流程不变
  - _需求: 2, 3, 5, 6, 7, 8_

- [x] 9. 测试
  - [x] 9.1 服务/单元：发码冷却与限流、校验过期/次数/单次消费、邮箱登录查/建、绑定冲突、解绑保底、注销级联与拦截
  - [x] 9.2 属性测试：Property 1-6
  - [x] 9.3 API/安全：auth 公开、me 需令牌、登录注册合一、注销后身份可复用
  - [x] 9.4 迁移/映射：实体与新表一致（H2 create-drop 通过），旧密码测试清理/改写
  - _需求: 全部_

## Task Dependency Graph

```json
{
  "waves": [
    { "wave": 1, "tasks": ["1"], "dependsOn": [] },
    { "wave": 2, "tasks": ["2", "6"], "dependsOn": ["1"] },
    { "wave": 3, "tasks": ["3"], "dependsOn": ["2"] },
    { "wave": 4, "tasks": ["4", "5"], "dependsOn": ["3", "6"] },
    { "wave": 5, "tasks": ["7"], "dependsOn": ["4", "5"] },
    { "wave": 6, "tasks": ["8"], "dependsOn": ["7"] },
    { "wave": 7, "tasks": ["9"], "dependsOn": ["7"] }
  ]
}
```

## Notes

- 邮件通道：复用 lodestar 的 QQ SMTP（`smtp.qq.com:587` + 授权码），`from`=`spring.mail.username`；未配置 SMTP 时用日志降级发送器，内测可直接看服务端日志里的验证码。
- 验证码存 MySQL（不引入 Redis）；防刷四件套：邮箱冷却、IP 分钟/日限流、单次消费、失败次数上限，缺一不可。
- 无密码：删除密码与锁定字段；`username` 降级为 `nickname`（仅展示）。
- 注销不做宽限期与账号合并；协作牵连先拦截，提示用户自行处理。
- 现有基于用户名/密码的鉴权测试（AuthServiceTest/AuthPropertyTest 等）将失效，随任务 9 改写。
