-- ============================================================================
-- 有余(youyu) 协作账本 —— 阶段A：账本成员与邀请
-- MySQL 8.x / utf8mb4（H2 dev 走 MySQL 兼容模式）
--
-- 设计：账本访问从「仅归属者」升级为「成员可访问」。每个账本有一个 OWNER 成员（创建者），
-- 协作账本可通过邀请码加入 EDITOR 成员。成员关系是访问控制的唯一真源：
--   - 可访问(读写流水/分类/账户/预算)：任一成员(OWNER/EDITOR)
--   - 仅 OWNER：改名/删除/邀请/移除成员
-- 独立账本(INDEPENDENT)仍只有创建者一个 OWNER 成员，行为不变。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- ledger_members 账本成员表
-- ----------------------------------------------------------------------------
CREATE TABLE ledger_members (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    ledger_id  BIGINT      NOT NULL COMMENT '账本',
    user_id    BIGINT      NOT NULL COMMENT '成员用户',
    role       VARCHAR(16) NOT NULL DEFAULT 'EDITOR' COMMENT '成员角色: OWNER / EDITOR',
    created_at DATETIME    NOT NULL COMMENT '加入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_member (ledger_id, user_id),
    KEY idx_member_user (user_id),
    CONSTRAINT fk_lm_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers (id),
    CONSTRAINT fk_lm_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '账本成员';

-- 回填：每个现有账本的创建者成为 OWNER 成员。
INSERT INTO ledger_members (ledger_id, user_id, role, created_at)
SELECT id, user_id, 'OWNER', NOW() FROM ledgers;

-- ----------------------------------------------------------------------------
-- ledger_invites 账本邀请码表（仅协作账本使用）
-- ----------------------------------------------------------------------------
CREATE TABLE ledger_invites (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32) NOT NULL COMMENT '邀请码(全局唯一)',
    ledger_id  BIGINT      NOT NULL COMMENT '账本',
    created_by BIGINT      NOT NULL COMMENT '创建邀请的用户',
    expires_at DATETIME    NOT NULL COMMENT '过期时间',
    created_at DATETIME    NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_code (code),
    KEY idx_invite_ledger (ledger_id),
    CONSTRAINT fk_li_ledger FOREIGN KEY (ledger_id) REFERENCES ledgers (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '账本邀请码';
