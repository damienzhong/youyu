-- ============================================================================
-- 有余(youyu) 连续记账(Streak)：streak_segments 历史连续区间表
--
-- 段是记账日历的派生视图,不是第二套事实源:唯一输入是 growth_events 里
--   event_type='DAILY_RECORD' 的日期集合,段边界由 GrowthCalendarService.segments 纯函数算出。
--   落表只为让历史区间能走索引分页回看(每次请求重扫全量日历再在内存里分页,成本随历史线性增长)。
-- 断一次不清零:中断只让当前段停止延长、下次记账另起一段;旧段一行不改,段总数单调不减。
--   本表刻意不提供补签/合并两段的任何入口,段只能由日历派生。
-- 不回填任何存量用户的段:迁移后本表行数为 0,各用户的段序列由其下一次结算做一次全量对账惰性建立。
--   刻意不在 SQL 里用窗口函数做 gap-and-islands 分组回填——H2(MODE=MySQL) 与 MySQL 的窗口函数
--   在排序稳定性、空集与单行分区、DATE 与整数隐式换算上行为可能不同,一旦核心不变式依赖它,
--   H2 上全绿并不能说明生产正确(与 V32 拒绝用窗口函数算连续段是同一条取舍)。
-- 刻意不建指向 users(id) 的外键:注销时由 AccountDeletionService 在同一事务内显式删除,
--   与 user_growth / growth_events / achievement_notices 同一取舍。
-- ============================================================================

CREATE TABLE streak_segments (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键(段是派生数据,id不承载业务语义)',
    user_id    BIGINT   NOT NULL COMMENT '用户id,无外键(注销时由服务层显式删除)',
    start_date DATE     NOT NULL COMMENT '该连续区间的起始日(前一日不在记账日历中)',
    end_date   DATE     NOT NULL COMMENT '该连续区间的结束日(次日不在记账日历中)',
    days       INT      NOT NULL COMMENT '段天数,等于end_date与start_date之差加1,>=1',
    created_at DATETIME NOT NULL COMMENT '该段首次落表时间(更新时不动)',
    updated_at DATETIME NOT NULL COMMENT '该段最后一次延长时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_streak_segments_user_start (user_id, start_date),
    KEY idx_streak_segments_user_days (user_id, days),
    CONSTRAINT ck_streak_segments_days CHECK (days >= 1),
    CONSTRAINT ck_streak_segments_range CHECK (end_date >= start_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '连续记账区间(记账日历的派生视图,(user_id,start_date)唯一索引承担幂等与并发兜底)';
