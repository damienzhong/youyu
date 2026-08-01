-- ============================================================================
-- 有余(youyu) 分类图标统一：分类新增 icon 字段
-- MySQL 8.x / utf8mb4
--
-- 背景：
--   分类图标原先由前端按名称启发式映射 emoji，风格杂、跨端不一致。改为内置线性图标集，
--   分类持久化图标 key（用户可在新建/编辑分类时选择）。
--
-- 本迁移：
--   1. categories 增加 icon VARCHAR(32) NULL。
--   2. 按名称关键字回填为最接近的内置图标 key；未命中按种类给默认（支出 receipt / 收入 income）。
--      关键字与顺序须与 CategoryIcons.guess / 前端 icons.js 保持一致（先匹配先命中）。
-- ============================================================================

ALTER TABLE categories
    ADD COLUMN icon VARCHAR(32) NULL COMMENT '图标 key(内置线性图标集)';

-- 按名称关键字回填（CASE 先命中先生效，顺序与服务层一致）。
UPDATE categories SET icon = CASE
    WHEN name REGEXP '餐饮|吃|饭|外卖|美食|聚餐|零食|饮|咖啡|奶茶' THEN 'food'
    WHEN name REGEXP '交通|地铁|公交|打车|出行|车|油|加油|停车|高铁' THEN 'transport'
    WHEN name REGEXP '购物|买|衣|鞋|服饰|数码|电器|日用' THEN 'shopping'
    WHEN name REGEXP '娱乐|游戏|电影|玩|唱|运动|健身' THEN 'entertainment'
    WHEN name REGEXP '居住|房租|房贷|物业|水电|燃气|家居' THEN 'home'
    WHEN name REGEXP '医疗|药|医院|健康|体检' THEN 'medical'
    WHEN name REGEXP '教育|学习|书|培训|课|学费' THEN 'education'
    WHEN name REGEXP '通讯|话费|网费|流量|手机|宽带' THEN 'communication'
    WHEN name REGEXP '旅行|旅游|酒店|机票|景点' THEN 'travel'
    WHEN name REGEXP '宠物' THEN 'pet'
    WHEN name REGEXP '工资|薪|奖金|报销|劳务|兼职' THEN 'salary'
    WHEN name REGEXP '理财|利息|收益|投资|分红|基金|股票' THEN 'invest'
    WHEN name REGEXP '红包|礼金|转赠|人情' THEN 'redpacket'
    WHEN name REGEXP '退款|返现' THEN 'refund'
    WHEN kind = 'INCOME' THEN 'income'
    ELSE 'receipt'
END;
