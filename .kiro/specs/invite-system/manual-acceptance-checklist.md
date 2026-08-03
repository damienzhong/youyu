# 邀请系统 · 手工验收清单（任务 14）

对照 `design/invite-system-prototype.html` 的 9 个页面态与 4 组 `uni.*` 交互逐条核对。

**核对方式说明（重要）**：本文档区分两类结论——
「静态可核对」指通过阅读 `.vue` / `.js` 源码确认标记、文案、状态分支与 API 调用参数确实存在且一致；
「必须真机/微信开发者工具验收」指只有在真实运行环境里才能确认的行为（分享卡片在聊天窗口的实际渲染、系统剪贴板真实内容、相册授权系统弹窗、滚动触底事件与真实后端分页）。
**本次未在真机或微信开发者工具中运行过任何用例**，第三节全部条目为待人工执行。

核对基线：`miniapp/src` 当前工作副本；`npm run test` 15 项通过、`npm run build:h5` 构建成功（本次修改后已重跑）。

---

## 一 · 静态可核对且已通过

### 1. 邀请好友页 · info 就绪态（原型「邀请好友页」主态）

| 原型元素 | 实现位置（`miniapp/src/pages/invite/invite.vue`） | 结论 |
| --- | --- | --- |
| hero 主数字 `6人` + 「位好友通过你的邀请加入有余」 | `<view v-if="infoState === 'ready'" class="hero">`，`{{ invitedCount }}` + `invitedCount > 0 ? '位好友通过你的邀请加入有余' : '还没有好友通过你的邀请加入'` | 一致 |
| hero 三栏 `7 累计邀请 / 6 有效在册 / 1 已注销` | `v-if="statsReady"` 下三个 `.sp`，取值 `total` / `invitedCount` / `invalidCount = max(0, total - invitedCount)` | 一致（见第二节偏差 D1 的修复） |
| 邀请码卡：`我的邀请码` + 8 位等宽大字 + 「复制邀请码」胶囊 | `.codebox` 内 `<text class="k">我的邀请码</text>`、`<text class="code" selectable>`、`<view class="pill" @click="copyCode">复制邀请码</view>`；样式 `font-family: Menlo, monospace; letter-spacing: 10rpx` | 一致 |
| 二维码卡 + 「微信扫码直达 · 扫码后自动带上你的邀请码」+ 「保存到相册」胶囊 | `.qrbox` 内 `<image class="qr" :src="qrSrc">`、`hint` 文案逐字相同、`<view class="pill" @click="saveQrToAlbum">保存到相册</view>`（仅 `qrState === 'ready'` 渲染，与原型降级态无该胶囊一致） | 一致 |
| 绿色主按钮「微信转发给好友」+ 幽灵按钮「复制邀请链接」 | `<button class="cta" open-type="share">微信转发给好友</button>`、`<view class="cta ghost" @click="copyLink">复制邀请链接</view>` | 一致 |
| 「邀请记录（7）」分组标题用**总条数** | `<view class="sect">邀请记录（{{ total }}）</view>` | 一致 |
| 记录行：首字头像 / 昵称 / `2026-08-01 21:14 注册` / `已注册`·`已注销` 胶囊 | `.ivt` 行内 `avatarText`、`nicknameOf`、`registerLabel`（`YYYY-MM-DD HH:mm 注册`）、`inviteStatusLabel`（`REGISTERED`→已注册、`INVALID`→已注销，`utils/invite.js`） | 一致 |
| 昵称缺失行：灰底头像 `·` + 灰色斜体「未设置昵称」/「昵称不可见」 | `avatarText` 返回 `'·'`；`nicknameOf` 对 `INVALID` 返回「昵称不可见」、其余返回「未设置昵称」；`.av.gy` + `.nm.none { color:#9aa2ad; font-style:italic }` | 一致 |
| 上拉分页 20 条 | `INVITE_PAGE_SIZE = 20`（`utils/invite.js`），`fetchInvitees(page, INVITE_PAGE_SIZE)` | 一致（真实滚动见第三节 M7） |

### 2. 邀请好友页 · info 失败态（需求 2.8）

- `infoState === 'error'` 渲染 `.fail-card`：`AppIcon name="warning"` + 「邀请信息加载失败」+ 「网络不太顺畅，稍后再试一次」+ 「重试」（`@click="loadInfo"`）。
- **邀请码 / 二维码 / 转发按钮 / 复制链接 / 链接文本五处全部以 `v-if="infoState === 'ready'"` 或 `<template v-if="infoState === 'ready'">` 包裹**，失败态不渲染任何一项，符合 2.8「不展示邀请码、邀请链接与转发入口」。
- 10 秒超时：`INFO_TIMEOUT_MS = 10000` + `withTimeout(fetchInviteInfo(), INFO_TIMEOUT_MS)`，超时走同一 `catch` 置 `error`。
- 请求序号守卫 `infoSeq`：重试时迟到的旧响应被丢弃，不会把失败态覆盖回就绪态。

### 3. 邀请好友页 · 二维码失败态（原型「二维码降级」，需求 3.8）

- `qrState` 非 `ready` 时渲染 `.qr-fail`：虚线框（`border: 1rpx dashed #d6dade`，与原型 `.qr-fail` 同款）+ 警示图标 + 「二维码暂时生成失败」+ 「重试」胶囊（`@click="loadQrCode"`）。
- 提示文案切换为「不影响转发与复制邀请码」（`v-else class="hint"`），与原型逐字相同。
- 二维码故障**不连坐**：邀请码卡、转发按钮、复制链接均只依赖 `infoState`，与 `qrState` 无耦合；`loadQrCode` 的失败只写 `qrState`。三条状态机（`infoState` / `qrState` / `listState`）各自独立，无整体 loading。

### 4. 邀请好友页 · 空状态（需求 7.14）

- `listState === 'empty'`（由 `inviteListStateAfterLoad(total)` 在 `total === 0` 时给出）渲染 `.empty`：圆形绿底 `members` 图标 + 「还没有邀请记录」+ 「把邀请卡片转发给正在记账的朋友，他注册成功后就会出现在这里。」，与原型逐字相同。
- 该分支为 `v-if`，其后的 `<template v-else>` 才渲染「邀请记录（N）」分组与列表卡，故空状态下**不渲染列表区域**。
- hero 在空状态只显示 `0 人` + 「还没有好友通过你的邀请加入」，不再显示 0/0/0 三栏（本次修复，见 D1）。

### 5. 邀请好友页 · 列表加载失败态（需求 7.12）

- `catch` 分支只写 `listState = ERROR`，`items` / `total` / `inviteCode` / `inviteLink` 一行不动 → 已加载记录与已展示的邀请码、链接保持不变。
- `.listfail` 渲染「邀请记录加载失败」+ 「重试」（`@click="loadList(lastAttemptPage)"`，重试的是失败那一页而非固定第 0 页）。
- 2 秒超时：`LIST_TIMEOUT_MS = 2000` + `withTimeout(fetchInvitees(...), LIST_TIMEOUT_MS)`。
- 停止条件：`nextInviteListRequest` 仅在 `listState === LOADED ∧ !loadingMore ∧ loaded < total` 时返回 `shouldRequest: true`（`utils/invite.js`，Property 17 已自动化覆盖该纯逻辑）。

### 6. 落地页 · `INVITER_SHOWN`（需求 4.3）

`miniapp/src/pages/invitelanding/invitelanding.vue`：

- 品牌区：`有` logo + 「有余」+ 「把钱记清楚，把日子过明白」，与原型逐字相同。
- 邀请人卡：`.l1` 「邀请你的好友」；`.l2` 昵称非空 → `<text class="em">{{ inviterNickname }}</text> 邀请你一起记账`（`.em { color:#12a150 }`，对应原型 `em` 绿色昵称）。
- **昵称为空的通用提示**：`hasNickname` 为假时渲染「有好友邀请你一起记账」，头像回落为 `友`；`loadInviterBrief` 对 `res.nickname` 做 `String(...).trim()`，`null` / `''` / 全空白统一落到空串分支。
- 绑定说明条 `.bind`（`#fff8e9` 底 + `#f6e2b8` 边，与原型同色）：「注册成功后自动记录这层邀请关系」/「无需手动填码。已有账号登录不会绑定。」逐字相同。
- 写暂存 + 查询由 `resolveInviteLanding(options, auth.isLoggedIn)` 一次定死：`shouldPersist` → `savePendingInviteCode`，`shouldQuery` → `loadInviterBrief`。

### 7. 落地页 · `DEFAULT`（需求 2.5、4.5、4.11）

- 通用欢迎卡 `.welcome`：「欢迎使用有余」+ 「登录后即可开始记账，不影响任何功能使用」，与原型同义同字（原型换行为两行）。
- 触发路径完备：参数缺失/非法（`decideInviteLanding` 的 `valid === false`）以及查询失败——`loadInviterBrief` 的 `catch` 覆盖 `NOT_FOUND` / `INVITE_RATE_LIMITED` / 网络错误 / `INVITER_QUERY_TIMEOUT_MS = 5000` 超时，统一置 `DEFAULT`。
- **不显示任何错误**：`catch` 分支内无 `uni.showToast`、无错误文案节点。
- 参数非法时不发查询、不写也不改暂存：`shouldQuery` 与 `shouldPersist` 同时为 `false`，页面无其它存储写入点；查询失败分支同样不触碰暂存（保留已写入的码与写入时刻）。

### 8. 落地页 · `LOGGED_IN`（需求 4.9）

- `.logged` 卡：「你已登录有余」+ 「邀请关系只在注册新账号时建立，你的账号已存在，本次不会绑定。」+ 「回到首页」（`@click="goHome"` → `uni.reLaunch('/pages/index/index')`），与原型逐字相同。
- 底部提示「如需邀请好友，可在「我的 → 邀请好友」生成你自己的邀请码」逐字相同。
- 置灰邀请人卡：`.inviter.dim { opacity: .55 }`，与原型 `opacity:.55` 一致。
- 不写暂存、不查询：`decideInviteLanding` 在 `isLoggedIn === true` 时返回 `shouldQuery: false, shouldPersist: false`。
- 两个登录入口在该态被整块隐藏（`v-if="!isLoggedInState"`），与原型该态只保留「回到首页」一致。

### 9. 「我的」页邀请入口（需求 2.6）

`miniapp/src/pages/me/me.vue`：

- 位置：快捷宫格之后、`groups`（「记账工具」）之前，独立 `<view class="sect">邀请</view>` + `.card`，与原型「在『记账工具』上方新增一组」一致。
- 行内容：`.r-ic t-green` + `AppIcon name="members"`、`邀请好友`、`已邀请 {{ invitedCount }} 人`、`›`；`.r-v-invite { color:#12a150; font-weight:700 }` 对应原型的品牌绿加粗。
- 点击：`@click="go('/pages/invite/invite')"` → `uni.navigateTo`。
- **人数获取失败降级**：`invitedCount` 初值 `null`，`onShow` 中 `fetchInviteInfo().catch(() => { invitedCount.value = null })`，非有限数或负数也回落 `null`；数字节点为 `v-if="invitedCount !== null"`，失败时只剩标题与箭头，且 `catch` 内无任何 toast。

### 10. `uni.*` 交互的源码接线（可静态确认的部分）

| 交互 | 源码事实 | 静态结论 |
| --- | --- | --- |
| 转发卡片 | `onShareAppMessage(() => { ... return { title: payload.title, path: payload.path } })`；`buildInviteSharePayload(inviteLink)` 返回 `title = '我在用「有余」记账，一起来试试'`（含「有余」，15 字 ≤ 30）、`path = inviteLink` | 标题与原型卡片逐字相同；`path` 取后端 `inviteLink`，后端 `InviteService.INVITE_LANDING_PATH = "/pages/invitelanding/invitelanding"` 且不做额外转义，与原型 `/pages/invitelanding/invitelanding?code=K7M2Q9XT` 同构 |
| 转发降级 | `inviteLink` 为空 → `path = INVITE_LANDING_PATH`（不带 `code`）且 `degraded = true` → `uni.showToast({ title: '邀请码尚未就绪', icon: 'none', duration: 1500 })` | 与需求 2.9 一致 |
| 剪贴板内容 | `copyText(inviteCode.value, '邀请码已复制')` / `copyText(inviteLink.value, '链接已复制')`；`uni.setClipboardData({ data: text })` 的 `data` **就是**该字符串，无拼接、无说明文字；两个值在 `loadInfo` 里已 `String(...).trim()` | 内容严格等于原文（真实剪贴板见 M3） |
| 复制提示时长 | 成功 `duration: 1500`、失败 `duration: 1500` | 与需求 2.3 的 1500ms 一致；原型 toast「邀请码已复制」文案相同 |
| 复制失败降级 | `fail()` 只 `showToast('复制失败，可长按文本手动复制')`，无跳转/无状态清空；`<text class="code" selectable>` 与 `<text class="linktext" selectable>` 保持可选取 | 与需求 2.10 一致 |
| 相册授权 | `getFileSystemManager().writeFile(base64 → USER_DATA_PATH/youyu-invite-qrcode.png)` → `uni.saveImageToPhotosAlbum`；`fail` 分支按 `/deny|auth/i` 判定授权失败 → 「需要相册权限才能保存」（与原型 toast 逐字相同），其余 → 「保存失败，请稍后重试」 | 失败分支**不修改** `qrState` / `qrBase64` / `inviteCode` / `inviteLink`，故页面展示不变、停留原页（真实授权弹窗见 M5） |
| 上拉加载 | `onReachBottom` → `nextInviteListRequest` → `loadList(next.page)`，每页 `size = 20`；`hasMoreInvitees(loaded, total)` 为假时不再发起 | 追加 20 / 到底停止的判定逻辑已由 Property 17 自动化覆盖（真实滚动 + 真实后端见 M7） |
| 页面注册 | `pages.json`：`pages/invite/invite`（`navigationBarTitleText: 邀请好友`）、`pages/invitelanding/invitelanding`（`有余邀请` + `navigationStyle: custom`） | 与原型的导航条标题、落地页无标准导航一致 |

---

## 二 · 静态核对发现的偏差

### D1（已修复）hero 三栏在空状态被渲染为 0/0/0

- **偏差**：`statsReady` 原为 `listState === LOADED || listState === EMPTY`，导致 `total = 0` 时 hero 仍渲染「0 累计邀请 / 0 有效在册 / 0 已注销」三栏。原型的「空状态 & 二维码降级」页面态 hero 只有 `0人` 与「还没有好友通过你的邀请加入」，design.md 战绩卡一行亦写明「无关系时只显示 `0 人` + 「还没有好友通过你的邀请加入」」。
- **修复**：`miniapp/src/pages/invite/invite.vue`
  ```js
  // 战绩三栏依赖列表返回的 total，故只在列表确实有记录时展示：
  // 无任何邀请关系时按原型与设计只显示「0 人 + 还没有好友通过你的邀请加入」，不渲染 0/0/0 三栏。
  const statsReady = computed(() => listState.value === INVITE_LIST_STATE.LOADED)
  ```
  （`inviteListStateAfterLoad` 保证 `LOADED` ⟺ `total > 0`，故语义等价于「有记录才显示三栏」。）
- **回归**：`npm run test` 15 项通过；`npm run build:h5` 构建成功。未改动任何测试。

### D2（保留，设计刻意）`LOGGED_IN` 态不显示邀请人昵称

- 原型该态的置灰卡片显示邀请人昵称「小林同学」；实现显示通用文案「有好友邀请你一起记账」。
- 原因：`decideInviteLanding` 在已登录时 `shouldQuery: false`（design.md 与任务 12.7 明确「LOGGED_IN 不写暂存、不发查询」），拿不到昵称。显示昵称会引入一次公开查询，与该设计决策冲突。
- 结论：**不改代码**，作为原型与设计的已知取舍记录在此。若产品要求显示昵称，需先修改 design.md 的落地页状态表再实现。

### D3（保留，与既有页面一致）落地页协议文案无可点链接

- 原型底部为「登录即表示同意 <u>用户协议</u> 与 <u>隐私政策</u>」（两个链接）；实现为纯文本「登录即表示同意用户协议与隐私政策」。
- 与既有 `pages/login/login.vue` 第 160 行完全一致，且无任何验收标准要求可点链接（需求 4.10 只要求「提供且仅提供原有两种登录方式入口」）。
- 结论：**不改代码**，属全局既有约定；协议页若单独立项再统一处理。

除以上三项，9 个页面态与 4 组交互的源码接线未发现与原型/需求冲突之处。

---

## 三 · 必须真机 / 微信开发者工具验收（无法自动化）

共 13 项（M1–M13）。执行环境：微信开发者工具（真机调试）+ 一台可登录的测试机；后端指向可控的测试环境。

### M1 · 转发卡片在聊天窗口的实际渲染

1. 用 A 账号登录，进「我的 → 邀请好友」，等 hero 与邀请码渲染完成。
2. 点「微信转发给好友」，在弹出的转发面板选一个测试会话发送。
3. 在聊天窗口查看卡片。

**预期**：卡片标题为「我在用「有余」记账，一起来试试」；开发者工具 `AppData`/网络面板或 Console 打印的 `path` 为 `/pages/invitelanding/invitelanding?code=<A 的 8 位邀请码>`，`code` 与页面上显示的邀请码逐字符相同。

### M2 · 好友点开卡片进入落地页（`code` 传参链路）

1. 用 B 账号（未注册/已退登）点击 M1 发出的卡片。

**预期**：进入落地页 `INVITER_SHOWN` 态，显示 A 的昵称 + 「邀请你一起记账」；开发者工具 Storage 面板中 `youyu_pending_invite_code` 等于该邀请码、`youyu_pending_invite_code_at` 为当前时刻毫秒数。

### M3 · 剪贴板内容严格等于原文 + 1500ms 提示

1. 邀请页点「复制邀请码」，出现 toast 后到微信聊天输入框长按粘贴。
2. 点「复制邀请链接」，同样粘贴。

**预期**：第 1 步粘贴出的文本**恰好**是 8 位邀请码，无前后空格、无「我的邀请码：」之类前缀；第 2 步粘贴出的文本恰好是 `/pages/invitelanding/invitelanding?code=XXXXXXXX`；两次 toast 文案分别为「邀请码已复制」「链接已复制」，肉眼可见时长约 1.5 秒后自动消失。

### M4 · 剪贴板写入失败的降级

1. 在开发者工具 Console 里临时覆盖：`uni.setClipboardData = (o) => o.fail && o.fail({ errMsg: 'fail' })`。
2. 点「复制邀请码」。

**预期**：toast「复制失败，可长按文本手动复制」；仍停留在邀请好友页；邀请码与链接文本仍在页面上且可长按选中复制。

### M5 · 相册授权被拒绝后停留原页且展示不变

1. 在测试机「设置 → 微信 → 相册权限」中先关闭，或首次点击时在系统弹窗选「拒绝」。
2. 邀请页点「保存到相册」。

**预期**：出现系统授权弹窗；选择拒绝后 toast「需要相册权限才能保存」；页面仍在邀请好友页，二维码图片、邀请码、邀请链接与三个分享操作全部保持原样（无空白、无刷新、无跳转）。

### M6 · 相册保存成功

1. 授予相册权限后再次点「保存到相册」。

**预期**：toast「已保存到相册」；系统相册中出现一张小程序码 PNG；用另一台手机微信扫该图片，进入落地页且 `INVITER_SHOWN` 显示同一邀请人（对应需求 3.3 的 `scene` 链路）。

### M7 · 上拉加载：追加 20 条 / 到底停止

1. 准备一个名下有 25–45 条邀请关系的测试账号（可用后台脚本造数）。
2. 登录该账号进邀请页，记录首屏条数与「邀请记录（N）」中的 N。
3. 上拉到底，观察每次追加条数与网络面板的 `/api/invite/invitees` 请求。

**预期**：首屏恰好 20 条；每次触底新增一个 `page` 递增、`size=20` 的请求，追加至多 20 条；已加载条数达到 N 后底部显示「没有更多了」，继续上拉**不再发出**任何 `/api/invite/invitees` 请求。

### M8 · info 失败态（需求 2.8）

1. 开发者工具切「离线」，或把后端 `/api/invite` 临时改为 500 / 挂起超过 10 秒。
2. 进入邀请好友页。

**预期**：页面仅显示「邀请信息加载失败 / 网络不太顺畅，稍后再试一次 / 重试」；页面上**看不到**邀请码、二维码、「微信转发给好友」「复制邀请链接」任何一项。恢复网络后点「重试」，三块内容正常出现。

### M9 · 二维码失败态（需求 3.8）

1. 让 `/api/invite/qrcode` 返回 `INVITE_QRCODE_FAILED`（最简：测试环境把 `app.wechat.appid` 置空；或同一用户 24 小时内连续请求到第 21 次触发 `INVITE_RATE_LIMITED`）。
2. 进入邀请好友页。

**预期**：二维码位置为虚线占位框 + 「二维码暂时生成失败」+ 「重试」胶囊，下方文案为「不影响转发与复制邀请码」，且**没有**「保存到相册」胶囊；邀请码、复制邀请码、复制邀请链接、微信转发四项全部正常可用（逐一点击验证）。

### M10 · 列表加载失败态（需求 7.12）

1. 先在正常网络下加载出首屏 20 条，上拉到第二页时切断网络（或让 `/api/invite/invitees` 超过 2 秒不返回）。

**预期**：出现「邀请记录加载失败 / 重试」；**已加载的 20 条一行不少**，邀请码与邀请链接仍在页面上；恢复网络后点「重试」，补上失败那一页而不是从第一页重来（网络面板确认请求的 `page` 等于失败时的页码）。

### M11 · 落地页 `DEFAULT` 与 `LOGGED_IN`

1. `DEFAULT`：在开发者工具「编译模式」里把落地页启动参数设为空、`code=ABC`、`code=IO0189A%`（含字母表外字符与畸形百分号编码）各跑一次。
2. `LOGGED_IN`：用已登录账号点击 M1 的分享卡片。

**预期**：`DEFAULT` 三次都显示「欢迎使用有余 / 登录后即可开始记账，不影响任何功能使用」+ 两个登录入口，**无任何报错提示、无白屏**，Storage 中 `youyu_pending_invite_code` 保持原值（未被写入或篡改）；网络面板无 `/api/invite/inviter` 请求。`LOGGED_IN` 显示置灰邀请人卡 + 「你已登录有余 / 邀请关系只在注册新账号时建立，你的账号已存在，本次不会绑定。」+「回到首页」+ 底部「如需邀请好友，可在「我的 → 邀请好友」生成你自己的邀请码」，且**不显示**两个登录入口；Storage 中暂存键值与进入前逐字节相同。

### M12 · 落地页昵称为空的通用提示（需求 4.3）

1. 造一个 `nickname` 为 NULL 或全空白的邀请人账号 C，取其邀请码。
2. 未登录状态下用 `code=<C 的邀请码>` 打开落地页。

**预期**：邀请人卡显示头像「友」+ 「邀请你的好友」+ 「有好友邀请你一起记账」（不出现空昵称、不出现「undefined」「null」等字样）；绑定说明条与两个登录入口正常展示。

### M13 · 「我的」页人数获取失败只显示标题与箭头（需求 2.6）

1. 让 `/api/invite` 返回 500（或离线）后进入「我的」页。

**预期**：「邀请」分组内的「邀请好友」行只显示绿色图标、标题与右侧 `›`，**没有**「已邀请 N 人」，也**没有**任何错误 toast；页面其余分组（记账工具 / 标签体系 / 关于 / 退出登录）显示正常。恢复后重新进入该页，显示「已邀请 N 人」且 N 与邀请页 hero 主数字一致。

---

## 执行记录

| 项 | 执行人 | 日期 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| M1 |  |  |  |  |
| M2 |  |  |  |  |
| M3 |  |  |  |  |
| M4 |  |  |  |  |
| M5 |  |  |  |  |
| M6 |  |  |  |  |
| M7 |  |  |  |  |
| M8 |  |  |  |  |
| M9 |  |  |  |  |
| M10 |  |  |  |  |
| M11 |  |  |  |  |
| M12 |  |  |  |  |
| M13 |  |  |  |  |
