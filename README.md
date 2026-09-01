# 有余（youyu）

> 记好每一笔，日子更有余。

面向中国用户的个人记账产品。定位：**干净**（无广告、无基础功能付费墙）、**数据自持**（随时完整导出）、**跨端顺滑**（手机与电脑一套账）。

本仓库为「有余」的独立代码库，与 `lodestar` 项目完全分离。

## 技术栈

- 后端：Spring Boot 3.4.x + Java 17 + Spring Data JPA + Spring Security
- 数据库：MySQL 8.x（`utf8mb4`），迁移用 Flyway
- 鉴权：JWT（jjwt）
- 金额：`DECIMAL(18,2)` / `BigDecimal`，全程避免浮点误差
- 时区：`Asia/Shanghai`（UTC+8）
- 测试：JUnit 5 + jqwik（属性测试）

## 后端本地运行

前置：JDK 17+、本地 MySQL（库名 `youyu`）。

```bash
# 编译
./mvnw -q compile

# 运行测试（含启动冒烟测试，使用内存数据库，无需 MySQL）
./mvnw -q test

# 启动（默认读取 application.yml 中的 MySQL 数据源，可用环境变量覆盖）
./mvnw spring-boot:run
```

可用环境变量：`YOUYU_DB_URL`、`YOUYU_DB_USER`、`YOUYU_DB_PASSWORD`、`YOUYU_PORT`、`YOUYU_JWT_SECRET`。

## 健康检查

- `GET /api/health` —— 返回服务状态与 `Asia/Shanghai` 时间
- `GET /actuator/health` —— Actuator 探活端点

## 客户端

`miniapp/` 一套 uni-app 代码产出三种形态：

| 形态 | 产出方式 | 说明 |
| --- | --- | --- |
| 微信小程序 | `cd miniapp && npm run build:mp-weixin` | 用微信开发者工具打开 `miniapp/dist/build/mp-weixin` 上传 |
| H5 / PWA | 部署在 <https://youyuji.com/app/> | 可「添加到主屏幕」装成独立应用，详见 `miniapp/README.md` |
| Android apk | `bash android/build-apk.sh` | WebView 外壳，见下 |

`android/` 是一层极薄的 WebView 外壳（纯 framework API，无 androidx 依赖，apk 约 24KB），
加载线上的 `/app/`。这样选是因为：与站点同源，请求 `/api` 无跨域问题、后端不必开 CORS；
直接继承站点已有的 Service Worker，离线能力不用在原生侧重做；改前端只要部署，apk 不用重新分发。
代价是首次启动必须联网（要先装上 Service Worker）。

构建全程在 docker 里完成，不需要本机安装 JDK 或 Android SDK：

```bash
bash android/build-apk.sh          # 产物 android/app/build/outputs/apk/release/app-release.apk
docker volume rm youyu-android-sdk # 需要时清理工具链缓存
```

## 目录结构

```
src/main/java/com/damien/youyu/
  YouyuApplication.java      # 应用入口
  api/HealthController.java  # 健康检查端点
  config/SecurityConfig.java # 安全配置（脚手架阶段仅放行健康检查）
src/main/resources/
  application.yml            # 数据源 / JPA / Flyway / 时区配置
  db/migration/              # Flyway 迁移脚本（任务 2.1 加入）
```
