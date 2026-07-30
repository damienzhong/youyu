#!/usr/bin/env bash
# 本地演示数据播种：注册/登录 demo 用户，建账户/分类/流水/转账，并建一个协作账本（选账户）。
# 仅用于 dev（H2 内存库）。用法：bash deploy/seed-demo.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080/api}"
USER="${USER_NAME:-demo}"
PASS="${PASS:-password123}"

jqget() { # 从 JSON 里取一个字段（简单提取，避免依赖 jq）
  python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))"
}

echo "== 注册（已存在则忽略）=="
curl -s -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" >/dev/null || true

echo "== 登录 =="
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" | jqget token)
if [ -z "$TOKEN" ]; then echo "登录失败"; exit 1; fi
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

post() { curl -s -X POST "$BASE$1" "${AUTH[@]}" -d "$2"; }

echo "== 建账户 =="
CASH=$(post /accounts '{"name":"现金","type":"CASH","initialBalance":"2000.00"}' | jqget id)
CARD=$(post /accounts '{"name":"招商银行卡","type":"BANK_CARD","initialBalance":"18000.00"}' | jqget id)
CREDIT=$(post /accounts '{"name":"信用卡","type":"CREDIT_CARD","initialBalance":"-1200.00","creditLimit":"20000.00"}' | jqget id)
ALIPAY=$(post /accounts '{"name":"支付宝","type":"ALIPAY","initialBalance":"860.00"}' | jqget id)
echo "  现金=$CASH 银行卡=$CARD 信用卡=$CREDIT 支付宝=$ALIPAY"

echo "== 建分类 =="
C_FOOD=$(post /categories '{"kind":"EXPENSE","name":"餐饮"}' | jqget id)
C_TRAFFIC=$(post /categories '{"kind":"EXPENSE","name":"交通"}' | jqget id)
C_SHOP=$(post /categories '{"kind":"EXPENSE","name":"购物"}' | jqget id)
C_SALARY=$(post /categories '{"kind":"INCOME","name":"工资"}' | jqget id)

echo "== 记流水 =="
post /transactions "{\"type\":\"income\",\"amount\":\"18000.00\",\"accountId\":$CARD,\"categoryId\":$C_SALARY,\"occurredAt\":\"2026-07-05T10:00:00\",\"note\":\"七月工资\"}" >/dev/null
post /transactions "{\"type\":\"expense\",\"amount\":\"38.50\",\"accountId\":$ALIPAY,\"categoryId\":$C_FOOD,\"occurredAt\":\"2026-07-06T12:30:00\",\"note\":\"午餐\"}" >/dev/null
post /transactions "{\"type\":\"expense\",\"amount\":\"6.00\",\"accountId\":$ALIPAY,\"categoryId\":$C_TRAFFIC,\"occurredAt\":\"2026-07-06T09:00:00\",\"note\":\"地铁\"}" >/dev/null
post /transactions "{\"type\":\"expense\",\"amount\":\"299.00\",\"accountId\":$CREDIT,\"categoryId\":$C_SHOP,\"occurredAt\":\"2026-07-07T20:00:00\",\"note\":\"网购\"}" >/dev/null

echo "== 账户转账（银行卡 -> 支付宝，脱离账本）=="
post /accounts/transfer "{\"sourceAccountId\":$CARD,\"destinationAccountId\":$ALIPAY,\"amount\":\"1000.00\",\"occurredAt\":\"2026-07-06T08:00:00\",\"note\":\"转到支付宝\"}" >/dev/null

echo "== 建协作账本（纳入 现金 + 支付宝）=="
post /ledgers "{\"name\":\"家庭账本\",\"type\":\"COLLABORATIVE\",\"accountIds\":[$CASH,$ALIPAY]}" >/dev/null

echo "== 完成。用户：$USER / $PASS =="
