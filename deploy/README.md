# 部署到 server（notif.azndev.com）

現況：DNS 已指向 server 並走 Cloudflare proxy，TLS 正常。
但 `https://notif.azndev.com/*` 目前**任何路徑**都回同一個 200 HTML 佔位頁
（含 `/nonexistent-xyz`），代表 nginx 還沒把流量導到我們的 app。

> ⚠️ 這個狀態很危險：LINE Console 的「Verify」只檢查是否回 200，
> **現在按下去會顯示成功，但事件根本沒進到我們的 app**。
> 一定要用下面 §5 的方式實際驗證，不要只信 Verify 的綠勾。

---

## 0. 先確認 port

8080 已被占用，預設改用 **19080**。先確認它是空的：

```bash
ss -ltnp | grep -E ':(19080|5432)\s' || echo "19080 與 5432 都沒被占用"
```

有衝突就換一個，並且**同時**改兩個地方：

| 檔案 | 位置 |
|---|---|
| `.env` | `APP_PORT=19080` |
| nginx 設定 | `upstream notifyline { server 127.0.0.1:19080; }` |

> PostgreSQL 容器**沒有**對主機開 port，不會跟 server 上既有的 5432 衝突。

---

## 1. 建立部署目錄與設定

**server 上不需要 JDK / Maven / Node，也不需要 clone 整個 repo** ——
image 由 CI 建好推到 GHCR，server 只要 Docker。

```bash
sudo mkdir -p /opt/notifyline && cd /opt/notifyline

# compose 檔會由 CI 的 deploy job 自動送上來。
# 手動先跑一次的話：
curl -sSL -o docker-compose.yml   https://raw.githubusercontent.com/Alien7666/NotificatioLineBot/main/docker/docker-compose.prod.yml

# .env 由你自己建立，永遠不經過 CI
sudo nano .env
```

編輯 `.env`：

```bash
# LINE —— 從 LINE Developers Console 取得
LINE_CHANNEL_TOKEN=<Messaging API 分頁 → Channel access token (long-lived) → Issue>
LINE_CHANNEL_SECRET=<Basic settings → Channel secret>

# 加密金鑰（保護所有 client secret）
APP_SECRET_ENC_KEY=<openssl rand -base64 32 的輸出>

APP_PUBLIC_BASE_URL=https://notif.azndev.com
APP_ALLOWED_URI_HOSTS=azndev.com

DB_URL=jdbc:postgresql://postgres:5432/notifyline
DB_USER=notifyline
DB_PASSWORD=<openssl rand -base64 24 的輸出>

APP_PORT=19080
SPRING_PROFILES_ACTIVE=prod
```

> **Channel ID 用不到** —— 我們的 app 只需要 Channel Secret（驗簽）與
> Channel Access Token（發訊息）。Channel ID 是 LINE Login 才用的。

權限收緊：

```bash
sudo chown root:root .env && sudo chmod 600 .env
```

> ⚠️ `APP_SECRET_ENC_KEY` **遺失就等於所有 client 憑證報廢**（無法解密，只能全部重發）。
>
> 它是什麼、為什麼非要不可、怎麼備份、有沒有其他選項 ——
> 見 [金鑰管理.md](金鑰管理.md)。**部署前先讀完那份。**

---

## 2. 啟動

private repo 的 image 需要先登入 GHCR（用有 `read:packages` scope 的 PAT）：

```bash
echo "<你的 GitHub PAT>" | docker login ghcr.io -u Alien7666 --password-stdin
docker compose --env-file .env up -d
```

> 之後 CI 的 deploy job 會用短效的 `GITHUB_TOKEN` 自己登入，
> 這一步只是手動先跑一次時需要。

確認只綁在 localhost：

```bash
ss -ltnp | grep 19080
# 應該看到 127.0.0.1:19080，不是 0.0.0.0:19080
curl -sS http://127.0.0.1:19080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

---

## 3. nginx

```bash
sudo cp deploy/nginx/notif.azndev.com.conf /etc/nginx/conf.d/
sudo nginx -t && sudo systemctl reload nginx
```

### 這份設定與你 MinIO 那份刻意不同的地方

| 差異 | 理由 |
|---|---|
| `client_max_body_size 1m`（不是 10G） | 純 JSON API，應用層本身就把 body 上限設在 64KB |
| 超時 30s（不是 300s） | 請求都很短。但仍比 LINE 的 webhook 逾時寬鬆 |
| **不開** `proxy_intercept_errors`、**不 include** 錯誤頁 | 這個 API 的 4xx **本身就是契約** —— 呼叫端要靠 `{"error":{"code":"AUTH_NONCE_REPLAY"}}` 分支處理。換成 HTML 錯誤頁對方就看不懂了 |
| 逐一列出 location，`location / { return 404; }` | 預設拒絕。日後新增端點要明確開放，不會有人不小心把管理介面暴露出去 |
| `/actuator/health` 單獨開，不開整個 `/actuator` | `/actuator/env`、`/actuator/configprops` 會洩漏設定值 |
| `proxy_request_buffering off` + 不做 body 轉換 | **HMAC 驗簽是對原始 body bytes 做的**。任何改動 body 的模組（gzip、sub_filter）都會讓簽章對不上，而錯誤只會是一句「Invalid signature」 |
| `modsecurity off` | 與你 MinIO 的處理一致 —— ModSecurity 對 JSON POST 常誤判，會把 LINE 的 webhook 擋在門外 |

> `listen 443 ssl http2;` 沿用你現有寫法。nginx 1.25.1+ 建議改成
> `listen 443 ssl;` + 獨立的 `http2 on;`，但既有寫法仍可運作，這裡不動它。

---

## 4. Cloudflare

DNS 目前是 proxied（橘雲）。要注意兩點：

1. **SSL/TLS 模式**必須是 **Full (strict)** 或 **Full**。若是 Flexible，Cloudflare 會用 HTTP 連 origin，nginx 的 443 收不到。
2. **WAF / Bot Fight Mode** 可能擋掉 LINE 的 webhook。若 §5 驗證失敗但 origin 直連正常，就在 Cloudflare 加一條 WAF 例外：
   `Hostname eq "notif.azndev.com" and http.request.uri.path eq "/line/webhook"` → Skip。

也可以先把橘雲關掉（DNS only）確認端到端通了，再開回來。

---

## 5. 驗證（**不要只信 LINE 的 Verify 綠勾**）

### 5.1 確認流量真的到我們的 app

```bash
curl -sS -o /dev/null -w "%{http_code}\n" \
  -X POST https://notif.azndev.com/line/webhook \
  -H 'Content-Type: application/json' \
  --data-binary '{"destination":"U0","events":[]}'
```

| 結果 | 意義 |
|---|---|
| **400 或 403** | ✅ 正確 —— 到我們的 app 了，被缺簽章擋下 |
| **200 + HTML** | ✗ 還是佔位頁，nginx 沒生效 |
| **404** | ✗ location 沒對上，或走到 `location / { return 404; }` |
| **502 / 504** | ✗ nginx 到得了但 app 沒起來，查 `docker compose logs app` |

再確認回的是 JSON 不是 HTML：

```bash
curl -sSI -X POST https://notif.azndev.com/line/webhook \
  -H 'Content-Type: application/json' --data-binary '{"events":[]}' | grep -i content-type
# 期望：application/json   （若是 text/html 就還是佔位頁）
```

### 5.2 健康檢查

```bash
curl -sS https://notif.azndev.com/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
```

### 5.3 確認不該公開的路徑真的擋住

```bash
for p in /actuator/env /actuator/configprops / /admin; do
  printf "%-24s %s\n" "$p" "$(curl -sS -o /dev/null -w '%{http_code}' https://notif.azndev.com$p)"
done
# 全部應該是 404
```

### 5.4 用正確簽章送一個真的事件

`<CHANNEL_SECRET>` 換成你的（**這行只在 server 上執行，不要貼進聊天室**）：

```bash
BODY='{"destination":"U0","events":[]}'
printf '%s' "$BODY" > /tmp/wh.json
SIG=$(openssl dgst -sha256 -hmac '<CHANNEL_SECRET>' -binary < /tmp/wh.json | base64)

curl -sS -o /dev/null -w "%{http_code}\n" \
  -X POST https://notif.azndev.com/line/webhook \
  -H 'Content-Type: application/json' \
  -H "x-line-signature: $SIG" \
  --data-binary @/tmp/wh.json
# 期望：200
```

**這一步通過才代表 Channel Secret 設對了。**

> 一定要用 `--data-binary @檔案`，不要用 `--data-raw`。
> 含多位元組字元時 shell 的 argv 轉碼會讓簽章對不上。

---

## 6. LINE Console 設定

Messaging API 分頁：

| 設定 | 值 |
|---|---|
| Webhook URL | `https://notif.azndev.com/line/webhook` |
| Use webhook | **開啟** |
| **Auto-reply messages** | **關閉** — 否則 LINE 的罐頭回覆會蓋過我們的 |
| Greeting messages | 關閉（我們自己發歡迎訊息） |

---

## 7. 實機驗證

| 動作 | 預期 | 怎麼查 |
|---|---|---|
| 手機加 Bot 好友 | 收到歡迎訊息 | `docker compose logs -f app` 應出現 `使用者加入好友` |
| — | DB 有該筆 | `SELECT line_user_id, status FROM line_user;` |
| 傳「我的ID」 | Bot 回你的 User ID | — |
| 傳「說明」 | Bot 回指令清單 | — |
| 封鎖 Bot | 狀態變 BLOCKED | `SELECT status FROM line_user;` |

> **若收得到事件但收不到回覆**，且日誌出現
> `code=401 ... Authentication failed. Confirm that the access token`，
> 代表 `LINE_CHANNEL_TOKEN` 沒設或設錯 —— 那是**與 Channel Secret 不同的東西**，
> 要到 Messaging API 分頁按 Issue 產生。

拿到自己的 User ID 後，建立 OWNER 憑證：

```bash
docker compose -f docker/docker-compose.yml --env-file .env run --rm --no-deps app \
  --create-client --name=owner --owner --line-user-id=<你的 User ID>
```

**secret 只會顯示這一次**，立刻存進密碼管理器。

---

## 疑難排解

| 症狀 | 原因 |
|---|---|
| 所有路徑回 200 HTML | nginx 設定沒載入，或有另一個 server block 先匹配到。`nginx -T \| grep -A2 notif.azndev.com` 確認 |
| 502 Bad Gateway | app 沒起來，或 upstream port 與 `APP_PORT` 不一致 |
| 直連 origin 正常、經 Cloudflare 失敗 | Cloudflare WAF / Bot Fight Mode。先關橘雲確認，再加 WAF 例外 |
| webhook 一直 `Invalid API signature` | `LINE_CHANNEL_SECRET` 錯，或中間層改動了 body（gzip、ModSecurity） |
| 收得到事件但回不了訊息（401） | 缺 `LINE_CHANNEL_TOKEN` |
| 容器起不來，日誌提到 `app.crypto.keys` | `APP_SECRET_ENC_KEY` 沒設或不是 32 bytes。`openssl rand -base64 32` |
| postgres 起不來 | PostgreSQL 18 要掛 `/var/lib/postgresql`。若曾用舊設定跑過需 `docker compose down -v` |
