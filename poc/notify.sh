#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  發一則通知，並把結果查回來。
#
#  用法：
#    export NOTIFY_BASE=https://notif.azndev.com
#    export NOTIFY_CLIENT_ID=cli_xxx
#    export NOTIFY_SECRET=xxxxx
#    ./poc/notify.sh "備份完成"
#    ./poc/notify.sh "公告內容" ALL
#
#  這支腳本同時是「怎麼簽章」的可執行文件 —— 接入時直接照抄。
# ---------------------------------------------------------------------------
set -euo pipefail

BASE="${NOTIFY_BASE:?請設 NOTIFY_BASE，例如 https://notif.azndev.com}"
CLIENT_ID="${NOTIFY_CLIENT_ID:?請設 NOTIFY_CLIENT_ID}"
SECRET="${NOTIFY_SECRET:?請設 NOTIFY_SECRET}"

TEXT="${1:-NotifyLine 測試訊息}"
TARGET="${2:-OWNER}"
PATH_NOTIFY="/api/v1/notifications"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ── 組請求 body ────────────────────────────────────────────────────────────
# 一定要寫進檔案再用 --data-binary 送。
# 在 Git Bash 上用 --data-raw 傳中文會被 argv 的編碼轉換弄壞，
# 於是 curl 送出去的位元組與這裡算雜湊的位元組不一樣，簽章必定不符 ——
# 症狀是「英文可以、中文一律 401」，非常難查。
cat > "$TMP/body.json" <<EOF
{"target":{"type":"${TARGET}"},"message":{"text":"${TEXT}"}}
EOF
# heredoc 會多一個結尾換行，去掉它，讓送出的位元組與雜湊完全一致
printf '%s' "$(cat "$TMP/body.json")" > "$TMP/req.json"

# ── 簽章 ───────────────────────────────────────────────────────────────────
TS="$(date +%s)"
NONCE="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || openssl rand -hex 16)"
BODY_HASH="$(openssl dgst -sha256 -hex < "$TMP/req.json" | awk '{print $NF}')"

# canonical string：五段，以 \n 串接，「沒有」結尾換行
CANONICAL="$(printf '%s\n%s\n%s\n%s\n%s' \
  "POST" "$PATH_NOTIFY" "$TS" "$NONCE" "$BODY_HASH")"
SIG="$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)"

# ── 送出 ───────────────────────────────────────────────────────────────────
echo "→ POST ${BASE}${PATH_NOTIFY}  target=${TARGET}"
RESPONSE="$(curl -sS -X POST "${BASE}${PATH_NOTIFY}" \
  -H 'Content-Type: application/json' \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -H "X-Timestamp: ${TS}" \
  -H "X-Nonce: ${NONCE}" \
  -H "X-Signature: ${SIG}" \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid 2>/dev/null || openssl rand -hex 16)" \
  --data-binary "@$TMP/req.json")"

echo "$RESPONSE"

ID="$(printf '%s' "$RESPONSE" | sed -n 's/.*"notificationId":"\([^"]*\)".*/\1/p')"
if [ -z "$ID" ]; then
  echo "沒有拿到 notificationId，上面就是伺服器的回應。" >&2
  exit 1
fi

# ── 查結果 ─────────────────────────────────────────────────────────────────
# 202 只代表「已受理」。真正送出是非同步的，等一下再查。
echo
echo "→ 等待派送…"
sleep 3

GET_PATH="${PATH_NOTIFY}/${ID}"
TS2="$(date +%s)"
NONCE2="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || openssl rand -hex 16)"
EMPTY_HASH="$(printf '' | openssl dgst -sha256 -hex | awk '{print $NF}')"
CANONICAL2="$(printf '%s\n%s\n%s\n%s\n%s' "GET" "$GET_PATH" "$TS2" "$NONCE2" "$EMPTY_HASH")"
SIG2="$(printf '%s' "$CANONICAL2" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)"

curl -sS "${BASE}${GET_PATH}" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -H "X-Timestamp: ${TS2}" \
  -H "X-Nonce: ${NONCE2}" \
  -H "X-Signature: ${SIG2}"
echo
echo
echo "status 的意思："
echo "  QUEUED    已受理，還沒送"
echo "  SENDING   派送中"
echo "  SUCCEEDED LINE 已接受全部批次"
echo "  PARTIAL   部分批次失敗"
echo "  FAILED    全部失敗（看 batches[].errorCode）"
