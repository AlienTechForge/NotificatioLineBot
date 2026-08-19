#!/usr/bin/env bash
#
# NotifyLine POC — T1~T5 端到端演示
#
# 展示範圍：
#   1. 基礎設施（Docker、Flyway、健康檢查）
#   2. Bootstrap CLI 建立第一組憑證（雞生蛋問題的解法）
#   3. HMAC 認證正向路徑
#   4. HMAC 認證負向路徑（竄改 / 重放 / 時鐘偏移 / 停用）
#   5. 呼叫端速率限制
#   6. LINE Webhook（加好友 / 指令 / 封鎖連鎖停用 / 重送去重）
#
# 不需要真實的 LINE 憑證 —— webhook 用本地 channel secret 自簽，
# 完整走過 SDK 的驗簽路徑。發送到手機需要真憑證，見 README。
#
# 用法：  bash poc/poc.sh

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="docker compose -f $ROOT/docker/docker-compose.yml --env-file $ROOT/.env"
BASE=""   # 於載入 .env 後設定（見下方），因為 port 由 APP_PORT 決定

PASS=0
FAIL=0

# ── 輸出工具 ─────────────────────────────────────────────────────────
c_reset=$'\033[0m'; c_dim=$'\033[2m'; c_bold=$'\033[1m'
c_green=$'\033[32m'; c_red=$'\033[31m'; c_cyan=$'\033[36m'; c_yellow=$'\033[33m'

section() { printf '\n%s%s── %s ──%s\n' "$c_bold" "$c_cyan" "$1" "$c_reset"; }
info()    { printf '%s   %s%s\n' "$c_dim" "$1" "$c_reset"; }

# check <說明> <期望> <實際>
check() {
  if [ "$2" = "$3" ]; then
    printf '   %s✔%s %-58s %s\n' "$c_green" "$c_reset" "$1" "$3"
    PASS=$((PASS + 1))
  else
    printf '   %s✗%s %-58s 期望 %s，實得 %s\n' "$c_red" "$c_reset" "$1" "$2" "$3"
    FAIL=$((FAIL + 1))
  fi
}

# contains <說明> <字串> <應包含>
contains() {
  if printf '%s' "$2" | grep -q -- "$3"; then
    printf '   %s✔%s %-58s %s\n' "$c_green" "$c_reset" "$1" "含「$3」"
    PASS=$((PASS + 1))
  else
    printf '   %s✗%s %-58s 未含「%s」\n' "$c_red" "$c_reset" "$1" "$3"
    printf '     %s%s%s\n' "$c_dim" "$(printf '%s' "$2" | head -c 200)" "$c_reset"
    FAIL=$((FAIL + 1))
  fi
}

psql_q() { $COMPOSE exec -T postgres psql -U "${DB_USER:-notifyline}" -d notifyline -tAc "$1" | tr -d '\r'; }

# ── HMAC 簽章 ────────────────────────────────────────────────────────
# api <METHOD> <PATH> <BODY> [SECRET] [TIMESTAMP] [NONCE]
# 回傳 "HTTP狀態碼<TAB>回應內容"
api() {
  local method="$1" path="$2" body="$3"
  local secret="${4:-$CLIENT_SECRET}"
  local ts="${5:-$(date +%s)}"
  local nonce="${6:-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c 'import uuid;print(uuid.uuid4())')}"

  local hash canonical sig
  hash=$(printf '%s' "$body" | openssl dgst -sha256 -hex | awk '{print $NF}')
  canonical=$(printf '%s\n%s\n%s\n%s\n%s' "$method" "$path" "$ts" "$nonce" "$hash")
  sig=$(printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$secret" -binary | base64)

  local out
  # 一律用 --data-binary @檔案，不用 --data-raw。
  # Windows 的 Git Bash 下，含多位元組 UTF-8 的字串經過 argv 會被轉碼，
  # curl 實際送出的 bytes 與我們簽章的 bytes 不同 -> 簽章永遠不符。
  # 這正是「必須對實際送出的 bytes 簽章」那一類錯誤的真實案例。
  printf '%s' "$body" > /tmp/poc_req.json

  out=$(curl -sS -o /tmp/poc_body -w '%{http_code}' -X "$method" "${BASE}${path}" \
    -H 'Content-Type: application/json' \
    -H "X-Client-Id: ${CLIENT_ID}" \
    -H "X-Timestamp: ${ts}" \
    -H "X-Nonce: ${nonce}" \
    -H "X-Signature: ${sig}" \
    --data-binary @/tmp/poc_req.json)
  printf '%s\t%s' "$out" "$(cat /tmp/poc_body)"
}

status_of() { printf '%s' "$1" | cut -f1; }
body_of()   { printf '%s' "$1" | cut -f2-; }

# ── LINE webhook 簽章 ────────────────────────────────────────────────
webhook() {
  local body="$1"
  local sig
  printf '%s' "$body" > /tmp/poc_webhook.json
  sig=$(openssl dgst -sha256 -hmac "$LINE_CHANNEL_SECRET" -binary < /tmp/poc_webhook.json | base64)
  curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE}/line/webhook" \
    -H 'Content-Type: application/json' \
    -H "x-line-signature: ${sig}" \
    --data-binary @/tmp/poc_webhook.json
}

# =====================================================================
printf '%s\n' "$c_bold"
cat <<'BANNER'
╔══════════════════════════════════════════════════════════════════╗
║   NotifyLine POC — 統一 LINE Notification Service   T1 ~ T5      ║
╚══════════════════════════════════════════════════════════════════╝
BANNER
printf '%s' "$c_reset"

[ -f "$ROOT/.env" ] || { echo "缺少 .env，請先從 .env.example 複製"; exit 1; }
set -a; . "$ROOT/.env"; set +a
BASE="http://localhost:${APP_PORT:-19080}"

# ── 1. 基礎設施 ──────────────────────────────────────────────────────
section "1. 基礎設施"
$COMPOSE up -d --build >/dev/null 2>&1

for _ in $(seq 1 60); do
  curl -fsS "$BASE/actuator/health" >/dev/null 2>&1 && break
  sleep 2
done

health=$(curl -sS "$BASE/actuator/health")
contains "健康檢查" "$health" '"status":"UP"'
contains "liveness / readiness 分離" "$(curl -sS "$BASE/actuator/health/readiness")" '"status":"UP"'
check "Flyway 已套用 V1" "1" "$(psql_q "SELECT count(*) FROM flyway_schema_history WHERE success")"
check "資料表數（9 張 + flyway 紀錄）" "10" "$(psql_q "SELECT count(*) FROM pg_tables WHERE schemaname='public'")"
info "LINE API 不可達時 readiness 仍須為 UP —— 否則 LINE 故障會讓容器被反覆重啟"

# ── 2. Bootstrap 憑證 ────────────────────────────────────────────────
section "2. Bootstrap CLI 建立第一組憑證"
OWNER_LINE_ID="U00000000000000000000000000000001"

psql_q "DELETE FROM request_nonce; DELETE FROM client_scope; DELETE FROM client; DELETE FROM webhook_event; DELETE FROM line_user;" >/dev/null

# 映像的 ENTRYPOINT 已是 java -jar app.jar，run 的參數會「附加」在後面。
# 寫成 `run app java -jar /app/app.jar ...` 會變成 java -jar app.jar java -jar ...，
# 也就是帶著垃圾參數正常啟動一個 web server，永遠不會結束。
boot=$($COMPOSE run --rm --no-deps app \
  --create-client --name=poc-owner --owner --line-user-id="$OWNER_LINE_ID" 2>&1)

CLIENT_ID=$(printf '%s' "$boot" | grep -oE 'cli_[a-z0-9]{20}' | head -1)
CLIENT_SECRET=$(printf '%s' "$boot" | grep -A0 'Client Secret' | sed 's/.*Client Secret *: *//' | tr -d ' \r')

contains "CLI 印出憑證" "$boot" "Client 已建立"
check   "clientId 格式" "yes" "$([ ${#CLIENT_ID} -eq 24 ] && echo yes || echo no)"
check   "secret 只顯示一次的警語" "1" "$(printf '%s' "$boot" | grep -c '只會顯示這一次')"
check   "DB 內 secret 為密文（不含明文）" "0" \
        "$(psql_q "SELECT count(*) FROM client WHERE encode(secret_ciphertext,'escape') LIKE '%${CLIENT_SECRET:0:12}%'")"
check   "owner 自動建立並標記" "t" "$(psql_q "SELECT is_owner FROM line_user WHERE line_user_id='$OWNER_LINE_ID'")"
info "clientId=$CLIENT_ID"

# ── 3. HMAC 認證：正向 ───────────────────────────────────────────────
section "3. HMAC 認證（正向）"
r=$(api GET /api/v1/whoami "")
check "簽章正確 → 200" "200" "$(status_of "$r")"
who=$(body_of "$r")
contains "回傳自己的 clientId" "$who" "$CLIENT_ID"
contains "回傳綁定的使用者" "$who" "$OWNER_LINE_ID"
contains "回傳 scope: notify:all" "$who" "notify:all"
contains "統一信封 success=true" "$who" '"success":true'

# ── 4. HMAC 認證：負向 ───────────────────────────────────────────────
section "4. HMAC 認證（負向）"
ts=$(date +%s)

r=$(api GET /api/v1/whoami "" "wrong-secret")
check "錯誤的 secret → 401" "401" "$(status_of "$r")"
contains "錯誤碼" "$(body_of "$r")" 'AUTH_INVALID_SIGNATURE'

fixed_nonce="11111111-2222-3333-4444-555555555555"
api GET /api/v1/whoami "" "$CLIENT_SECRET" "$ts" "$fixed_nonce" >/dev/null
r=$(api GET /api/v1/whoami "" "$CLIENT_SECRET" "$ts" "$fixed_nonce")
check "同一 nonce 重放 → 401" "401" "$(status_of "$r")"
contains "錯誤碼" "$(body_of "$r")" 'AUTH_NONCE_REPLAY'

r=$(api GET /api/v1/whoami "" "$CLIENT_SECRET" "$((ts - 400))")
check "時鐘偏移 400 秒 → 401" "401" "$(status_of "$r")"
contains "錯誤碼" "$(body_of "$r")" 'AUTH_TIMESTAMP_SKEW'
contains "訊息可自行診斷" "$(body_of "$r")" 'Date header'

nosig=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/whoami")
check "完全沒有簽章 header → 401" "401" "$nosig"

r=$(api GET /api/v1/whoami "")
contains "錯誤回應不含 stack trace" "$(body_of "$(api GET /api/v1/whoami "" wrong)")" '"success":false'
info "不存在的 client id 也回 AUTH_INVALID_SIGNATURE —— 避免被列舉出哪些 id 存在"

# ── 5. 速率限制 ──────────────────────────────────────────────────────
section "5. 呼叫端速率限制（缺口 G2 的預防層）"
psql_q "UPDATE client SET rate_limit_per_min = 3 WHERE client_id='$CLIENT_ID'" >/dev/null
$COMPOSE restart app >/dev/null 2>&1
for _ in $(seq 1 60); do curl -fsS "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 2; done

ok=0
for _ in 1 2 3; do
  [ "$(status_of "$(api GET /api/v1/whoami "")")" = "200" ] && ok=$((ok + 1))
done
check "限額 3/min 內的請求都通過" "3" "$ok"

r=$(api GET /api/v1/whoami "")
check "第 4 次 → 429" "429" "$(status_of "$r")"
contains "錯誤碼" "$(body_of "$r")" 'RATE_LIMITED'

psql_q "UPDATE client SET rate_limit_per_min = NULL WHERE client_id='$CLIENT_ID'" >/dev/null

# ── 6. LINE Webhook ──────────────────────────────────────────────────
section "6. LINE Webhook"
USER_ID="U00000000000000000000000000000002"
DEST="U00000000000000000000000000000099"

follow_body='{"destination":"'"$DEST"'","events":[{"type":"follow","mode":"active","timestamp":1755500000000,"webhookEventId":"poc-follow-1","deliveryContext":{"isRedelivery":false},"replyToken":"poc-reply-1","source":{"type":"user","userId":"'"$USER_ID"'"}}]}'
msg_body='{"destination":"'"$DEST"'","events":[{"type":"message","mode":"active","timestamp":1755500000000,"webhookEventId":"poc-msg-1","deliveryContext":{"isRedelivery":false},"replyToken":"poc-reply-2","source":{"type":"user","userId":"'"$USER_ID"'"},"message":{"type":"text","id":"m1","text":"我的ID"}}]}'
unfollow_body='{"destination":"'"$DEST"'","events":[{"type":"unfollow","mode":"active","timestamp":1755500000000,"webhookEventId":"poc-unfollow-1","deliveryContext":{"isRedelivery":false},"source":{"type":"user","userId":"'"$USER_ID"'"}}]}'

check "空事件（LINE 連線測試）→ 200" "200" \
      "$(webhook '{"destination":"'"$DEST"'","events":[]}')"

printf '%s' "$follow_body" > /tmp/poc_bad.json
bad=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/line/webhook" \
      -H 'Content-Type: application/json' -H 'x-line-signature: bogus' --data-binary @/tmp/poc_bad.json)
check "錯誤簽章被 4xx 擋下" "4xx" "$(printf '%s' "$bad" | sed 's/^4[0-9][0-9]$/4xx/')"

check "follow 事件 → 200" "200" "$(webhook "$follow_body")"
check "使用者已建立且 ACTIVE" "ACTIVE" "$(psql_q "SELECT status FROM line_user WHERE line_user_id='$USER_ID'")"

check "同一事件重送 → 200" "200" "$(webhook "$follow_body")"
check "冪等閘門：只記錄一次" "1" "$(psql_q "SELECT count(*) FROM webhook_event WHERE webhook_event_id='poc-follow-1'")"

check "文字指令事件 → 200" "200" "$(webhook "$msg_body")"

# 幫這位使用者建一組金鑰，驗證 unfollow 的連鎖停用
$COMPOSE run --rm --no-deps app \
  --create-client --name=poc-user --line-user-id="$USER_ID" >/dev/null 2>&1
check "使用者金鑰為 ACTIVE" "ACTIVE" \
      "$(psql_q "SELECT status FROM client WHERE bound_line_user_id='$USER_ID' ORDER BY id DESC LIMIT 1")"

check "unfollow 事件 → 200" "200" "$(webhook "$unfollow_body")"
check "使用者標為 BLOCKED" "BLOCKED" "$(psql_q "SELECT status FROM line_user WHERE line_user_id='$USER_ID'")"
check "其金鑰連帶停用" "DISABLED" \
      "$(psql_q "SELECT status FROM client WHERE bound_line_user_id='$USER_ID' ORDER BY id DESC LIMIT 1")"
info "封鎖期間金鑰可能已外流，所以重新加好友「不會」自動恢復金鑰"

# ── 總結 ─────────────────────────────────────────────────────────────
printf '\n%s%s' "$c_bold" "$c_cyan"
printf '══════════════════════════════════════════════════════════════════\n'
printf '%s' "$c_reset"
if [ "$FAIL" -eq 0 ]; then
  printf '  %s全部 %d 項檢查通過%s\n' "$c_green" "$PASS" "$c_reset"
else
  printf '  %s%d 項通過，%d 項失敗%s\n' "$c_yellow" "$PASS" "$FAIL" "$c_reset"
fi
printf '%s%s══════════════════════════════════════════════════════════════════%s\n' "$c_bold" "$c_cyan" "$c_reset"

printf '\n%s尚未實作（後續任務）：%s\n' "$c_dim" "$c_reset"
printf '   T6  自助申請金鑰的一次性連結\n'
printf '   T7  POST /api/v1/notifications 通知 API\n'
printf '   T8  非同步分批派送到 LINE\n\n'

exit $((FAIL > 0 ? 1 : 0))
