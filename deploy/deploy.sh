#!/usr/bin/env bash
# ============================================================
# 服务器侧部署（用本地上传的产物替换并重启）
# 前提：deploy/dist 已上传到 /opt/youyu/source/deploy/dist（见 build-local.sh）
# 用法： sudo bash /opt/youyu/source/deploy/deploy.sh
# ============================================================
set -euo pipefail

APP_HOME="/opt/youyu"
DIST="$APP_HOME/source/deploy/dist"

echo "=== 有余 部署 $(date '+%Y-%m-%d %H:%M:%S') ==="

[ -f "$DIST/youyu.jar" ] || { echo "缺少 $DIST/youyu.jar，请先在本地运行 build-local.sh 并上传"; exit 1; }

echo "[后端] 备份并替换 jar..."
mkdir -p "$APP_HOME/app" "$APP_HOME/logs"
if [ -f "$APP_HOME/app/youyu.jar" ]; then
    mkdir -p "$APP_HOME/backup"
    cp "$APP_HOME/app/youyu.jar" "$APP_HOME/backup/youyu.jar.$(date +%Y%m%d%H%M%S)"
fi
cp "$DIST/youyu.jar" "$APP_HOME/app/youyu.jar"
chown -R youyu:youyu "$APP_HOME/app" "$APP_HOME/logs"

echo "[后端] 重启服务..."
systemctl restart youyu
sleep 4
if systemctl is-active --quiet youyu; then
    echo "[后端] 启动成功"
else
    echo "[后端] 启动失败，查看日志： journalctl -u youyu -n 100 --no-pager"
    exit 1
fi

echo "[前端] 替换静态文件..."
mkdir -p "$APP_HOME/web"
rm -rf "$APP_HOME/web"/*
cp -r "$DIST/web/"* "$APP_HOME/web"/
nginx -t && systemctl reload nginx

echo "=== 部署完成 ==="
echo "健康检查： curl -s http://127.0.0.1:8090/api/health"
