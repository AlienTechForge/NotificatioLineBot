#!/usr/bin/env bash
#
# 在 server 上執行的部署腳本。
#
#   bash deploy/deploy.sh
#
# 會做：檢查前置條件 -> 建置 -> 啟動 -> 等健康 -> 驗證 -> 印出後續步驟。
# 任何一步失敗就停下並說明原因，不會留下半調子的狀態。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE="docker compose -f docker/docker-compose.yml --env-file .env"

c_reset=$'\033[0m'; c_bold=$'\033[1m'; c_dim=$'\033[2m'
c_green=$'\033[32m'; c_red=$'\033[31m'; c_cyan=$'\033[36m'; c_yellow=$'\033[33m'

step() { printf '\n%s%s── %s ──%s\n' "$c_bold" "$c_cyan" "$1" "$c_reset"; }
ok()   { printf '   %s✔%s %s\n' "$c_green" "$c_reset" "$1"; }
warn() { printf '   %s!%s %s\n' "$c_yellow" "$c_reset" "$1"; }
die()  { printf '\n   %s✗ %s%s\n\n' "$c_red" "$1" "$c_reset"; exit 1; }

# ── 1. 前置檢查 ──────────────────────────────────────────────────────
step "1. 前置檢查"

command -v docker >/dev/null || die "找不到 docker"
docker compose version >/dev/null 2>&1 || die "找不到 docker compose（v2）"
ok "docker $(docker --version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"

[ -f .env ] || die ".env 不存在。先 cp .env.example .env 並填入設定"

# shellcheck disable=SC1091
set -a; . ./.env; set +a

missing=""
for var in LINE_CHANNEL_TOKEN LINE_CHANNEL_SECRET APP_SECRET_ENC_KEY APP_PUBLIC_BASE_URL DB_USER DB_PASSWORD; do
  [ -z "${!var:-}" ] && missing="$missing $var"
done
[ -n "$missing" ] && die ".env 缺少：$missing"
ok ".env 必要變數齊全"

# APP_SECRET_ENC_KEY 必須是 base64 的 32 bytes，否則應用會拒絕啟動
keylen=$(printf '%s' "$APP_SECRET_ENC_KEY" | base64 -d 2>/dev/null | wc -c)
[ "$keylen" = "32" ] || die "APP_SECRET_ENC_KEY 解碼後是 ${keylen} bytes，必須恰好 32。產生：openssl rand -base64 32"
ok "APP_SECRET_ENC_KEY 長度正確（32 bytes）"

case "$APP_PUBLIC_BASE_URL" in
  https://*) ok "APP_PUBLIC_BASE_URL 使用 https" ;;
  *) die "APP_PUBLIC_BASE_URL 必須是 https（目前：$APP_PUBLIC_BASE_URL）。金鑰領取連結會用它" ;;
esac

perm=$(stat -c '%a' .env 2>/dev/null || echo "?")
if [ "$perm" = "600" ]; then
  ok ".env 權限 600"
else
  warn ".env 權限是 $perm，建議收緊：sudo chown root:root .env && sudo chmod 600 .env"
fi

APP_PORT="${APP_PORT:-19080}"
if ss -ltn 2>/dev/null | grep -qE "127\.0\.0\.1:${APP_PORT}\s|:::${APP_PORT}\s|0\.0\.0\.0:${APP_PORT}\s"; then
  # 已被我們自己占用是正常的（重新部署）
  if docker ps --format '{{.Names}}' | grep -q '^notifyline-app$'; then
    ok "port ${APP_PORT} 由既有的 notifyline-app 占用（將重新部署）"
  else
    die "port ${APP_PORT} 已被其他程式占用。改 .env 的 APP_PORT 與 nginx 的 upstream"
  fi
else
  ok "port ${APP_PORT} 可用"
fi

# ── 2. 建置與啟動 ────────────────────────────────────────────────────
step "2. 建置與啟動"
$COMPOSE up -d --build || die "建置或啟動失敗。看 $COMPOSE logs"
ok "容器已啟動"

# ── 3. 等待健康 ──────────────────────────────────────────────────────
step "3. 等待服務就緒"
for i in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
    ok "健康檢查通過（$((i * 2)) 秒）"
    break
  fi
  [ "$i" = "90" ] && {
    printf '\n%s最後 40 行日誌：%s\n' "$c_dim" "$c_reset"
    $COMPOSE logs --tail=40 app
    die "180 秒內未就緒"
  }
  sleep 2
done

# ── 4. 本機驗證 ──────────────────────────────────────────────────────
step "4. 本機驗證（繞過 nginx）"

health=$(curl -sS "http://127.0.0.1:${APP_PORT}/actuator/health")
printf '   health : %s\n' "$health"
printf '%s' "$health" | grep -q '"status":"UP"' || die "健康檢查回應異常"

if ss -ltn 2>/dev/null | grep -qE "0\.0\.0\.0:${APP_PORT}\s"; then
  warn "port ${APP_PORT} 綁在 0.0.0.0（對外可直連，繞過 nginx）。檢查 docker-compose.yml"
else
  ok "只綁 127.0.0.1，外部無法直連"
fi

# 沒帶簽章的 webhook 必須被擋（4xx），不是 200
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:${APP_PORT}/line/webhook" \
  -H 'Content-Type: application/json' --data-binary '{"destination":"U0","events":[]}')
case "$code" in
  4*) ok "webhook 無簽章時回 $code（正確）" ;;
  *)  die "webhook 無簽章時回 $code，應為 4xx" ;;
esac

# ── 5. 經 nginx 驗證 ─────────────────────────────────────────────────
step "5. 經 nginx / 對外驗證"

pub=$(curl -sS -o /tmp/deploy_pub.txt -w '%{http_code}|%{content_type}' \
  -X POST "${APP_PUBLIC_BASE_URL}/line/webhook" \
  -H 'Content-Type: application/json' --data-binary '{"destination":"U0","events":[]}' 2>/dev/null)
pubcode="${pub%%|*}"; pubtype="${pub#*|}"

printf '   %s/line/webhook -> HTTP %s (%s)\n' "$APP_PUBLIC_BASE_URL" "$pubcode" "$pubtype"

case "$pubcode|$pubtype" in
  4*"|"*json*)
    ok "對外端點已正確接到本服務"
    ;;
  200*)
    warn "回 200 —— 這幾乎一定是佔位頁，不是我們的 app"
    warn "LINE Console 的「Verify」在這個狀態下會顯示成功，但事件不會進來"
    warn "檢查：sudo nginx -T | grep -A3 'server_name notif'"
    ;;
  50*)
    warn "回 $pubcode —— nginx 到得了但上游有問題。檢查 upstream port 是否為 ${APP_PORT}"
    ;;
  *)
    warn "非預期回應。檢查 nginx 設定與 Cloudflare 的 SSL/TLS 模式（需 Full 或 Full strict）"
    ;;
esac

# 驗證 LINE 憑證
step "6. LINE 憑證驗證"
info_code=$(curl -sS -o /tmp/deploy_botinfo.json -w '%{http_code}' https://api.line.me/v2/bot/info \
  -H "Authorization: Bearer ${LINE_CHANNEL_TOKEN}")
if [ "$info_code" = "200" ]; then
  ok "Channel Access Token 有效"
  grep -oE '"displayName":"[^"]*"' /tmp/deploy_botinfo.json | sed 's/^/   /'
else
  warn "Channel Access Token 驗證失敗（HTTP $info_code）—— 收得到事件但回不了訊息"
fi

wh=$(curl -sS https://api.line.me/v2/bot/channel/webhook/endpoint -H "Authorization: Bearer ${LINE_CHANNEL_TOKEN}")
printf '   LINE 端登記的 webhook：%s\n' "$wh"
printf '%s' "$wh" | grep -q "$APP_PUBLIC_BASE_URL" \
  && ok "與 APP_PUBLIC_BASE_URL 一致" \
  || warn "LINE 端沒有登記（或不一致）。到 Console 的 Messaging API 分頁設定 Webhook URL 並按 Update"

# ── 完成 ─────────────────────────────────────────────────────────────
printf '\n%s%s══════════════════════════════════════════════════════════════%s\n' "$c_bold" "$c_cyan" "$c_reset"
printf '  部署完成\n'
printf '%s%s══════════════════════════════════════════════════════════════%s\n\n' "$c_bold" "$c_cyan" "$c_reset"

cat <<'NEXT'
後續步驟：

  1. LINE Console -> Messaging API
       Webhook URL      : 設為 <APP_PUBLIC_BASE_URL>/line/webhook 並按 Update
       Use webhook      : 開啟
       Auto-reply       : 關閉（否則罐頭回覆會蓋過我們的）
       Greeting message : 關閉

  2. 手機加 Bot 好友 -> 應收到歡迎訊息
     追蹤：  docker compose -f docker/docker-compose.yml --env-file .env logs -f app

  3. 傳「我的ID」取得自己的 LINE User ID，然後建立 OWNER 憑證：
       docker compose -f docker/docker-compose.yml --env-file .env run --rm --no-deps app \
         --create-client --name=owner --owner --line-user-id=<你的 User ID>
     secret 只會顯示這一次。
NEXT
