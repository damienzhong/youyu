#!/usr/bin/env bash
# 由 GitHub Actions 通过 SSH 在服务器上执行：
#   替换后端 jar + 前端静态文件 → 重启后端 → 健康检查 → reload nginx。
# 产物由 workflow 提前 scp 到：/opt/youyu/app/youyu.jar.new 与 /tmp/youyu-web.tar.gz
set -euo pipefail

APP=/opt/youyu/app
WEB=/opt/youyu/web
BACKUP=/opt/youyu/backup

mkdir -p "$APP" "$WEB" "$BACKUP" /opt/youyu/logs

# 备份当前 jar（保留最近 5 个）
if [ -f "$APP/youyu.jar" ]; then
  cp "$APP/youyu.jar" "$BACKUP/youyu.jar.$(date +%Y%m%d%H%M%S)"
  ls -1dt "$BACKUP"/youyu.jar.* 2>/dev/null | tail -n +6 | xargs -r rm -f
fi

# 替换 jar
mv "$APP/youyu.jar.new" "$APP/youyu.jar"

# 替换前端静态文件
rm -rf "${WEB:?}/"*
tar -C "$WEB" -xzf /tmp/youyu-web.tar.gz
rm -f /tmp/youyu-web.tar.gz

chown -R youyu:youyu "$APP" "$WEB" /opt/youyu/logs

# 重启后端
systemctl restart youyu

# 健康检查（最多等 ~120s；/api/health 正常返回 200）
ok=0
for i in $(seq 1 24); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8090/api/health || echo 000)
  if [ "$code" = "200" ]; then
    echo "backend healthy (http=$code)"
    ok=1
    break
  fi
  sleep 5
done
if [ "$ok" != "1" ]; then
  echo "ERROR: backend did not become healthy, dumping recent logs:"
  journalctl -u youyu -n 40 --no-pager || true
  exit 1
fi

# reload nginx
nginx -t && systemctl reload nginx
echo "=== deploy finished ==="
