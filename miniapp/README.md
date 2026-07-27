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

- 账户 / 交易 / 分类 / 报表页面，复用后端对应接口
- tabBar 导航、下拉刷新、分页加载
- 出 App 时同一套代码 `uni build -p app` 编译
