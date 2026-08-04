# 有余(youyu) 部署指南

架构：**Nginx**（对外 80/443，托管前端静态 + 反代 `/api`）→ **Spring Boot**（`youyu.jar`，监听 `127.0.0.1:8090`）→ **MySQL 8**。

前端为两套同源静态产物，一起打包部署到 `/opt/youyu/web`：

- **根路径 `/`**：营销落地站（`web/`，Vue 静态站点）。
- **子路径 `/app/`**：记账应用本体（`miniapp/` 的 uni-app **H5** 产物，与微信小程序同一套代码）。

后端是可执行 jar，与前端分开部署。Flyway 在后端启动时自动建表/迁移，无需手动导入 SQL。

> 端口用 8090，避免与同机的 lodestar(8080) 冲突。

## 一、准备一台服务器

- Linux（Ubuntu 22.04 / Debian 12 / 等），公网 IP，最低 1C2G 即可。
- 装好：**JDK 17+**、**MySQL 8**、**Nginx**、**git**（本地还需 **Node 20+** 和 JDK 构建）。

## 二、首次初始化（服务器上，执行一次）

```bash
# 把仓库拉到服务器（用于拿 deploy 脚本），或只上传 deploy/ 目录亦可
sudo mkdir -p /opt/youyu/source && sudo chown $USER /opt/youyu/source
git clone <你的 youyu 仓库地址> /opt/youyu/source

sudo bash /opt/youyu/source/deploy/setup-server.sh
```

脚本会：建系统用户 `youyu`、建 `/opt/youyu/{app,logs,web,source}`、生成 `env.conf` 模板、注册 systemd 服务。之后按脚本末尾提示：

1. **改环境变量**：`sudo vim /opt/youyu/app/env.conf`
   - `YOUYU_DB_PASSWORD`：数据库密码
   - `YOUYU_JWT_SECRET`：`openssl rand -base64 48` 生成的长随机串（**务必改**，否则令牌可被伪造）
2. **建库建账号**：
   ```sql
   CREATE DATABASE youyu CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'youyu'@'localhost' IDENTIFIED BY '你的强密码';
   GRANT ALL ON youyu.* TO 'youyu'@'localhost'; FLUSH PRIVILEGES;
   ```
3. **配 Nginx**：
   ```bash
   sudo cp /opt/youyu/source/deploy/nginx-youyu.conf /etc/nginx/conf.d/youyu.conf
   sudo vim /etc/nginx/conf.d/youyu.conf   # 改 server_name
   sudo nginx -t && sudo systemctl reload nginx
   ```

## 三、发布（每次上线）

在**本地开发机**构建并上传产物：

```bash
# 只构建
bash deploy/build-local.sh
# 构建 + 自动 rsync 到服务器
DEPLOY_SSH=root@你的服务器IP bash deploy/build-local.sh
```

到**服务器**执行部署（备份旧 jar → 换新 → 重启 → 换前端 → reload nginx）：

```bash
sudo bash /opt/youyu/source/deploy/deploy.sh
```

## 四、验证

```bash
curl -s http://127.0.0.1:8090/api/health        # 后端存活
curl -s http://你的域名/api/health              # 经 nginx
# 浏览器打开 http://你的域名        → 营销落地页
# 浏览器打开 http://你的域名/app/   → 记账应用（邮箱验证码 / 微信登录）
```

## 五、HTTPS（强烈建议）

登录涉及邮箱验证码与 JWT，务必上 HTTPS：

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

certbot 会自动改 nginx 配置并续期。微信小程序端要求后端为 HTTPS，正式联调前务必先配好证书。

## 本地直连测试库调试（可选）

想在本地改代码即时调试、又用测试服务器上的真实数据，可让本地后端直连测试库：

```bash
cp deploy/dev-remote-db.conf.example deploy/dev-remote-db.conf   # 首次，填服务器 IP 与 DB 口令
bash deploy/dev-remote-db.sh                                     # 本地后端连测试库启动（默认 8080）
```

前提：测试服务器的 MySQL 3306 已对你的机器放行，且 DB 账号允许从你的来源主机连接（如 `'youyu'@'%'`）。
前端联调把 `VITE_API_BASE` 指到本地 `http://localhost:8080/api` 即可（H5 dev 已代理 /api 到 8080）。

> ⚠️ 直连的是真实测试库：本地的写入/删除会直接落库；本地若有未上线的迁移脚本，
> 启动时 Flyway 会直接改这台库的表结构。请确认在测试库上操作。`dev-remote-db.conf` 含口令，已被 gitignore。

## 六、运维

- 看日志：`journalctl -u youyu -f`
- 重启：`sudo systemctl restart youyu`
- 状态：`systemctl status youyu`
- 回滚：`/opt/youyu/backup/` 下有历史 jar，拷回 `app/youyu.jar` 再 `systemctl restart youyu`
- 备份数据库（建议加 cron）：`mysqldump -u youyu -p youyu > youyu-$(date +%F).sql`

## 数据库连接池（成长结算的连接占用）

成长体系的结算在记账业务事务的**提交后回调**里以独立事务（`REQUIRES_NEW`）执行。此时外层记账事务的连接
**尚未释放**，结算又要另取一个连接，因此**同一记账请求在结算窗口内会同时占用 2 个数据库连接**。

- 连接池上限（`spring.datasource.hikari.maximum-pool-size`）已在 `application.yml` 显式设为 **20**
  （HikariCP 默认只有 10），按「并发记账请求数 × 2」预留余量；可用环境变量 `YOUYU_DB_POOL_MAX_SIZE` 覆盖。
- 记账高峰把连接池打满时，结算会以「**获取连接超时**」失败——这是**预期内的降级**：结算被设计成
  「失败即吞、下次自愈」，失败只记 WARN 日志，**不会拖垮记账**（记账事务已提交，经验会在该用户下一次
  结算时自动补齐）。因此看到偶发的结算获取连接超时告警**无需人工干预**。
- 若调大连接池，务必确认 MySQL 的 `max_connections`（MySQL 8 默认 151）能覆盖「所有实例连接池上限之和」。

## 注意

- `env.conf` 含密码/密钥，权限 600，**不进仓库**（`.gitignore` 已忽略）。
- 数据库迁移由 Flyway 在启动时执行；升级只需换 jar 重启，新迁移会自动应用（本次含用户表微信字段 V7）。
- 启用微信小程序登录需在 `env.conf` 补 `YOUYU_WX_APPID` / `YOUYU_WX_SECRET`（见 `env.conf.example`），改完 `systemctl restart youyu` 生效。
- 首次上线后建议改掉演示账号或不导入 seed（生产不需要 demo 数据）。
