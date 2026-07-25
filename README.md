# 有余（youyu）

> 记好每一笔，日子有余。

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
