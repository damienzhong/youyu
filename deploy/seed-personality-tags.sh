#!/usr/bin/env bash
# 趣味人格标签（fun-personality-tags）演示数据播种（dev / H2 内存库）。
#
# 目标：针对「上一个已完结自然月」精准造数据，尽量把 8 枚人格标签全部触发出来：
#   省钱达人 / 理财新星 / 预算大师 / 外卖探索家 / 咖啡收藏家 / 夜宵王 / 旅行狂人 / 购物生活家。
#
# 关键前提（对齐 fun-personality-tags 需求）：
#   - 标签只在「已完结的自然月」（目标月早于当前自然月）判定；进行中的当前月只会返回鼓励兜底文案。
#     故本脚本把交易造在「上个月」(TARGET)，并把省钱达人所需的更高支出造在「上上个月」(PREV)。
#   - 行为类标签按「分类名称」匹配默认集合：外卖→分类「外卖」、咖啡→「咖啡」、旅行→「旅行」、购物→「购物」。
#   - 夜宵王按 Asia/Shanghai 本地小时 [22:00,04:00) 统计，故把外卖交易排在 23:10。
#   - 接口默认只返回强度分最高的前 N=4 枚（youyu.personality-tags.max-count，默认 4）；
#     想一次看全 8 枚，可用环境变量 YOUYU_PERSONALITY_TAGS_MAX_COUNT=8 或在 application 里改 max-count 后重启。
#
# 鉴权用邮箱验证码登录；dev 万能验证码为 000000（见 application-dev.yml）。
# 用法：
#   先跑后端  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
#   再跑本脚本 bash deploy/seed-personality-tags.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080/api}"
EMAIL="${EMAIL:-demo@youyu.local}"
DEV_CODE="${DEV_CODE:-000000}"

py() { python3 -c "$1"; }
jqget() { py "import sys,json;d=json.load(sys.stdin);print(d.get('$1','') if isinstance(d,dict) else '')"; }

# 计算目标月 TARGET（上一自然月）与基线月 PREV（上上自然月），格式 YYYY-MM。
TARGET=$(py "from datetime import date
t=date.today(); y,m=t.year,t.month-1
if m==0: y,m=y-1,12
print(f'{y:04d}-{m:02d}')")
PREV=$(py "from datetime import date
t=date.today(); y,m=t.year,t.month-2
while m<=0: y,m=y-1,m+12
print(f'{y:04d}-{m:02d}')")
echo "== 目标月(已完结)=$TARGET  基线月(省钱达人对比)=$PREV =="

echo "== 登录（邮箱验证码，dev 万能码 ${DEV_CODE}）=="
TOKEN=$(curl -s -X POST "$BASE/auth/email-login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"code\":\"$DEV_CODE\"}" | jqget token)
if [ -z "$TOKEN" ]; then echo "登录失败：请确认后端已用 dev profile 启动"; exit 1; fi

AUTHH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
postL() { curl -s -X POST "$BASE$1" "${AUTHH[@]}" -H "X-Ledger-Id: $2" -d "$3"; }
putL()  { curl -s -X PUT  "$BASE$1" "${AUTHH[@]}" -H "X-Ledger-Id: $2" -d "$3"; }

echo "== 取默认账本 =="
DEF=$(curl -s "$BASE/ledgers" "${AUTHH[@]}" | py "import sys,json;d=json.load(sys.stdin);xs=[l for l in d if l.get('isDefault')] or d;print(xs[0]['id'] if xs else '')")
if [ -z "$DEF" ]; then echo "取默认账本失败"; exit 1; fi
echo "  默认账本=$DEF"

echo "== 建账户 =="
ALIPAY=$(postL /accounts "$DEF" '{"name":"支付宝","type":"ALIPAY","initialBalance":"100000.00"}' | jqget id)
echo "  支付宝=$ALIPAY"

echo "== 建分类（名称需精确匹配行为类标签默认集合）=="
C_TAKEOUT=$(postL /categories "$DEF" '{"kind":"EXPENSE","name":"外卖"}' | jqget id)
C_COFFEE=$(postL /categories "$DEF" '{"kind":"EXPENSE","name":"咖啡"}' | jqget id)
C_TRAVEL=$(postL /categories "$DEF" '{"kind":"EXPENSE","name":"旅行"}' | jqget id)
C_SHOP=$(postL /categories "$DEF" '{"kind":"EXPENSE","name":"购物"}' | jqget id)
C_MISC=$(postL /categories "$DEF" '{"kind":"EXPENSE","name":"日常"}' | jqget id)
C_SALARY=$(postL /categories "$DEF" '{"kind":"INCOME","name":"工资"}' | jqget id)
echo "  外卖=$C_TAKEOUT 咖啡=$C_COFFEE 旅行=$C_TRAVEL 购物=$C_SHOP 日常=$C_MISC 工资=$C_SALARY"

tx() { # $1=type $2=amount $3=categoryId $4=occurredAt $5=note
  postL /transactions "$DEF" \
    "{\"type\":\"$1\",\"amount\":\"$2\",\"accountId\":$ALIPAY,\"categoryId\":$3,\"occurredAt\":\"$4\",\"note\":\"$5\"}" >/dev/null
}

echo "== 基线月 ${PREV} 造一笔大额支出（让目标月成为「省钱达人」）=="
tx expense "50000.00" "$C_MISC" "${PREV}-15T12:00:00" "上月大额支出（对比基线）"

echo "== 目标月 ${TARGET} 收入 + 各类支出 =="
# 理财新星：收入远大于支出，结余率 >=20%
tx income "30000.00" "$C_SALARY" "${TARGET}-05T10:00:00" "工资"

# 外卖探索家：>=8 笔；同时排在 23:10 → 兼作夜宵王（>=5 笔夜宵）
for d in 05 06 07 08 09 10 11 12; do
  tx expense "32.00" "$C_TAKEOUT" "${TARGET}-${d}T23:10:00" "外卖夜宵 ${d}"
done

# 咖啡收藏家：>=5 笔
for d in 06 08 10 13 16; do
  tx expense "35.00" "$C_COFFEE" "${TARGET}-${d}T09:30:00" "咖啡 ${d}"
done

# 旅行狂人：金额 >=1000
tx expense "1200.00" "$C_TRAVEL" "${TARGET}-14T15:00:00" "周末出游"

# 购物生活家：金额 >=800
tx expense "900.00" "$C_SHOP" "${TARGET}-18T20:00:00" "置办好物"

echo "== 目标月 ${TARGET} 设置预算（预算大师，使用率 <=90%）=="
putL "/budgets?month=${TARGET}" "$DEF" '{"amount":"5000.00"}' >/dev/null

echo
echo "== 验证：拉取目标月趣味人格标签 =="
curl -s "$BASE/reports/personality-tags?month=${TARGET}" "${AUTHH[@]}" -H "X-Ledger-Id: $DEF" \
  | py "import sys,json
d=json.load(sys.stdin)
print('month=%s status=%s isFallback=%s' % (d.get('month'), d.get('monthStatus'), d.get('isFallback')))
if d.get('fallbackText'): print('fallbackText=', d['fallbackText'])
for t in d.get('tags') or []:
    print(' -', t.get('emoji',''), t.get('title',''), '| score=', t.get('strengthScore'), '|', t.get('narrativeText'))"

echo
echo "== 完成 =="
echo "登录邮箱：${EMAIL}  验证码：${DEV_CODE}  目标月：${TARGET} （在报表页把月份切到这里查看标签墙）"
echo "提示：接口默认只返回强度分最高的前 4 枚；想一次看全 8 枚可把 max-count 调到 8 后重启后端。"
