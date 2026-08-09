# 账户品牌图标 · 资源包规格与素材清单

> 目标：账户图标「品牌可认」。第三方 App / 银行显示**真实官方 Logo**（随包内置，不依赖运行时外网）；
> 资源包内缺该项时，回退「品牌色圆徽 + 简称」（App）或线性图标（通用类型），保证永不空图标。
>
> 本文档为落地前的评审件。真实 Logo 矢量/位图素材需按第 4 节从**官方渠道**获取放入资源包；
> 代码与兜底逻辑见第 5–6 节。

## 1. 策略分层

| 层 | 覆盖 | 呈现 |
|---|---|---|
| A 真实 Logo（资源包） | 微信/支付宝/QQ/京东/云闪付/花呗/白条/数字人民币；主流银行 | 官方 Logo（`<image>`） |
| B 品牌字徽（兜底） | 资源包缺失的 App / 中小银行 | 品牌色圆徽 + 简称（现有 `BANKS`/字徽） |
| C 线性图标（自绘） | 现金/公积金/医保/公交卡/饭卡/会员卡/押金/股票/基金/理财/虚拟货币… | `utils/icons.js` 描边图标 |

## 2. 目录与命名

uni-app 静态资源放 `miniapp/src/static/`：

```
miniapp/src/static/brand/
  manifest.json         # 映射与元数据（见第 3 节）
  apps/                 # 第三方 App 品牌 Logo
    wechat.svg  alipay.svg  qq.svg  jd.svg  unionpay.svg
    huabei.svg  baitiao.svg  ecny.svg
  banks/                # 银行 Logo（文件名 = slug）
    icbc.svg  abc.svg  boc.svg  ccb.svg  bocom.svg  psbc.svg
    cmb.svg   cmbc.svg citic.svg ceb.svg  spdb.svg  cib.svg
    pab.svg   cgb.svg  hxb.svg   cbhb.svg bob.svg   bosc.svg
    nbcb.svg  jsb.svg  ...
```

命名规则：全小写、稳定 slug（不随中文名变化）。**优先 SVG**（矢量、随包体积小）；只有位图时用 PNG，透明底、正方形安全区、边长 ≥120px（@3x）。

## 3. manifest.json（映射，供评审）

```json
{
  "apps": {
    "WECHAT":       { "file": "apps/wechat.svg",   "bg": "#07C160", "short": "微", "fit": "tile" },
    "ALIPAY":       { "file": "apps/alipay.svg",   "bg": "#1677FF", "short": "支", "fit": "tile" },
    "QQ_WALLET":    { "file": "apps/qq.svg",       "bg": "#1296DB", "short": "Q",  "fit": "tile" },
    "JD_FINANCE":   { "file": "apps/jd.svg",       "bg": "#E1251B", "short": "京", "fit": "tile" },
    "HUABEI":       { "file": "apps/huabei.svg",   "bg": "#1677FF", "short": "花", "fit": "tile" },
    "JD_BAITIAO":   { "file": "apps/baitiao.svg",  "bg": "#E1251B", "short": "白", "fit": "tile" },
    "DIGITAL_RMB":  { "file": "apps/ecny.svg",     "bg": "#C1272D", "short": "¥",  "fit": "plain" }
  },
  "banks": {
    "工商银行": { "slug": "icbc",  "short": "工", "color": "#c7000b" },
    "农业银行": { "slug": "abc",   "short": "农", "color": "#00954c" },
    "中国银行": { "slug": "boc",   "short": "中", "color": "#b01c2e" },
    "建设银行": { "slug": "ccb",   "short": "建", "color": "#005baa" },
    "交通银行": { "slug": "bocom", "short": "交", "color": "#004a9f" },
    "邮储银行": { "slug": "psbc",  "short": "邮", "color": "#00713c" },
    "招商银行": { "slug": "cmb",   "short": "招", "color": "#c7000b" },
    "民生银行": { "slug": "cmbc",  "short": "民", "color": "#0a8a3c" },
    "中信银行": { "slug": "citic", "short": "信", "color": "#c8102e" },
    "光大银行": { "slug": "ceb",   "short": "光", "color": "#6f2c91" },
    "浦发银行": { "slug": "spdb",  "short": "浦", "color": "#003a70" },
    "兴业银行": { "slug": "cib",   "short": "兴", "color": "#1a4f9c" },
    "平安银行": { "slug": "pab",   "short": "平", "color": "#e60012" },
    "广发银行": { "slug": "cgb",   "short": "广", "color": "#e60012" },
    "华夏银行": { "slug": "hxb",   "short": "华", "color": "#c8102e" },
    "渤海银行": { "slug": "cbhb",  "short": "渤", "color": "#1b4a9c" },
    "北京银行": { "slug": "bob",   "short": "京", "color": "#c8102e" },
    "上海银行": { "slug": "bosc",  "short": "沪", "color": "#005ba1" },
    "宁波银行": { "slug": "nbcb",  "short": "甬", "color": "#d40f2b" },
    "江苏银行": { "slug": "jsb",   "short": "苏", "color": "#007a4d" }
  }
}
```

> `short`/`color` 与现有 `api/account.js` 的 `BANKS` 一致，直接作为兜底字徽来源（缺 Logo 时用）。
> 其余中小行沿用 `BANKS` 全量清单，默认走字徽，可后续补 Logo。

## 4. 素材来源与授权（须逐项获取）

**获取原则**：只用各品牌**官方对外发布**的标识素材，遵循其品牌/VI 规范，不改形改色（背景色块除外）。

- 微信 / 微信支付：微信开放平台 · 设计资源 / 微信支付「商户 Logo 使用规范」。
- 支付宝 / 花呗：支付宝开放平台 · 品牌与设计规范。
- QQ / QQ 钱包：腾讯 QQ 品牌资源。
- 京东 / 京东白条：京东开放平台 · 品牌规范。
- 云闪付 / 银联：中国银联品牌中心对外标识规范。
- 数字人民币（e-CNY）：数字人民币官方标识规范。
- 各银行：各行官网（页脚「品牌」/「标识下载」/VI 手册）提供的对外标识；或其对外《标识使用规范》。

**授权/商标提示**：以上均为各权利人商标。此处为「标识用户自己持有的对应账户」的**指示性合理使用**（与竞品做法一致），非品牌联合/背书。请保留素材出处与版本；若某品牌规范禁止内置，则该项仅用字徽兜底。

## 5. 渲染与兜底

- 统一账户徽标组件读取 `manifest.json`：
  - App 类型：有 `file` → `fit=tile`（品牌色块 + 白/原色 Logo）或 `fit=plain`（浅底原色 Logo）；否则字徽（`bg`+`short`）。
  - 银行卡（`BANK_CARD`/`CREDIT_CARD`）：按 `issuingBank` 取 `banks[label].slug` → `banks/<slug>.svg`；缺失回退 `color`+`short` 圆徽。
  - 通用类型：走 `utils/icons.js` 线性图标（本次新增 candles/moneybag/cross 等）。
- **加载失败兜底**：`<image>` 用 `binderror`（小程序）/`onerror`（H5）切换到字徽，避免出现「占位地球」这类误显。
- 资源包内**只放确实拿到的官方素材**；没有的条目不放文件，运行时自然走字徽——这正是「有真标显真标、无则字徽」的期望行为。

## 6. 代码接入改动点（落地时）

| 文件 | 改动 |
|---|---|
| `src/static/brand/*` | 新增资源包 + `manifest.json` |
| `utils/icons.js` | 新增线性图标 key：`candles`/`moneybag`/`cross`/`creditcard` 等 |
| 新增 `components/AccountBadge` | 统一账户徽标：真实 Logo → 字徽 → 线性图标 三级；含加载失败兜底 |
| `pages/accounts`、`AccountTypeSheet`、`accountdetail`、记账账户选择、资产列表 | 用 `AccountBadge` 替换现有 `AppIcon(accountTypeIcon)` |
| `api/account.js` | `BANKS` 增加 `slug` 字段；`accountTypeIcon` 保留为线性兜底 |

## 7. 交付清单（勾选）

App（8）：☐ wechat ☐ alipay ☐ qq ☐ jd ☐ unionpay ☐ huabei ☐ baitiao ☐ ecny
主流银行（20）：☐ icbc ☐ abc ☐ boc ☐ ccb ☐ bocom ☐ psbc ☐ cmb ☐ cmbc ☐ citic ☐ ceb ☐ spdb ☐ cib ☐ pab ☐ cgb ☐ hxb ☐ cbhb ☐ bob ☐ bosc ☐ nbcb ☐ jsb
（其余中小行：默认字徽，后续按需补）
