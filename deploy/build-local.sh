#!/usr/bin/env bash
# ============================================================
# 本地构建产物（后端 jar + 前端 dist），打包到 deploy/dist/。
# 可选：设置 DEPLOY_SSH（如 user@host）后自动 rsync 到服务器 /opt/youyu/source。
# 用法：
#   bash deploy/build-local.sh                 # 只构建
#   DEPLOY_SSH=root@1.2.3.4 bash deploy/build-local.sh   # 构建并上传
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/deploy/dist"

echo "== [1/3] 构建后端 jar =="
cd "$ROOT"
./mvnw -q clean package                      # 含测试；如需跳过加 -DskipTests

echo "== [2/3] 构建前端 H5（miniapp）=="
# 线上前端 = uni-app 的 H5 产物（与小程序同一套代码）。API 走相对 /api，由 nginx 反代到后端 8090。
cd "$ROOT/miniapp"
if [ -f package-lock.json ]; then npm ci; else npm install; fi
VITE_API_BASE=/api npm run build:h5

echo "== [3/3] 组装 deploy/dist =="
rm -rf "$DIST"
mkdir -p "$DIST/web"
cp "$ROOT/target/youyu.jar" "$DIST/youyu.jar"
cp -r "$ROOT/miniapp/dist/build/h5/"* "$DIST/web/"
echo "产物已就绪：$DIST (youyu.jar + web/)"

if [ -n "${DEPLOY_SSH:-}" ]; then
    echo "== 上传到 $DEPLOY_SSH:/opt/youyu/source/deploy/dist =="
    ssh "$DEPLOY_SSH" "mkdir -p /opt/youyu/source/deploy/dist"
    rsync -az --delete "$DIST/" "$DEPLOY_SSH:/opt/youyu/source/deploy/dist/"
    echo "上传完成。到服务器执行： sudo bash /opt/youyu/source/deploy/deploy.sh"
fi
