#!/usr/bin/env bash
# AA 账本演示数据播种（dev / H2 内存库）。
#
# 造一个「云南旅行AA」账本，三名成员（Alice/Bob/Carol），演示：
#   - 本人付款（扣本人账户）与他人代付（不动本人账户）
#   - 均分 与 自定义金额 两种分摊
#   - 净额 / 最少转账建议
#   - 一条真实结算落库（Alice 结清对 Bob 的应付，Alice 侧扣款）
#
# 鉴权用邮箱验证码登录；dev 万能验证码为 000000（见 application-dev.yml）。
# 用法：先跑后端  YOUYU_PORT=8090 ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
#       再跑本脚本  BASE=http://127.0.0.1:8090/api bash deploy/seed-aa-demo.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8090/api}"
DEV_CODE="${DEV_CODE:-000000}"

jqget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1','') if isinstance(d,dict) else '')"; }

# ---- HTTP 帮助函数（Authorization 头整体作为单个参数，避免词分割）----
post()  { curl -s -X POST "$BASE$2" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postL() { curl -s -X POST "$BASE$3" -H "Authorization: Bearer $1" -H "X-Ledger-Id: $2" -H 'Content-Type: application/json' -d "$4"; }
getT()  { curl -s "$BASE$2" -H "Authorization: Bearer $1"; }
getL()  { curl -s "$BASE$3" -H "Authorization: Bearer $1" -H "X-Ledger-Id: $2"; }

login() { # $1=email  -> echoes "TOKEN USERID"
  local resp
  resp=$(curl -s -X POST "$BASE/auth/email-login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"code\":\"$DEV_CODE\"}")
  local tok uid
  tok=$(printf '%s' "$resp" | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
  uid=$(printf '%s' "$resp" | python3 -c "import sys,json;print(json.load(sys.stdin)['user']['id'])")
  echo "$tok $uid"
}

defledger() { # $1=token
  getT "$1" /ledgers | python3 -c "import sys,json;d=json.load(sys.stdin);xs=[l for l in d if l.get('isDefault')] or d;print(xs[0]['id'] if xs else '')"
}

echo "== 登录三名成员（dev 万能码 ${DEV_CODE}）=="
read -r TOKA UIDA <<<"$(login alice@youyu.local)"
read -r TOKB UIDB <<<"$(login bob@youyu.local)"
read -r TOKC UIDC <<<"$(login carol@youyu.local)"
echo "  Alice uid=$UIDA  Bob uid=$UIDB  Carol uid=$UIDC"
[ -z "$TOKA" ] && { echo "登录失败：确认后端已用 dev profile 启动"; exit 1; }

echo "== 为三人各建一个付款账户（归属各自用户）=="
DEFA=$(defledger "$TOKA"); DEFB=$(defledger "$TOKB"); DEFC=$(defledger "$TOKC")
ACCA=$(postL "$TOKA" "$DEFA" /accounts '{"name":"Alice支付宝","type":"ALIPAY","initialBalance":"5000.00"}' | jqget id)
ACCB=$(postL "$TOKB" "$DEFB" /accounts '{"name":"Bob微信","type":"WECHAT","initialBalance":"5000.00"}' | jqget id)
ACCC=$(postL "$TOKC" "$DEFC" /accounts '{"name":"Carol现金","type":"CASH","initialBalance":"5000.00"}' | jqget id)
echo "  Alice账户=$ACCA  Bob账户=$ACCB  Carol账户=$ACCC"

echo "== Alice 创建 AA 账本「云南旅行AA」=="
AA=$(post "$TOKA" /ledgers '{"name":"云南旅行AA","type":"AA"}' | jqget id)
echo "  AA账本=$AA"

echo "== Alice 生成邀请码，Bob / Carol 加入 =="
CODE=$(post "$TOKA" "/ledgers/$AA/invite" '{}' | jqget code)
post "$TOKB" /ledgers/join "{\"code\":\"$CODE\"}" >/dev/null
post "$TOKC" /ledgers/join "{\"code\":\"$CODE\"}" >/dev/null
echo "  邀请码=${CODE}，成员："
getT "$TOKA" "/ledgers/$AA/members" | python3 -c "import sys,json;[print('   -',m['displayName'],'uid='+str(m['userId']),'(owner)' if m['owner'] else '') for m in json.load(sys.stdin)]"

AACAT=$(getL "$TOKA" "$AA" /categories | python3 -c "
import sys,json
d=json.load(sys.stdin)
def leaves(ns):
    out=[]
    for n in ns:
        ch=n.get('children') or []
        out+=leaves(ch) if ch else [n]
    return out
xs=leaves(d.get('expense',[]))
print(xs[0]['id'] if xs else '')")
echo "  AA支出分类=$AACAT"

echo "== 记若干 AA 支出 =="
postL "$TOKA" "$AA" /aa/expenses "{\"amount\":\"900.00\",\"categoryId\":$AACAT,\"payerUserId\":$UIDA,\"payerAccountId\":$ACCA,\"note\":\"民宿两晚\",\"splitMode\":\"even\",\"participants\":[$UIDA,$UIDB,$UIDC]}" >/dev/null
echo "  + Alice 付 900 民宿，三人均分"
postL "$TOKB" "$AA" /aa/expenses "{\"amount\":\"360.00\",\"categoryId\":$AACAT,\"payerUserId\":$UIDB,\"payerAccountId\":$ACCB,\"note\":\"过桥米线晚餐\",\"splitMode\":\"even\",\"participants\":[$UIDA,$UIDB,$UIDC]}" >/dev/null
echo "  + Bob 付 360 晚餐，三人均分"
postL "$TOKC" "$AA" /aa/expenses "{\"amount\":\"500.00\",\"categoryId\":$AACAT,\"payerUserId\":$UIDC,\"payerAccountId\":$ACCC,\"note\":\"景区门票\",\"splitMode\":\"custom\",\"participants\":[$UIDA,$UIDB,$UIDC],\"customShares\":[{\"userId\":$UIDA,\"amount\":\"200.00\"},{\"userId\":$UIDB,\"amount\":\"200.00\"},{\"userId\":$UIDC,\"amount\":\"100.00\"}]}" >/dev/null
echo "  + Carol 付 500 门票，自定义 200/200/100"
postL "$TOKA" "$AA" /aa/expenses "{\"amount\":\"150.00\",\"categoryId\":$AACAT,\"payerUserId\":$UIDB,\"note\":\"机场打车\",\"splitMode\":\"even\",\"participants\":[$UIDA,$UIDB]}" >/dev/null
echo "  + Alice 代记：Bob 付 150 打车，Alice/Bob 均分（不动 Alice 账户）"

echo
echo "== 结算视图（每人净额 + 建议转账）=="
getT "$TOKA" "/aa/$AA/settlement" | python3 -m json.tool --no-ensure-ascii || getT "$TOKA" "/aa/$AA/settlement"

echo
echo "== 一条真实结算：Carol 结清应付给 Alice 20（Carol 侧扣款）=="
# Carol 净额 -20（应付 Alice）。由 Carol 发起、付给 Alice，扣 Carol 账户。
SETTLE=$(postL "$TOKC" "$AA" /aa/settlements "{\"toUserId\":$UIDA,\"amount\":\"20.00\",\"myAccountId\":$ACCC}")
echo "  结算结果: $SETTLE"
echo "  结算后 Carol 净额应为 0："
getT "$TOKA" "/aa/$AA/settlement" | python3 -m json.tool --no-ensure-ascii

echo
echo "== Alice 视角 overview =="
getT "$TOKA" "/aa/$AA/overview" | python3 -m json.tool --no-ensure-ascii || getT "$TOKA" "/aa/$AA/overview"

echo
echo "== 完成 =="
echo "登录任一成员：alice@youyu.local / bob@youyu.local / carol@youyu.local ，验证码 ${DEV_CODE}"
echo "AA 账本 id = $AA"
