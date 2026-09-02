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

# 同步 nginx 应用配置片段（location / 缓存策略 / 反向代理 / gzip）。
#
# 只同步这个片段，刻意不碰 /etc/nginx/conf.d/youyu.conf —— 那个文件由 certbot
# 管理（listen 443 / ssl_* / 301 跳转），整文件覆盖会把 HTTPS 弄掉。
# 加这一步的原因：在此之前部署只 reload nginx、从不同步配置，仓库里的 nginx 配置
# 沦为纯文档并与线上漂移，导致线上长期停留在没有 /app/ 块的旧版本，HTML 因此缺少
# no-cache、被客户端（尤其 Android WebView）启发式缓存住，表现为「已发版但一直
# 加载旧版」。
#
# 本机可能同时跑着其他站点，故 nginx -t 不通过必须立刻回滚，绝不让 reload 带着坏配置执行。
SNIPPET_SRC=/tmp/youyu-nginx-locations.conf
SNIPPET_DST=/etc/nginx/snippets/youyu-locations.conf
if [ -f "$SNIPPET_SRC" ]; then
  mkdir -p /etc/nginx/snippets
  SNIPPET_BAK=""
  if [ -f "$SNIPPET_DST" ]; then
    SNIPPET_BAK="$BACKUP/youyu-locations.conf.$(date +%Y%m%d%H%M%S)"
    cp -a "$SNIPPET_DST" "$SNIPPET_BAK"
  fi
  cp -a "$SNIPPET_SRC" "$SNIPPET_DST"
  rm -f "$SNIPPET_SRC"
  if nginx -t; then
    echo "nginx 配置片段已同步"
  else
    echo "ERROR: 同步 nginx 片段后 nginx -t 未通过，回滚"
    if [ -n "$SNIPPET_BAK" ]; then
      cp -a "$SNIPPET_BAK" "$SNIPPET_DST"
    else
      # 首次同步就失败：移除新装的片段。若 youyu.conf 已 include 它，则需人工介入。
      rm -f "$SNIPPET_DST"
    fi
    nginx -t
    exit 1
  fi
fi

# reload nginx
nginx -t && systemctl reload nginx
echo "=== deploy finished ==="
