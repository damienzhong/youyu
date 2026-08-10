-- ============================================================================
-- 有余(youyu) 用户性别与头像颜色
-- gender：'MALE' / 'FEMALE'，NULL 表示保密；仅展示，不做任何功能门控。
-- avatar_color：用户自选头像颜色（十六进制，如 '#0ea5e9'），用于家庭（协作）账本中
--   区分不同记账人的首字头像；NULL 时前端回退品牌绿。
-- 纯增量、可整块摘除：删除这两列即可回收，既有代码不依赖它们。
-- ============================================================================
ALTER TABLE users
    ADD COLUMN gender VARCHAR(8) NULL COMMENT '性别 MALE/FEMALE,NULL=保密(仅展示)';

ALTER TABLE users
    ADD COLUMN avatar_color VARCHAR(16) NULL COMMENT '头像颜色(十六进制),用于家庭账本区分记账人';
