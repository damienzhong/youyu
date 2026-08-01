#!/usr/bin/env bash
# ============================================================
# 本地后端直连"测试服务器 MySQL"运行，便于本地改代码即时调试，无需每次发布到服务器。
#
# 用法：
#   1) cp deploy/dev-remote-db.conf.example deploy/dev-remote-db.conf  （首次）
#      并填入测试服务器 IP 与 DB 口令
#   2) bash deploy/dev-remote-db.sh
#
# ⚠️ 连接的是测试库：本地写入/删除会直接作用到该库；本地若有未上线的迁移脚本，
#    启动时 Flyway 会直接修改这台库的表结构。确认你在测试库上操作后再继续。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$ROOT/deploy/dev-remote-db.conf"

if [ ! -f "$CONF" ]; then
  echo "缺少配置：$CONF"
  echo "请先执行： cp deploy/dev-remote-db.conf.example deploy/dev-remote-db.conf 并填写"
  exit 1
fi
# shellcheck disable=SC1090
set -a; source "$CONF"; set +a

: "${DB_HOST:?请在 conf 里填 DB_HOST}"
: "${DB_PASSWORD:?请在 conf 里填 DB_PASSWORD}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-youyu}"
DB_USER="${DB_USER:-youyu}"
PORT="${PORT:-8080}"

echo "== 连接测试库 ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}，本地端口 ${PORT} =="
echo "⚠️  这是真实测试库，注意你的写操作会直接落库。"

export YOUYU_DB_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
export YOUYU_DB_USER="$DB_USER"
export YOUYU_DB_PASSWORD="$DB_PASSWORD"
export YOUYU_PORT="$PORT"
[ -n "${JWT_SECRET:-}" ]     && export YOUYU_JWT_SECRET="$JWT_SECRET"
[ -n "${MAIL_USERNAME:-}" ]  && export YOUYU_MAIL_USERNAME="$MAIL_USERNAME"
[ -n "${MAIL_PASSWORD:-}" ]  && export YOUYU_MAIL_PASSWORD="$MAIL_PASSWORD"
[ -n "${WX_APPID:-}" ]       && export YOUYU_WX_APPID="$WX_APPID"
[ -n "${WX_SECRET:-}" ]      && export YOUYU_WX_SECRET="$WX_SECRET"

# 本地"万能验证码"：默认 000000，登录/绑定/注销时输入它即通过，免去翻日志取码。
# 仅作用于本地这个进程（通过命令行参数注入），不影响服务器配置。置空可关闭。
DEV_LOGIN_CODE="${DEV_LOGIN_CODE:-000000}"

# 默认 profile（非 dev）→ 走 MySQL 数据源。
cd "$ROOT"
if [ -n "$DEV_LOGIN_CODE" ]; then
  echo "== 本地万能验证码已启用：${DEV_LOGIN_CODE}（仅本进程，服务器不受影响）=="
  exec ./mvnw spring-boot:run \
    -Dspring-boot.run.arguments="--app.auth.email-code.dev-code=${DEV_LOGIN_CODE}"
else
  exec ./mvnw spring-boot:run
fi
