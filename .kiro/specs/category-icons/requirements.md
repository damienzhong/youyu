# Requirements Document

分类图标库与自定义配色（Category Icon Library & Custom Color）

## Introduction

现有分类图标仅约 40 枚、无分组、且磁贴统一为灰底灰图标，表达力弱、与竞品差距明显。本功能提供一套
**按场景分组的完整内置图标库**（≥120 枚 / ≥20 分组，统一 24×24 线性描边风格），并支持用户为每个分类
**自定义背景色**；分类图标在全 App 各处以「实色圆角磁贴 + 白色描边图标」统一呈现。

范围（纯增量、不破坏既有契约）：
- 图标资产全部为内置本地 SVG（沿用 `utils/icons.js` 的 data-URI 渲染方案，H5 / 小程序通用），不引入外链或图片资源。
- 分类表新增 `icon_color`（`icon` 列已存在，见迁移 V24），旧数据缺省回退，不做破坏性变更。
- 前后端图标 key 集合保持一致（沿用 V24 既定「先匹配先命中」的名称回填规则）。

## Glossary

- **图标 key**：内置图标的稳定标识（如 `food`/`transport`），前后端一致，存于 `categories.icon`。
- **图标分组**：按消费场景聚合的图标集合（如「餐饮美食」「交通出行」），仅用于选择器展示，不入库。
- **图标背景色**：分类磁贴的实色背景（hex `#RRGGBB`），存于 `categories.icon_color`；为空表示用默认色。
- **默认色**：品牌绿 `#12a150`，`icon_color` 为空/非法时的回退。
- **彩色磁贴**：圆角容器以 `icon_color` 实色填充 + 白色描边图标的统一呈现形式。

## Requirements

### Requirement 1: 完整分组图标库

**User Story:** 作为记账用户，我想从丰富且分场景的图标里挑选，以便分类更贴切、更好看。

#### Acceptance Criteria
1. THE 系统 SHALL 内置不少于 120 枚图标，覆盖不少于 20 个消费场景分组。
2. THE 图标 SHALL 统一为 24×24 viewBox、单色描边、无填充的线性风格。
3. THE 前端图标 key 集合 SHALL 与后端校验用的 key 集合完全一致。
4. WHERE 某图标 key 不存在于图标库，THE 渲染 SHALL 回退到 `receipt`（支出）或 `income`（收入）默认图标，且不报错。

### Requirement 2: 分类图标选择

**User Story:** 作为记账用户，我想在新建/编辑分类时按分组挑图标，以便快速找到想要的图标。

#### Acceptance Criteria
1. WHEN 用户新建或编辑分类，THE 系统 SHALL 提供按场景分组浏览、并可在组间切换的图标选择器。
2. WHEN 用户在选择器中选中某图标，THE 选择器 SHALL 实时预览「当前背景色 + 所选图标」的彩色磁贴。
3. WHEN 用户保存分类，THE 系统 SHALL 持久化所选图标 key 到 `categories.icon`。
4. IF 用户未选择任何图标，THEN THE 系统 SHALL 按分类名称关键字推断默认图标（沿用既有 `guessIcon` 规则）。

### Requirement 3: 自定义背景色

**User Story:** 作为记账用户，我想给分类选背景色，以便一眼区分不同分类。

#### Acceptance Criteria
1. WHEN 用户新建或编辑分类，THE 系统 SHALL 提供一组可选背景色（调色板，不少于 8 色）。
2. WHEN 用户选中某背景色，THE 预览磁贴 SHALL 立即以该色渲染。
3. WHEN 用户保存分类，THE 系统 SHALL 持久化所选背景色到 `categories.icon_color`（hex `#RRGGBB`）。
4. IF 用户未选择背景色，THEN THE 系统 SHALL 使用默认色 `#12a150`。

### Requirement 4: 全 App 统一呈现

**User Story:** 作为记账用户，我希望分类图标在所有页面呈现一致，以便体验统一。

#### Acceptance Criteria
1. THE 系统 SHALL 在记账页分类网格、分类管理页、流水列表、首页「猜你要记」等所有展示分类图标处，以彩色磁贴（`icon_color` 实色底 + 白色描边图标）统一渲染。
2. WHERE 分类的 `icon_color` 为空或非法 hex，THE 渲染 SHALL 使用默认色 `#12a150`。
3. WHERE 分类的 `icon` 为空，THE 渲染 SHALL 按名称推断图标（需求 2.4）。

### Requirement 5: 后端契约与校验

**User Story:** 作为系统，我需要安全地接收并校验图标与配色，以便数据可靠且不被污染。

#### Acceptance Criteria
1. THE 分类创建/更新接口 SHALL 接收可选的 `icon`（字符串）与 `iconColor`（字符串）字段。
2. IF `icon` 不在内置图标 key 白名单内，THEN THE 后端 SHALL 将其视为未提供（置空，由名称推断兜底），不报错。
3. IF `iconColor` 不匹配 `^#[0-9a-fA-F]{6}$`，THEN THE 后端 SHALL 将其视为未提供（置空 = 默认色），不报错。
4. THE 本功能 SHALL NOT 新增任何错误码，且 SHALL NOT 改动分类接口的其它既有字段与语义。

### Requirement 6: 迁移与向后兼容

**User Story:** 作为既有用户，我希望升级后旧分类照常显示，以便不受影响。

#### Acceptance Criteria
1. THE 迁移 SHALL 为 `categories` 新增 `icon_color VARCHAR(9) NULL`（迁移版本 V36），不回填、不改动既有列。
2. WHERE 既有分类 `icon_color` 为 NULL，THE 渲染 SHALL 使用默认色，行为与升级前视觉一致（灰改绿属预期升级）。
3. THE 既有分类接口的返回 SHALL 仅新增 `iconColor` 字段，既有字段与错误码集合保持不变。

### Requirement 7: 纯本地、跨端一致

**User Story:** 作为系统，我需要图标在 H5 与小程序两端一致渲染，以便体验统一且无外部依赖。

#### Acceptance Criteria
1. THE 图标 SHALL 以本地 SVG data-URI 作为 background-image 渲染，不依赖任何网络图片或外链图标库。
2. THE 彩色磁贴的白色描边变体 SHALL 由既有 `iconDataUri` 以指定描边色生成，H5 与微信小程序均正常显示。
