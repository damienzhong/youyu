-- ============================================================================
-- 有余(youyu) 用户邀请系统：users.invite_code + invite_relations 邀请关系历史表
--
-- 邀请码：8 位，字母表 ABCDEFGHJKLMNPQRSTUVWXYZ23456789（剔除易混 I/O/0/1），
--   注册时生成，存量用户首次进邀请页惰性补齐，之后终身不变，随 users 行删除而释放。
-- 邀请关系：只在「新账号被创建的那一刻」写入，一次写定不可改绑。
--   刻意不建任何指向 users(id) 的外键：任一方注销都保留该行（悬空 id），
--   保住「谁带来谁」这条增长链路，代价是插入前需在应用层校验 inviter 存在。
-- status 仅描述被邀请人：REGISTERED（在册）/ INVALID（已注销）；邀请人注销不改任何行。
-- 本脚本不回填存量用户的 invite_code（迁移后一律为 NULL）。
-- ============================================================================
ALTER TABLE users
    ADD COLUMN invite_code VARCHAR(8)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '个人邀请码,8位,全局唯一,终身不变' AFTER nickname,
    ADD CONSTRAINT uk_users_invite_code UNIQUE (invite_code);

-- 表排序规则 utf8mb4_unicode_ci 大小写不敏感，若 CHECK 直接写 IN ('REGISTERED','INVALID')，
-- 则 'registered' 也会通过，违背「区分大小写」。故在表达式内显式 COLLATE utf8mb4_bin。
CREATE TABLE invite_relations (
    invite_id     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '邀请关系主键',
    inviter_id    BIGINT      NOT NULL COMMENT '邀请人用户id,无外键,注销后为悬空id',
    invitee_id    BIGINT      NOT NULL COMMENT '被邀请人用户id,无外键,至多一条关系',
    register_time DATETIME    NOT NULL COMMENT '被邀请人注册时刻,等于其users.created_at',
    status        VARCHAR(16) NOT NULL DEFAULT 'REGISTERED' COMMENT '关系状态:REGISTERED在册/INVALID被邀请人已注销',
    created_at    DATETIME    NOT NULL COMMENT '创建时间',
    updated_at    DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (invite_id),
    UNIQUE KEY uk_invite_relations_invitee (invitee_id),
    KEY idx_invite_relations_inviter_time (inviter_id, register_time),
    CONSTRAINT ck_invite_relations_status
        CHECK (status COLLATE utf8mb4_bin IN ('REGISTERED', 'INVALID'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户邀请关系(只追加+状态更新的历史表,无外键)';
