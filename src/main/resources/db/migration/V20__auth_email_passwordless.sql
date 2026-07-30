-- ============================================================================
-- 有余(youyu) 认证重构：邮箱验证码 + 无密码
-- MySQL 8.x / utf8mb4
-- 关联需求: 4（账号身份模型-无密码）, 9（数据模型迁移）
--
-- 目标：
--   1. users 增加 email（全局唯一、可空）与 nickname（可空、可重复，回填自旧 username）。
--   2. users 移除密码及登录锁定相关字段：password_hash / failed_login_count / locked_until。
--   3. users 移除 username 及其唯一键（降级为展示用 nickname，已回填）。
--   4. 新增 verification_code 表（验证码存 MySQL，含用途/过期/单次消费/失败计数/IP）。
--
-- 内测阶段数据库已清空、无真实用户，故不做历史账号合并；nickname 回填仅为完整性。
-- 约束：email 全局唯一、wx_openid 全局唯一（V7 已建 uk_users_wx_openid）。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 新增 email（全局唯一、可空）与 nickname（可空、可重复），并回填 nickname。
--    MySQL 的 UNIQUE 约束允许多个 NULL，故可空 email 的全局唯一依然成立。
-- ----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN email    VARCHAR(255) NULL COMMENT '邮箱身份(全局唯一,可空);与 wx_openid 至少其一' AFTER id,
    ADD COLUMN nickname VARCHAR(64)  NULL COMMENT '昵称(展示用,可空可重复,不用于登录)' AFTER email;

-- 回填 nickname：沿用旧 username 作为初始展示名。
UPDATE users SET nickname = username WHERE nickname IS NULL AND username IS NOT NULL;

-- email 全局唯一。
ALTER TABLE users
    ADD CONSTRAINT uk_users_email UNIQUE (email);

-- ----------------------------------------------------------------------------
-- 2. 移除 username 及其唯一键（降级为 nickname，已回填）。
-- ----------------------------------------------------------------------------
ALTER TABLE users DROP INDEX uk_users_username;
ALTER TABLE users DROP COLUMN username;

-- ----------------------------------------------------------------------------
-- 3. 移除密码与登录锁定相关字段（整体无密码化）。
-- ----------------------------------------------------------------------------
ALTER TABLE users
    DROP COLUMN password_hash,
    DROP COLUMN failed_login_count,
    DROP COLUMN locked_until;

-- ----------------------------------------------------------------------------
-- 4. verification_code 验证码表。
--    purpose: LOGIN(登录/注册) / BIND(绑定邮箱) / DELETE(注销二次验证)。
--    单次消费(consumed)、失败累计(attempt_count)、过期(expires_at)、IP 限流(ip)。
-- ----------------------------------------------------------------------------
CREATE TABLE verification_code (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL COMMENT '目标邮箱',
    purpose       VARCHAR(16)  NOT NULL COMMENT '用途: LOGIN/BIND/DELETE',
    code          VARCHAR(8)   NOT NULL COMMENT '验证码(6 位数字)',
    expires_at    DATETIME     NOT NULL COMMENT '过期时刻(+10 分钟)',
    consumed      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已消费/失效',
    attempt_count INT          NOT NULL DEFAULT 0 COMMENT '校验失败累计次数',
    ip            VARCHAR(45)  NULL COMMENT '请求来源 IP(限流/审计)',
    created_at    DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_vc_email_purpose (email, purpose, id),
    KEY idx_vc_ip_created (ip, created_at),
    CONSTRAINT ck_vc_purpose CHECK (purpose IN ('LOGIN', 'BIND', 'DELETE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '邮箱验证码';
