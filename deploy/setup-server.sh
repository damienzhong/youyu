#!/usr/bin/env bash
# ============================================================
# 服务器初始化（首次部署执行一次）
# 前提：已安装 JDK 17+、MySQL 8、Nginx
# 用法： sudo bash setup-server.sh
# ============================================================
set -euo pipefail

APP_HOME="/opt/youyu"
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "=========================================="
echo "  有余(youyu) 服务器初始化"
echo "=========================================="

command -v java >/dev/null || { echo "请先安装 JDK 17+"; exit 1; }

# 系统用户
if ! id youyu &>/dev/null; then
    groupadd --system youyu
    useradd --system --gid youyu --home-dir "$APP_HOME" \
        --shell /usr/sbin/nologin --comment "Youyu service" youyu
    echo "已创建系统用户 youyu"
fi

mkdir -p "$APP_HOME"/{app,logs,web,source}
chown -R youyu:youyu "$APP_HOME"

# 环境配置模板
if [ ! -f "$APP_HOME/app/env.conf" ]; then
    cp "$HERE/env.conf.example" "$APP_HOME/app/env.conf"
    chmod 600 "$APP_HOME/app/env.conf"
    chown youyu:youyu "$APP_HOME/app/env.conf"
    echo "✏️  已生成 env.conf 模板，请编辑真实值： vim $APP_HOME/app/env.conf"
fi

# systemd
cp -f "$HERE/youyu.service" /etc/systemd/system/youyu.service
systemctl daemon-reload
systemctl enable youyu
echo "youyu 服务已注册（尚未启动）"

cat <<EOF

=========================================
  ✅ 初始化完成，后续步骤：
=========================================
  1. 编辑环境变量（数据库密码、JWT 密钥）：
       vim $APP_HOME/app/env.conf
       # JWT 密钥： openssl rand -base64 48
  2. 建库与账号（Flyway 会自动建表，无需手动导入 schema）：
       mysql -u root -p
       CREATE DATABASE youyu CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
       CREATE USER 'youyu'@'localhost' IDENTIFIED BY '你的强密码';
       GRANT ALL ON youyu.* TO 'youyu'@'localhost'; FLUSH PRIVILEGES;
  3. 配置 Nginx：
       cp $HERE/nginx-youyu.conf /etc/nginx/conf.d/youyu.conf
       # 改 server_name 为你的域名/IP
       nginx -t && systemctl reload nginx
  4. 部署产物（先在本地 build-local.sh 生成并上传到 $APP_HOME/source/deploy/dist）：
       sudo bash $APP_HOME/source/deploy/deploy.sh
EOF
