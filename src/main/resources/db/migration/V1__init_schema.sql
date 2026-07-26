-- ============================================================================
-- 有余(youyu) 初始 schema
-- MySQL 8.x / utf8mb4 / 金额一律 DECIMAL(18,2)
-- 关联需求: 2.1, 3.9, 4.11, 9.1
-- ============================================================================

-- ----------------------------------------------------------------------------
-- users 用户表
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    username            VARCHAR(64)  NOT NULL COMMENT '账号标识(登录名),去空白后 1-64',
    password_hash       VARCHAR(100) NOT NULL COMMENT 'BCrypt 加盐哈希(盐内嵌)',
    plan                VARCHAR(16)  NOT NULL DEFAULT 'free' COMMENT '套餐: free/pro/lifetime',
    plan_started_at     DATETIME     NOT NULL COMMENT '注册时刻',
    plan_expires_at     DATETIME     NOT NULL COMMENT 'plan_started_at + 365 天',
    role                VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT '角色: user/admin',
    failed_login_count  INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    locked_until        DATETIME     NULL COMMENT '锁定截止时刻',
    created_at          DATETIME     NOT NULL COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT ck_users_plan CHECK (plan IN ('free', 'pro', 'lifetime')),
    CONSTRAINT ck_users_role CHECK (role IN ('user', 'admin'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户';

-- ----------------------------------------------------------------------------
-- accounts 账户表
-- ----------------------------------------------------------------------------
CREATE TABLE accounts (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL COMMENT '归属用户',
    name             VARCHAR(50)   NOT NULL COMMENT '账户名称,去空白后 1-50',
    type             VARCHAR(20)   NOT NULL COMMENT '账户类型: CASH/BANK_CARD/ALIPAY/WECHAT/CREDIT_CARD',
    initial_balance  DECIMAL(18,2) NOT NULL COMMENT '初始余额,用于重算校验',
    current_balance  DECIMAL(18,2) NOT NULL COMMENT '当前余额,随流水更新',
    sort_order       INT           NOT NULL DEFAULT 0 COMMENT '列表排序',
    created_at       DATETIME      NOT NULL COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_accounts_user (user_id, sort_order, id),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '账户';

-- ----------------------------------------------------------------------------
-- categories 分类表
-- 唯一约束 (user_id, kind, parent_id, name);MySQL 中 NULL 不参与唯一比较,
-- 父分类(parent_id 为 NULL)重名需应用层额外校验。
-- ----------------------------------------------------------------------------
CREATE TABLE categories (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL COMMENT '归属用户',
    parent_id   BIGINT      NULL COMMENT '父分类,空=父分类',
    kind        VARCHAR(10) NOT NULL COMMENT '类型: EXPENSE/INCOME',
    name        VARCHAR(50) NOT NULL COMMENT '分类名称,去空白后 1-50',
    created_at  DATETIME    NOT NULL COMMENT '创建时间',
    updated_at  DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_categories_user_kind_parent_name UNIQUE (user_id, kind, parent_id, name),
    KEY idx_categories_user (user_id, kind, parent_id),
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT ck_categories_kind CHECK (kind IN ('EXPENSE', 'INCOME'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '分类';

-- ----------------------------------------------------------------------------
-- transactions 交易表
-- 金额恒为正,方向由 type 决定;转账单条建模。
-- 字段使用约束由应用层保证,CHECK 辅助。
-- ----------------------------------------------------------------------------
CREATE TABLE transactions (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    user_id                 BIGINT        NOT NULL COMMENT '归属用户',
    type                    VARCHAR(10)   NOT NULL COMMENT '类型: expense/income/transfer',
    amount                  DECIMAL(18,2) NOT NULL COMMENT '金额,恒为正',
    account_id              BIGINT        NULL COMMENT '支出/收入使用账户',
    source_account_id       BIGINT        NULL COMMENT '转账源账户',
    destination_account_id  BIGINT        NULL COMMENT '转账目标账户',
    category_id             BIGINT        NULL COMMENT '支出/收入分类',
    occurred_at             DATETIME      NOT NULL COMMENT '交易时间',
    note                    VARCHAR(200)  NULL COMMENT '备注,<=200',
    created_at              DATETIME      NOT NULL COMMENT '创建时间',
    updated_at              DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_tx_user_time (user_id, occurred_at),
    KEY idx_tx_account (account_id),
    KEY idx_tx_source (source_account_id),
    KEY idx_tx_dest (destination_account_id),
    KEY idx_tx_category (category_id),
    CONSTRAINT fk_tx_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_tx_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_tx_source FOREIGN KEY (source_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_tx_dest FOREIGN KEY (destination_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_tx_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_tx_type CHECK (type IN ('expense', 'income', 'transfer')),
    CONSTRAINT ck_tx_amount_positive CHECK (amount >= 0.01),
    CONSTRAINT ck_tx_fields CHECK (
        (type IN ('expense', 'income')
            AND account_id IS NOT NULL
            AND category_id IS NOT NULL
            AND source_account_id IS NULL
            AND destination_account_id IS NULL)
        OR
        (type = 'transfer'
            AND source_account_id IS NOT NULL
            AND destination_account_id IS NOT NULL
            AND source_account_id <> destination_account_id
            AND account_id IS NULL
            AND category_id IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '交易流水';
