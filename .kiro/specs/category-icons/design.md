# Design: 分类图标库与自定义配色

## Overview

纯增量功能：扩充内置图标库、给分类加一个背景色字段、新增一个图标选择器组件，并把全 App 分类图标渲染
统一为「实色磁贴 + 白色描边图标」。不改动分类接口既有字段/错误码，不引入外部资源。

设计基线（既有资产，复用）：
- 前端 `miniapp/src/utils/icons.js`：`ICON_PATHS`（key→svg 内部标记）、`iconDataUri(key, color)`（生成
  `url("data:image/svg+xml,...")`，把描边色烘焙进 stroke）、`guessIcon(name, kind)`、`resolveIcon(icon, name, kind)`。
- 前端组件 `AppIcon`：以 `iconDataUri` 作为 background-image 渲染单色线性图标。
- 后端 `com.damien.youyu.domain.Category`（已有 `icon VARCHAR(32)`，迁移 V24）、`CategoryController`、
  名称→图标回填规则（`CategoryIcons.guess` / V24 SQL）。
- 图标资产来源：`design/category-icon-library.html` 中已设计完成的 128 枚图标（20 分组）的 SVG path 与分组表。

## Architecture

```
新建/编辑分类弹窗
  └─ IconPicker（分组 tab + 图标网格 + 配色行 + 实时预览）
        ├─ ICON_GROUPS / ICON_PATHS（utils/icons.js）
        └─ 选中 { icon, iconColor } → 提交 createCategory/updateCategory

渲染侧（记账页 / 分类管理 / 流水 / 首页猜你要记 …）
  └─ CategoryIcon 组件（容器 bg=iconColor 实色，内部 AppIcon 白色描边）

后端
  ├─ V36 迁移：categories.icon_color VARCHAR(9) NULL
  ├─ Category 实体：+ iconColor
  ├─ CategoryController：create/update 接收并净化 icon / iconColor
  └─ CategoryIcons：图标 key 白名单（与前端一致）+ hex 校验
```

## Components and Interfaces

### 1. 图标库扩充（`utils/icons.js`）
- 将 128 枚图标 path 并入 `ICON_PATHS`（沿用现有 key，新增 key 全部小写英文）。
- 新增导出 `ICON_GROUPS`：有序数组 `[{ label: '餐饮美食', keys: ['food','coffee',...] }, ...]`，供选择器分组展示。
- 新增导出 `ICON_KEY_SET`（`Set`），供前端校验/回退；后端另有等价白名单（见下）。
- `iconDataUri(key, color)` 保持不变；彩色磁贴用 `iconDataUri(key, '#ffffff')` 得到白描边图标，背景色由容器 CSS 提供。
- `DEFAULT_ICON_COLOR = '#12a150'` 导出，作为空/非法回退。

### 2. `CategoryIcon` 组件（新增，渲染侧单一入口）
- Props：`icon`（key）、`name`、`kind`（income/expense）、`color`（iconColor）、`size`。
- 逻辑：`resolvedKey = resolveIcon(icon, name, kind)`；`bg = isHex(color) ? color : DEFAULT_ICON_COLOR`。
- 渲染：圆角容器 `background: bg`，内部白色描边图标（`iconDataUri(resolvedKey, '#fff')`）。
- 替换各渲染点原先「灰底 + AppIcon」的分类图标用法（账户/成就等非分类图标不受影响）。

### 3. `IconPicker` 组件（新增，选择侧）
- Props：`v-model:icon`、`v-model:color`、`kind`。
- UI：顶部预览磁贴（bg=color + 白图标）→ 配色行（`ICON_COLORS` 调色板，≥8 色）→ 分组 tab（`ICON_GROUPS`）→ 图标网格。
- 交互：选色/选图标实时更新预览与双向绑定；不发任何请求。
- `ICON_COLORS` 常量（导出自 icons.js 或组件内）：如
  `['#12a150','#2eb8a6','#3aa0d0','#5b8def','#8b78e0','#e0609a','#e5563d','#f0a13b','#e8b93b','#8a94a6']`。

### 4. 新建/编辑分类接入
- 分类管理页（`pages/categories/categories.vue`）的新建/编辑表单接入 `IconPicker`，提交时带 `icon`、`iconColor`。
- `api/category.js` 的 create/update 增加透传 `icon`、`iconColor`。

### 5. 后端
- **迁移 V36**（`V36__category_icon_color.sql`）：
  `ALTER TABLE categories ADD COLUMN icon_color VARCHAR(9) NULL COMMENT '分类图标背景色 hex(#RRGGBB)';`
  不回填（NULL = 默认色，渲染侧兜底）。
- **`Category` 实体**：新增 `@Column(name="icon_color", length=9) private String iconColor;` + getter/setter。
- **`CategoryController` create/update**：
  - 接收可选 `icon`、`iconColor`。
  - `icon`：若非空且不在白名单 `CategoryIcons.KEYS` 内 → 置 null（由既有 `guess` 兜底）。
  - `iconColor`：若非空且不匹配 `^#[0-9a-fA-F]{6}$` → 置 null。
  - 其它字段与错误码不变；不新增错误码。
- **`CategoryIcons`**：暴露 `Set<String> KEYS`（与前端 `ICON_KEY_SET` 同集合）与 `boolean isValidColor(String)`；`guess` 规则不变。
- **分类响应 DTO**：新增 `iconColor` 字段（既有字段不动）。

## Data Models

`categories`（新增 1 列）：
| 列 | 类型 | 说明 |
|---|---|---|
| icon | VARCHAR(32) NULL | 图标 key（V24 既有） |
| icon_color | VARCHAR(9) NULL | 背景色 hex `#RRGGBB`；NULL=默认色 `#12a150` |

## Error Handling
- 非法 `icon`/`iconColor` 一律净化为 null（视为未提供），绝不抛错、不新增错误码（需求 5.2、5.3、5.4）。
- 渲染侧对空/非法值统一回退默认色与默认图标（需求 4.2、4.3）。

## Correctness Properties

### Property 1: 前后端图标 key 一致
前端 `ICON_KEY_SET` 与后端 `CategoryIcons.KEYS` 为同一集合（逐 key 相等）。
**Validates: Requirements 1.3**

### Property 2: 颜色校验幂等且安全
对任意字符串输入，后端净化后要么是合法 `#RRGGBB`、要么是 null；合法输入原样保留。
**Validates: Requirements 5.3**

### Property 3: 图标 key 净化
不在白名单内的 `icon` 净化为 null；白名单内的原样保留。
**Validates: Requirements 5.2**

### Property 4: 渲染回退全覆盖
`CategoryIcon` 对任意 `(icon, color)` 组合（含 null / 非法 / 未知 key）都产出合法背景色与合法图标 key，不抛错。
**Validates: Requirements 1.4, 4.2, 4.3**

### Property 5: 契约不回归
分类接口除新增 `iconColor` 外，字段集合与错误码集合与升级前一致；旧数据（`icon_color` 为 NULL）返回 `iconColor=null` 且各页正常渲染。
**Validates: Requirements 6.2, 6.3, 5.4**

## Testing Strategy
- 前端 vitest：`icons.js` 的 key 集合完整性、`resolveIcon` 回退、`isHex`/默认色；`ICON_GROUPS` 覆盖所有 key 且无悬空 key。
- 后端单测：`CategoryIcons.isValidColor` 与 key 白名单；`CategoryController` create/update 对非法 icon/color 的净化（`@DataJpaTest` 或 MockMvc）。
- 契约测试：分类接口新增字段、旧数据兼容、错误码不变。
- 图标资产核对：`ICON_PATHS` 与 `ICON_GROUPS` 一一对应（无缺失/无多余 key）。
