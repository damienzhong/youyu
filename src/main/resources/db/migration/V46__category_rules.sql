-- ============================================================================
-- 有余(youyu) 自动分类规则(Category_Rule)：一张新表
--   category_rules  用户在导入/识别预览里改分类并勾选「以后都这样记」时写入的「商户 → 分类」规则
--
-- 自动分类按「规则 → 该账本历史习惯 → 内置商户库 → 分类名关键字 → 默认分类」的顺序取分类，
--   本表是最高优先级的那一层。merchant_key 为规整后的商户名（去括号内容、去空白、小写，<=64）。
--   同一账本、同一收支种类下一个商户只保留一条规则（唯一键），重复保存即覆盖。
--
-- 本表刻意不建外键：分类被删除后规则自然失效（分类服务读取时校验分类仍存在且种类一致）；
--   账本删除、用户注销由服务层级联清理。纯增量：只新建本表，不改动任何既有表。
-- ============================================================================
CREATE TABLE category_rules (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL COMMENT '规则创建人(注销级联清理用)',
    ledger_id    BIGINT      NOT NULL COMMENT '所属账本(分类按账本隔离)',
    kind         VARCHAR(10) NOT NULL COMMENT '收支种类:EXPENSE/INCOME',
    merchant_key VARCHAR(64) NOT NULL COMMENT '规整后的商户名',
    category_id  BIGINT      NOT NULL COMMENT '命中后使用的分类',
    created_at   DATETIME    NOT NULL,
    updated_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_rules_ledger_kind_key (ledger_id, kind, merchant_key),
    KEY idx_category_rules_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '自动分类规则(商户 → 分类,用户在导入预览里确认)';
