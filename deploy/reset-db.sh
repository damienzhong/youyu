#!/usr/bin/env bash
# ============================================================================
# 有余(youyu) 内测前清库：清空所有业务表数据（不备份）。
#
#   - 动态清空该库所有 BASE TABLE，但【保留】flyway_schema_history（迁移历史）
#   - 保留表结构：应用无需重启/重跑迁移，清空后直接可用（重新注册账号即可）
#   - 需交互输入库名确认，防误删；或用 CONFIRM=<库名> 非交互确认
#
# ⚠️ 破坏性、不可逆、且不备份！仅在确认数据可丢弃时使用（如个人测试数据）。
#
# 用法（服务器上）：
#   DB_NAME=youyu DB_USER=youyu DB_PASSWORD=*** bash deploy/reset-db.sh
# 非交互：
#   DB_NAME=youyu DB_USER=youyu DB_PASSWORD=*** CONFIRM=youyu bash deploy/reset-db.sh
# ============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-youyu}"
DB_USER="${DB_USER:-youyu}"
DB_PASSWORD="${DB_PASSWORD:-youyu}"

# 用 MYSQL_PWD 传密码，避免出现在进程命令行/历史里
export MYSQL_PWD="$DB_PASSWORD"
MYSQL=(mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" --protocol=TCP)

echo "=== 有余 清库 目标：$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME （不备份）==="

# 连通性检查
if ! "${MYSQL[@]}" -N -e "SELECT 1" "$DB_NAME" >/dev/null 2>&1; then
  echo "无法连接数据库，请检查 DB_HOST/DB_PORT/DB_USER/DB_PASSWORD/DB_NAME"
  exit 1
fi

# 确认门（不备份，务必确认数据可丢弃）
if [ "${CONFIRM:-}" != "$DB_NAME" ]; then
  echo "⚠️  这将清空库 [$DB_NAME] 的所有业务数据（保留表结构与 Flyway 历史），不备份、不可恢复。"
  read -r -p "请输入库名以确认清空（输入 $DB_NAME）： " ans
  if [ "$ans" != "$DB_NAME" ]; then
    echo "已取消。"
    exit 1
  fi
fi

# 动态取所有 BASE TABLE，排除 flyway_schema_history
mapfile -t TABLES < <("${MYSQL[@]}" -N -e \
  "SELECT table_name FROM information_schema.tables \
   WHERE table_schema='$DB_NAME' AND table_type='BASE TABLE' \
     AND table_name <> 'flyway_schema_history';" "$DB_NAME")

if [ "${#TABLES[@]}" -eq 0 ]; then
  echo "没有可清空的业务表（除 flyway_schema_history 外为空）。"
  exit 0
fi

echo "[清空] 将 TRUNCATE 以下 ${#TABLES[@]} 张表："
printf '  - %s\n' "${TABLES[@]}"

# 组装并执行（关外键检查，逐表 TRUNCATE，重置自增）
{
  echo "SET FOREIGN_KEY_CHECKS=0;"
  for t in "${TABLES[@]}"; do
    echo "TRUNCATE TABLE \`$t\`;"
  done
  echo "SET FOREIGN_KEY_CHECKS=1;"
} | "${MYSQL[@]}" "$DB_NAME"

echo "=== 清库完成。flyway_schema_history 已保留，应用无需重启即可用干净库开始内测。 ==="
