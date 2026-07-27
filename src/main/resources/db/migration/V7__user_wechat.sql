-- ============================================================================
-- 有余(youyu) 用户表扩展：微信小程序登录
-- 关联需求: 微信授权登录（wx.login -> jscode2session -> openid -> 签发 JWT）
--
-- 设计要点：
--   1) 新增 wx_openid / wx_unionid，支持"纯微信"用户（无账号密码）。
--   2) username / password_hash 放宽为可空：纯微信注册的用户不具备登录名与口令；
--      MySQL 的 UNIQUE 约束允许多个 NULL，故 uk_users_username 对可空 username 依然成立。
--   3) wx_openid 全局唯一（同一小程序内 openid 唯一），作为微信用户的稳定标识。
-- ============================================================================

ALTER TABLE users
    MODIFY COLUMN username      VARCHAR(64)  NULL COMMENT '账号标识(登录名),去空白后 1-64;纯微信用户可为空',
    MODIFY COLUMN password_hash VARCHAR(100) NULL COMMENT 'BCrypt 加盐哈希(盐内嵌);纯微信用户可为空';

ALTER TABLE users
    ADD COLUMN wx_openid  VARCHAR(64) NULL COMMENT '微信小程序 openid(同一小程序内唯一)' AFTER password_hash,
    ADD COLUMN wx_unionid VARCHAR(64) NULL COMMENT '微信开放平台 unionid(多端/公众号打通用)' AFTER wx_openid;

ALTER TABLE users
    ADD CONSTRAINT uk_users_wx_openid UNIQUE (wx_openid);
