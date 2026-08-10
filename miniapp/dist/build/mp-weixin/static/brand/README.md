# 账户品牌图标资源包

把**官方** Logo 放到这里，文件名用下方 slug，账户徽标会自动显示真标；缺文件则回退「品牌色字徽 / 线性图标」（无需改代码）。

## 放置位置与命名（优先 SVG；位图用透明底、正方形、≥120px）

- 第三方 App：`apps/<slug>.svg`
  - `wechat` `alipay` `qq` `jd` `unionpay` `huabei` `baitiao` `ecny` `bitcoin`
- 银行：`banks/<slug>.svg`
  - `icbc` `abc` `boc` `ccb` `bocom` `psbc` `cmb` `cmbc` `citic` `ceb`
    `spdb` `cib` `pab` `cgb` `hxb` `cbhb` `bob` `bosc` `nbcb` `jsb`

映射见 `manifest.json`；slug 与 `src/utils/brand.js`（App）、`src/api/account.js` 的 `BANK_SLUG`（银行）一致。

## 素材来源（须官方、遵循各品牌标识规范）

- App：微信/支付宝/京东/腾讯 各开放平台品牌资源；银联品牌中心；数字人民币官方标识。
  - 微信/支付宝/QQ/银联/比特币等在开源 **Simple Icons**（图标代码 CC0）可直接下载。
- 银行：各行官网「品牌 / 标识规范」页的对外标识。

## 合规

各 Logo 商标权归各自所有者，此处为「标识用户本人对应账户」的指示性使用，非品牌联合/背书。请遵循各品牌标识规范；规范禁止内置的，仅用字徽兜底即可。
