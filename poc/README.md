# POC — T1 ~ T5 端到端演示

## 快速執行

```bash
cp .env.example .env      # 首次才需要，填法見下方
bash poc/poc.sh
```

腳本會自己起 Docker、建憑證、跑完所有檢查，最後印出通過／失敗統計。

---

## 演示了什麼

| # | 主題 | 驗證內容 |
|---|---|---|
| 1 | **基礎設施** | Docker 起得來、Flyway 套用 9 張表、liveness/readiness 分離 |
| 2 | **Bootstrap CLI** | 不經過 API 建立第一組憑證（雞生蛋問題的解法）；DB 內是密文；owner 自動標記 |
| 3 | **HMAC 認證（正向）** | 簽章正確 → 200，`whoami` 回傳自己的 clientId／綁定使用者／scope |
| 4 | **HMAC 認證（負向）** | 錯誤 secret、nonce 重放、時鐘偏移、缺 header 全部 401，錯誤碼正確 |
| 5 | **速率限制** | per-client 限額，超限 429 + `Retry-After` |
| 6 | **LINE Webhook** | 空事件回 200、錯誤簽章被擋、follow 建立使用者、**重送去重**、unfollow 連鎖停用金鑰 |

### 不需要真實 LINE 憑證

Webhook 的部分用本地的 `LINE_CHANNEL_SECRET` 自己簽請求，**完整走過 SDK 的驗簽路徑** ——
驗的是我們的處理邏輯，不是 LINE 的伺服器。

真正發訊息到手機才需要真憑證，見下一節。

---

## 接上真實 LINE（讓手機真的收到通知）

### 需要準備

| # | 項目 | 怎麼拿 |
|---|---|---|
| 1 | Channel Access Token + Channel Secret | LINE Developers Console → 建 Provider → 建 **Messaging API** channel。Secret 在 Basic settings，Token 在 Messaging API 分頁按 Issue |
| 2 | 公開 HTTPS endpoint 指到 `:8080` | 反向代理，或開發期用 Cloudflare Tunnel |
| 3 | 你的 LINE User ID | **不用事先準備** —— 加好友後傳「我的ID」，Bot 會回你 |

> ⚠️ 務必用**另開的測試帳號**，不要用正式的。
> dev 環境發測試訊息會直接進真實使用者的手機。

### 步驟

**1. 填入憑證**

```bash
# .env
LINE_CHANNEL_TOKEN=<你的 token>
LINE_CHANNEL_SECRET=<你的 secret>
APP_PUBLIC_BASE_URL=https://<你的公開網址>
```

**2. 重啟**

```bash
docker compose -f docker/docker-compose.yml --env-file .env up -d
```

**3. 設定 Webhook**

LINE Developers Console → Messaging API →
Webhook URL 填 `https://<你的公開網址>/line/webhook` → 按 **Verify** → 應該通過 → 開啟 **Use webhook**

同一頁把 **Auto-reply messages** 關掉，否則 LINE 的預設罐頭回覆會蓋過我們的。

**4. 實機驗證**

| 動作 | 預期 |
|---|---|
| 手機加 Bot 好友 | 收到歡迎訊息；`line_user` 出現該筆且 `status = ACTIVE` |
| 傳「我的ID」 | Bot 回你的 LINE User ID |
| 傳「說明」 | Bot 回可用指令清單 |
| 封鎖 Bot | `line_user.status` 變 `BLOCKED`，其金鑰變 `DISABLED` |

**5. 把自己設為 owner**

用步驟 4 拿到的 User ID：

```bash
docker compose -f docker/docker-compose.yml --env-file .env run --rm --no-deps app \
  --create-client --name=my-owner --owner --line-user-id=<你的 User ID>
```

記下印出的 clientId 與 secret（**只會顯示這一次**）。

---

## 自己打 API

`whoami` 是呼叫端的自我診斷端點 —— 回答「我的憑證有效嗎、我有哪些權限」。

```bash
CLIENT_ID=cli_xxxxxxxxxxxxxxxxxxxx
SECRET=xxxxxxxx
PATH_=/api/v1/whoami

TS=$(date +%s)
NONCE=$(uuidgen | tr 'A-Z' 'a-z')
HASH=$(printf '' | openssl dgst -sha256 -hex | awk '{print $NF}')
CANON=$(printf 'GET\n%s\n%s\n%s\n%s' "$PATH_" "$TS" "$NONCE" "$HASH")
SIG=$(printf '%s' "$CANON" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)

curl -sS "http://localhost:8080${PATH_}" \
  -H "X-Client-Id: $CLIENT_ID" \
  -H "X-Timestamp: $TS" \
  -H "X-Nonce: $NONCE" \
  -H "X-Signature: $SIG"
```

完整簽章規格與各語言範例見 [05-API契約](../Docs/plan/05-API契約.md)。

---

## 還沒做的

| 任務 | 內容 |
|---|---|
| **T6** | 自助申請金鑰的一次性連結（LINE 傳「申請金鑰」→ 收到連結 → 開啟取得憑證 → 再開回 410） |
| **T7** | `POST /api/v1/notifications` —— 通知 API 本體 |
| **T8** | 非同步分批派送（500 人一批、重試、斷路器） |
| **T9** | 查詢 API、稽核、指標、資料保留清理 |
| **T10** | CI/CD workflow |

目前 `/api/v1/**` 只有 `whoami`；送出通知要等 T7。

---

## 疑難排解

| 症狀 | 原因 |
|---|---|
| `缺少 .env` | 從 `.env.example` 複製並填值 |
| Maven 建置 `PKIX path building failed` | 防毒（如 Avast）MITM TLS。用 `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"` |
| postgres 容器啟動失敗 | PostgreSQL 18 要掛 `/var/lib/postgresql`（不是 `.../data`）。若曾用舊設定跑過，需 `docker compose down -v` 清掉舊 volume |
| LINE Console「Verify」失敗 | 檢查公開網址是否真的通到 `:8080`；`curl -X POST <url>/line/webhook -d '{"events":[]}'` 應回 4xx（缺簽章）而非 404 |
| 所有請求 `401 AUTH_INVALID_SIGNATURE` | 最常見是**簽章的 bytes 與送出的 bytes 不同**。兩個典型原因：(1) 序列化兩次（簽章時一次、送出時 HTTP 函式庫又一次，空白處理不同）；(2) **Windows 的 Git Bash 下用 `curl --data-raw` 傳含中文的 body** —— 多位元組 UTF-8 經過 argv 會被轉碼，curl 實際送出的 bytes 和 `printf` 給 openssl 的不一樣。改用 `--data-binary @檔案` |
| 所有請求 `401 AUTH_TIMESTAMP_SKEW` | 時鐘偏移。比對回應的 `Date` header |
