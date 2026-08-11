# 有余(youyu) 项目执行守则（前置约束）

> 本文件为 steering（默认始终纳入上下文）。以下约束在**每次执行任何任务前**都必须遵守，优先级高于"尽快完成任务"。

## Git / 发布（最重要）

- **绝不自动 `git push`**。`git commit` 可以在编译+测试通过后进行；但**推送到远端**（会触发 GitHub Actions 自动部署到生产）**必须由用户明确指示**后才能做。
- 用户说"提交代码"= 只 `commit`，**不 push**；要 push 必须用户明说"push/推送/发布上线"之类。
- 不直接操作 `main` 以外的强制动作；不使用 `push -f`、`reset --hard`、`clean -f` 等破坏性 git 命令，除非用户明确授权。
- 不修改 git config。

## 生产 / 服务器 / 高风险操作

- **不自动执行任何影响线上的操作**：部署、重启服务、删库/清库、改数据库、改阿里云安全组、改 nginx 生产配置等，一律先说明再等用户确认。
- 数据库迁移由 Flyway 在后端启动时自动执行，**不手动执行 SQL**。
- 删除多文件/目录、批量改动等不可逆操作，先确认。

## 提交前质量门（提交=commit 前必须满足）

- 后端：`./mvnw -q -o compile` 通过，且 `./mvnw -o test` **全部测试绿**（含 jqwik 属性测试）。
- 小程序：`cd miniapp && npx uni build -p mp-weixin` 与 `VITE_API_BASE=/api npm run build:h5` **两端都构建通过**。
- 只有在以上通过后才 `git commit`；提交信息用中文、说明改了什么。

## 编码约定

- **用中文回复**（产品面向中国用户）。
- 不自动新增测试，除非用户明确要求。
- 给已被测试依赖的方法加能力时，用**新增重载**而非改签名，避免破坏现有测试；给 service 加依赖时同步修所有手动构造该 service 的测试。
- 金额一律 `BigDecimal`/`DECIMAL(18,2)`；时区 `Asia/Shanghai`。
- 查找用文件/搜索工具，不用 cat/grep/find；sed 仅用于机械批量替换后再编译验证。

## 线上环境备忘

- 阿里云 ECS `47.120.65.57`：nginx(80/8081) → youyu 后端(8090) → MySQL；youyu 前端 = miniapp 的 H5 产物部署在 `/opt/youyu/web`。
- 域名 `youyuji.com` 未备案，备案通过前用 `http://47.120.65.57:8081` 访问。
- 部署走 `git push main` 触发 Actions（因此更要遵守"绝不自动 push"）。
