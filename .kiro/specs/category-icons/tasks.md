# Implementation Plan: 分类图标库与自定义配色

## Overview

按设计（`design.md`）增量落地：先扩充前端图标库与后端白名单（保持前后端 key 一致），再加数据列与后端净化，
再做选择器与渲染组件，最后接入分类管理与各渲染点。纯增量、不新增错误码、不改动分类接口既有字段语义。
图标 SVG path 与分组表取自 `design/category-icon-library.html`（已设计完成，128 枚 / 20 分组）。

## Tasks

- [x] 1. 扩充前端内置图标库（`miniapp/src/utils/icons.js`）
  - 把 `design/category-icon-library.html` 中的 128 枚图标 path 并入 `ICON_PATHS`（沿用既有 key，新增 key 全小写英文，不与既有重复）
  - 新增导出 `ICON_GROUPS`（有序 `[{ label, keys[] }]`，20 个场景分组）与 `ICON_KEY_SET`（`Set`，全部 key）
  - 新增导出常量 `DEFAULT_ICON_COLOR = '#12a150'` 与 `ICON_COLORS`（调色板，≥8 色）
  - 新增 `isHexColor(s)`（`^#[0-9a-fA-F]{6}$`）；`iconDataUri`/`guessIcon`/`resolveIcon` 保持不变
  - _Requirements: 1.1, 1.2, 1.3, 3.1, 4.2, 7.1, 7.2_

- [x] 2. 后端图标 key 白名单与颜色校验（`CategoryIcons`）
  - 在 `CategoryIcons` 暴露 `Set<String> KEYS`（与前端 `ICON_KEY_SET` 同集合，逐 key 一致）
  - 新增 `boolean isValidColor(String)`（匹配 `^#[0-9a-fA-F]{6}$`）与 `String sanitizeColor(String)`（合法原样、否则 null）、`String sanitizeIcon(String)`（在 KEYS 内原样、否则 null）
  - `guess` 名称回填规则保持不变
  - _Requirements: 1.3, 5.2, 5.3_

- [x] 3. 数据列与实体
  - 新增迁移 `src/main/resources/db/migration/V36__category_icon_color.sql`：`ALTER TABLE categories ADD COLUMN icon_color VARCHAR(9) NULL COMMENT '分类图标背景色 hex(#RRGGBB)';`（不回填）
  - `Category` 实体新增 `iconColor` 字段（`@Column(name="icon_color", length=9)`）+ getter/setter
  - _Requirements: 6.1, 6.3_

- [x] 4. 分类接口接收与净化 `icon` / `iconColor`
  - 分类创建/更新请求 DTO 增加可选 `icon`、`iconColor`
  - `CategoryController`（或服务层）create/update：`icon = sanitizeIcon(icon)`、`iconColor = sanitizeColor(iconColor)` 后落库
  - 分类响应 DTO 新增 `iconColor`；既有字段与错误码集合不变、不新增错误码
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 2.3, 3.3, 6.3_

- [x] 5. 渲染组件 `CategoryIcon`（全 App 统一入口）
  - 新增 `miniapp/src/components/CategoryIcon/CategoryIcon.vue`（easycom 自动注册）
  - Props：`icon`、`name`、`kind`、`color`、`size`；`resolvedKey=resolveIcon(...)`、`bg=isHexColor(color)?color:DEFAULT_ICON_COLOR`
  - 渲染圆角实色磁贴（bg）+ 白色描边图标（`iconDataUri(resolvedKey,'#ffffff')`）
  - _Requirements: 4.1, 4.2, 4.3, 1.4_

- [x] 6. 图标选择器组件 `IconPicker`
  - 新增 `miniapp/src/components/IconPicker/IconPicker.vue`
  - `v-model:icon`、`v-model:color`、`kind`；顶部预览磁贴 + 配色行（`ICON_COLORS`）+ 分组 tab（`ICON_GROUPS`）+ 图标网格
  - 选色/选图标实时更新预览与双向绑定；不发任何请求
  - _Requirements: 2.1, 2.2, 3.1, 3.2_

- [x] 7. 接入分类管理页与 API
  - `api/category.js` create/update 透传 `icon`、`iconColor`
  - `pages/categories/categories.vue` 新建/编辑表单接入 `IconPicker`，缺省色为 `DEFAULT_ICON_COLOR`；保存带 `icon`、`iconColor`；未选图标由后端按名称兜底（需求 2.4）
  - 分类列表项改用 `CategoryIcon` 渲染
  - _Requirements: 2.1, 2.3, 2.4, 3.3, 3.4, 4.1_

- [x] 8. 各渲染点统一改用 `CategoryIcon`
  - 记账页分类网格（`pages/record/record.vue`）、首页「猜你要记」（`pages/home/home.vue`）、流水列表（`pages/index/index.vue` 等）、报表（如展示分类图标处）统一替换为 `CategoryIcon`，传入分类的 `icon` 与 `iconColor`
  - 账户/成就等非分类图标不改动
  - _Requirements: 4.1, 4.2, 4.3_

- [x] 9. Checkpoint - 前后端构建与测试通过
  - 后端 `./mvnw -q test` 通过；前端 `npm run test`、`npm run build:mp-weixin` 通过；如有问题向用户确认
  - _Requirements: 5.4, 6.3_

- [ ]* 10. 编写 `icons.js` 单元测试（vitest）
  - 断言 `ICON_KEY_SET` 大小 ≥120；`ICON_GROUPS` 覆盖的 key 全部存在于 `ICON_PATHS` 且无重复、无悬空 key
  - `isHexColor` 合法/非法样例；`resolveIcon` 未知 key 回退 receipt/income
  - _Requirements: 1.1, 1.3, 1.4_

- [ ]* 11. 编写 `CategoryIcons` 单元测试
  - `KEYS` 与前端 key 集合一致（可用固定清单对照）；`sanitizeColor`/`sanitizeIcon` 合法与非法分支
  - _Requirements: 1.3, 5.2, 5.3_

- [ ]* 12. 编写控制器契约测试（MockMvc）
  - 非法 `icon`/`iconColor` 被净化为 null（由名称推断/默认色兜底）；合法值持久化并回显；旧数据 `iconColor=null` 正常返回
  - 断言分类接口字段集合仅新增 `iconColor`、错误码集合不变
  - _Requirements: 5.2, 5.3, 5.4, 6.2, 6.3_

- [ ]* 13. 编写 `CategoryIcon` 回退测试（vitest 纯逻辑：色值与 key 解析）
  - 任意 `(icon,color)`（含 null/非法/未知 key）→ 合法背景色 + 合法图标 key，不抛错
  - _Requirements: 1.4, 4.2, 4.3_

## Notes
- 标 `*` 的任务为测试，可为更快 MVP 跳过；核心实现任务（1–9）不标记为可选。
- 前后端图标 key 集合必须一致（Property 1）；调整图标库时两侧同步。
- 纯增量：不新增错误码、不改动分类接口既有字段语义、不引入外部图标资源。

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "2", "3"] },
    { "id": 1, "tasks": ["4", "5", "10", "11"] },
    { "id": 2, "tasks": ["6", "12", "13"] },
    { "id": 3, "tasks": ["7"] },
    { "id": 4, "tasks": ["8"] },
    { "id": 5, "tasks": ["9"] }
  ]
}
```
