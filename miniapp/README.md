# 有余 · 微信小程序端（uni-app）

基于 uni-app（Vue 3）的小程序端，复用后端同一套 JWT API。当前为骨架：微信一键登录打通登录态，首页为占位。

## 技术栈

- uni-app 3.x（Vue 3 + Vite 5）
- pinia 状态管理
- 目标平台：微信小程序（`mp-weixin`），H5 端可用于本地联调

## 本地运行

```bash
cd miniapp
npm install

# 配置后端地址
cp .env.example .env      # 修改 VITE_API_BASE 指向你的后端 /api

# H5 端联调（浏览器）
npm run dev:h5

# 微信小程序端：产物输出到 dist/dev/mp-weixin，用微信开发者工具打开该目录
npm run dev:mp-weixin
```

微信开发者工具中需在 `src/manifest.json` 的 `mp-weixin.appid` 填入你的小程序 AppID，
并在微信公众平台「开发管理-开发设置-服务器域名」把后端域名加入 request 合法域名（须 HTTPS）。

## 登录流程

1. 登录页点击「微信一键登录」→ `uni.login()` 取一次性 `code`
2. `POST /api/auth/wx-login { code }` → 后端换 openid、找到/创建用户、签发 JWT
3. token 落地本地存储，后续请求由 `utils/request.js` 自动带 `Authorization`

## 安装到手机（PWA）

H5 产物部署在 <https://youyuji.com/app/>，已配好 manifest 与 Service Worker，可当作独立应用装到桌面：

- **iPhone / iPad**：Safari 打开 <https://youyuji.com/app/> → 分享 → 添加到主屏幕。
  必须用 Safari，Chrome/微信内置浏览器装不了。
- **Android**：Chrome 打开同一地址 → 菜单 → 安装应用 / 添加到主屏幕。
  长按图标可直达「记一笔」「账本」（manifest 的 shortcuts）。

装好后有独立图标、无地址栏、断网可打开（读缓存），登录用邮箱验证码那条路径即可。

相关文件（都在 `public/`，由 vite 原样拷到产物根，线上即 `/app/` 下）：

```
public/
  manifest.json      PWA 清单：scope/start_url 均为 /app/，图标、快捷方式
  sw.js              Service Worker：HTML 联网优先、assets 缓存优先、/api/ 不拦截
  icons/             图标（矢量源 icon.svg + 渲染出的 192/512/maskable/apple-touch）
```

两条硬约束，改动前务必留意：

1. **SW 作用域必须限定 `/app/`**。历史版本曾在根作用域 `/` 注册 SW，缓存整套旧应用并把导航
   回退到旧 `index.html`，把同源的 `/app/` 拦掉了；`web/public/sw.js` 是善后用的自注销脚本。
   本 SW 靠「文件放在 `/app/sw.js`」天然获得该作用域，不要挪位置、不要放宽 scope。
2. **`/api/` 一律不缓存**。金额与账本是强一致数据，缓存会造成脏读。

清单用 `.json` 而非规范推荐的 `.webmanifest`：生产 nginx 为 1.18.0，其 `mime.types` 还没有
`webmanifest` 条目（1.21.5 才加），否则会以 `application/octet-stream` 返回。

## 目录

```
src/
  main.js            应用入口（挂载 pinia）
  App.vue            根组件
  manifest.json      uni-app 应用配置（小程序 appid）
  pages.json         页面路由注册
  utils/config.js    API base 与存储键
  utils/request.js   请求封装（带 token、401 处理）
  stores/auth.js     登录态与微信登录动作
  api/auth.js        鉴权接口封装
  pages/login        微信登录页
  pages/index        登录后首页占位
```

## 后续接入

- 出原生 App：同一套代码 `uni build -p app`，但 uni-app CLI 在 App 平台只能产出离线打包用的
  wgt 资源包，要出 apk/ipa 仍需 HBuilderX 云打包。届时至少要处理三处：
  `pages/data/data.vue`、`pages/billimport/billimport.vue` 里的 `wx.chooseMessageFile` 用的是
  `#ifndef H5`，App 端会进这个分支但没有 `wx` 对象（应改判 `#ifdef MP-WEIXIN` 并补 App 分支）；
  `manifest.json` 需补 `app-plus` 节；微信一键登录需接微信开放平台 SDK（或只留邮箱验证码登录）。
