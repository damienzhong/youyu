#!/usr/bin/env bash
# 本地演示数据播种（dev / H2 内存库）。
#
# 目标：造一批能清楚体现「账户独立于账本」的数据——
#   同一个「支付宝」账户同时参与「默认 / 家庭 / 旅行」三个账本，
#   并在不同账本里各记若干笔；其全局余额 = 初始 + 全部账本流水 + 转账，始终是单一值。
#
# 鉴权用新的邮箱验证码登录；dev 万能验证码为 000000（见 application-dev.yml）。
# 用法：先跑后端  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
#       再跑本脚本  bash deploy/seed-demo.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080/api}"
EMAIL="${EMAIL:-demo@youyu.local}"
DEV_CODE="${DEV_CODE:-000000}"

py() { python3 -c "$1"; }
jqget() { py "import sys,json;d=json.load(sys.stdin);print(d.get('$1','') if isinstance(d,dict) else '')"; }

echo "== 登录（邮箱验证码，dev 万能码 ${DEV_CODE}）=="
TOKEN=$(curl -s -X POST "$BASE/auth/email-login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"code\":\"$DEV_CODE\"}" | jqget token)
if [ -z "$TOKEN" ]; then echo "登录失败：请确认后端已用 dev profile 启动"; exit 1; fi

AUTHH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
# 带账本头的请求（在指定账本下记账 / 建账户）
postL() { curl -s -X POST "$BASE$1" "${AUTHH[@]}" -H "X-Ledger-Id: $2" -d "$3"; }
getL()  { curl -s "$BASE$1" "${AUTHH[@]}" -H "X-Ledger-Id: $2"; }
post()  { curl -s -X POST "$BASE$1" "${AUTHH[@]}" -d "$2"; }

echo "== 取默认账本 =="
DEF=$(curl -s "$BASE/ledgers" "${AUTHH[@]}" | py "import sys,json;d=json.load(sys.stdin);xs=[l for l in d if l.get('isDefault')] or d;print(xs[0]['id'] if xs else '')")
if [ -z "$DEF" ]; then echo "取默认账本失败"; exit 1; fi
echo "  默认账本=$DEF"

echo "== 建账户（归属用户，先纳入默认账本）=="
CASH=$(postL /accounts "$DEF" '{"name":"现金","type":"CASH","initialBalance":"2000.00"}' | jqget id)
CARD=$(postL /accounts "$DEF" '{"name":"招商储蓄卡","type":"BANK_CARD","initialBalance":"18000.00"}' | jqget id)
CREDIT=$(postL /accounts "$DEF" '{"name":"招行信用卡","type":"CREDIT_CARD","initialBalance":"-1200.00","creditLimit":"20000.00","billDay":10,"repayDay":20,"repayReminder":true}' | jqget id)
ALIPAY=$(postL /accounts "$DEF" '{"name":"支付宝","type":"ALIPAY","initialBalance":"860.00"}' | jqget id)
WECHAT=$(postL /accounts "$DEF" '{"name":"微信钱包","type":"WECHAT","initialBalance":"500.00"}' | jqget id)
echo "  现金=$CASH 招商卡=$CARD 信用卡=$CREDIT 支付宝=$ALIPAY 微信=$WECHAT"

echo "== 建协作账本，复用已有账户 =="
# 家庭账本：现金 + 招商卡 + 支付宝
FAM=$(post /ledgers "{\"name\":\"家庭账本\",\"type\":\"COLLABORATIVE\",\"accountIds\":[$CASH,$CARD,$ALIPAY]}" | jqget id)
# 旅行 AA：支付宝 + 微信（支付宝同时属于 默认/家庭/旅行 三个账本）
TRIP=$(post /ledgers "{\"name\":\"旅行AA\",\"type\":\"COLLABORATIVE\",\"accountIds\":[$ALIPAY,$WECHAT]}" | jqget id)
echo "  家庭账本=$FAM 旅行AA=$TRIP"

# 取某账本下第一个「收入/支出」叶子分类 id
firstcat() { # $1=ledgerId $2=kind(expense|income)
  getL /categories "$1" | py "
import sys,json
d=json.load(sys.stdin)
def leaves(ns):
    out=[]
    for n in ns:
        ch=n.get('children') or []
        out+=leaves(ch) if ch else [n]
    return out
xs=leaves(d.get('$2',[]))
print(xs[0]['id'] if xs else '')"
}

echo "== 记流水（同一支付宝账户，跨三个账本）=="
# 默认账本为首次登录懒创建，不含默认分类，这里显式建两个分类。
DEF_EXP=$(firstcat "$DEF" expense)
DEF_INC=$(firstcat "$DEF" income)
[ -z "$DEF_EXP" ] && DEF_EXP=$(postL /categories "$DEF" '{"kind":"EXPENSE","name":"餐饮"}' | jqget id)
[ -z "$DEF_INC" ] && DEF_INC=$(postL /categories "$DEF" '{"kind":"INCOME","name":"工资"}' | jqget id)
FAM_EXP=$(firstcat "$FAM" expense)
TRIP_EXP=$(firstcat "$TRIP" expense)

# 默认账本：工资入招商卡 + 支付宝午餐
postL /transactions "$DEF" "{\"type\":\"income\",\"amount\":\"18000.00\",\"accountId\":$CARD,\"categoryId\":$DEF_INC,\"occurredAt\":\"2026-08-05T10:00:00\",\"note\":\"八月工资\"}" >/dev/null
postL /transactions "$DEF" "{\"type\":\"expense\",\"amount\":\"38.50\",\"accountId\":$ALIPAY,\"categoryId\":$DEF_EXP,\"occurredAt\":\"2026-08-06T12:30:00\",\"note\":\"午餐（默认账本·支付宝）\"}" >/dev/null
# 家庭账本：支付宝买菜 + 现金
postL /transactions "$FAM" "{\"type\":\"expense\",\"amount\":\"126.00\",\"accountId\":$ALIPAY,\"categoryId\":$FAM_EXP,\"occurredAt\":\"2026-08-07T19:00:00\",\"note\":\"买菜（家庭账本·支付宝）\"}" >/dev/null
postL /transactions "$FAM" "{\"type\":\"expense\",\"amount\":\"50.00\",\"accountId\":$CASH,\"categoryId\":$FAM_EXP,\"occurredAt\":\"2026-08-07T20:00:00\",\"note\":\"打车（家庭账本·现金）\"}" >/dev/null
# 旅行 AA：支付宝 + 微信
postL /transactions "$TRIP" "{\"type\":\"expense\",\"amount\":\"320.00\",\"accountId\":$ALIPAY,\"categoryId\":$TRIP_EXP,\"occurredAt\":\"2026-08-08T13:00:00\",\"note\":\"民宿（旅行AA·支付宝）\"}" >/dev/null
postL /transactions "$TRIP" "{\"type\":\"expense\",\"amount\":\"88.00\",\"accountId\":$WECHAT,\"categoryId\":$TRIP_EXP,\"occurredAt\":\"2026-08-08T18:30:00\",\"note\":\"晚饭（旅行AA·微信）\"}" >/dev/null

echo "== 账户转账（招商卡 -> 支付宝，脱离账本）=="
post /accounts/transfer "{\"sourceAccountId\":$CARD,\"destinationAccountId\":$ALIPAY,\"amount\":\"1000.00\",\"occurredAt\":\"2026-08-06T08:00:00\",\"note\":\"转到支付宝\"}" >/dev/null

echo
echo "== 完成 =="
echo "登录邮箱：$EMAIL  验证码：$DEV_CODE"
echo "看点：支付宝账户同时出现在 默认/家庭/旅行 三个账本的记账里，"
echo "     但它在资产页只有一个全局余额（初始 860 - 38.5 - 126 - 320 + 1000 = 1375.50）。"
