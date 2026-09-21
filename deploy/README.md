# 部署與操作指南

> 校訂日期：2026-09-21；以 `.github/workflows/ci-cd.yml`、`admin.yml` 與 Compose 為準。
> 文件使用 `notify.example.com` 作為示例；實際網址、網域與帳號由部署環境提供。

## 1. 實際部署流程

main push：GitHub-hosted `Test` 執行 Maven verify → `Build & Push` 推 GHCR →
self-hosted `[self-hosted, Linux, X64]` 的 `Deploy` 在主機執行 Docker Compose。
PR 只跑測試；`v*` tag 會測試及建 image，但不是 main ref，不部署正式環境。
手動 `workflow_dispatch` 選 main 時亦可部署。

沒有 SSH deploy。部署目錄由 `DEPLOY_PATH` Variable 指定，未設使用 runner 的 `$HOME/notifyline`。
CI 把 `docker/docker-compose.prod.yml` 複製為該處 `docker-compose.yml`，由 Secrets／Variables
重建 `.env`；不要把在主機手改 `.env` 當成能跨部署保留的設定。

## 2. 前置設定

- repo 有權使用一台標籤符合的 self-hosted runner，且 runner 使用者可以執行 Docker／Compose。
- GitHub Actions Workflow permissions 允許 read/write；workflow 宣告 `contents: read`、`packages: write`。
- 內建 `GITHUB_TOKEN` 用於 GHCR 登入，無需先建立 PAT 或 SSH key。
- 反向代理、TLS 憑證、DNS、管理門禁須由部署者配置；CI 不安裝或更新 nginx。
- 依目前單實例設計部署；跨實例的 rate limit、profile 排程與站台登入鎖尚未協調。

### Secrets

| 名稱 | 用途 |
|---|---|
| `LINE_CHANNEL_TOKEN` | LINE 發送及 Profile 查詢 |
| `LINE_CHANNEL_SECRET` | webhook 驗簽 |
| `APP_SECRET_ENC_KEY` | 版本 1 的 AES key，32 bytes Base64 |
| `APP_SECRET_ENC_KEY_V2` | 選填，版本 2 的 key；不得任意覆蓋舊版本 |
| `DB_PASSWORD` | PostgreSQL 密碼 |
| `APP_ADMIN_USERNAME`、`APP_ADMIN_PASSWORD` | 後台帳密，需一起設定或一起留空；密碼至少 12 字元 |

GitHub Secrets 是部署來源，主機 `.env` 是執行時副本。後台帳密都留空時，管理介面停用。
key 的備份、全部加密欄位與輪替限制見 [金鑰管理](金鑰管理.md)。

### Variables

| 名稱 | 說明／預設 |
|---|---|
| `APP_PUBLIC_BASE_URL` | 自行配置的 HTTPS 網址，如 `https://notify.example.com` |
| `APP_ALLOWED_URI_HOSTS` | 通知內容允許的連結網域，逗號分隔；空值拒絕全部外部連結 |
| `APP_OWNER_LINE_USER_ID` | 選填，啟動時標記為 owner 的 LINE User ID；此欄屬個人識別資料 |
| `APP_PORT` | 主機 loopback port，預設 19080；容器固定 8080 |
| `DB_USER` | 預設 notifyline |
| `DEPLOY_PATH` | 可選部署路徑，runner 使用者必須可寫 |
| `APP_SECRET_ENC_KEY_VERSION` | 新資料使用的 key 版本，預設 1 |

`DB_URL`、prod profile 由 workflow 寫入。其他 application.yml 可調參數並不會自動由 GitHub
Variables 透傳；若需部署自訂監控間隔等，須擴充 workflow 的 env 產生清單。
Variables 並非 secret；部署網域與 LINE ID 若需隱藏，不能僅依賴變數名稱或 private repo。

## 3. 啟動與版本

一般直接推送 main 或重跑 CI/CD。部署使用 `sha-<commit 前 7 碼>` image，預先記錄
目前容器的 image，健康檢查失敗時嘗試切回。

需要手動操作時，在**已經準備好的部署目錄**使用：

```bash
cd "$DEPLOY_PATH"
export NOTIFYLINE_IMAGE='<已推送且可讀取的完整 image ref>'
docker compose --env-file .env pull app
docker compose --env-file .env up -d
curl -fsS http://127.0.0.1:19080/actuator/health
```

私有 GHCR 若需手動登入，使用核准的短效憑證與 `--password-stdin`，不要把真值放進指令歷史。
CI 本身使用 `GITHUB_TOKEN`。PostgreSQL 不公開 host port，app 僅綁 `127.0.0.1`。

健康檢查最多 90 次、每次間隔 2 秒（HTTP 耗時另計）。只有 health 步驟失敗且已有舊 image
才會觸發現有 rollback；pull／up 失敗不一定回滾。回滾不還原 `.env` 或 DB schema，
也沒有第二次健康驗證。不能把它視為所有故障都會自動復原的保證。

## 4. nginx 與管理門禁

範本為 [`nginx/notifyline.conf.example`](nginx/notifyline.conf.example)。複製到主機後先修改
`server_name`、憑證路徑、日誌路徑與 upstream port，再執行 `nginx -t` 後 reload。
若 nginx 沒有 ModSecurity 模組，需移除相應指令；不要將範本直接當作已驗證的主機設定。

| 路徑 | 現況 |
|---|---|
| `/api/` | 轉送通知 API，`/api/v1/**` 由應用 HMAC 保護 |
| `/line/webhook` | LINE SDK 驗簽；proxy 不改 body bytes |
| `/actuator/health` 及子路徑 | 健康檢查；其他 actuator 路徑不開放 |
| `/admin` | 轉到 `/admin/` |
| `/admin/` | `X-Admin-Gate` 門禁，再由應用帳密／session 驗證 |
| `/admin/login` | 同上，另有 nginx 5 requests/min、burst 3 限速 |
| `/enroll/` | proxy 預留，但應用尚無 enrollment 頁面 |
| 其他 | 404 |

`admin-gate.map.example` 需填入獨立隨機值，存成主機上的 `/etc/nginx/conf.d/admin-gate.map`。
代理端對 `/admin*` 設定同值的 `X-Admin-Gate`，並覆寫訪客自帶 header。
此 header 只證明請求經過代理規則；如果規則對所有訪客注入，它**不是**個別使用者授權。
仍需登入；如只允許特定人，另設代理存取政策。`CF-Connecting-IP` 僅作限流 key，需可信代理
邊界才能信任；application 本身沒有後台登入 rate limit。

使用 Cloudflare 時，以符合憑證驗證的 TLS 設定連 origin；正式設定以代理平台為準。
不要為 webhook 全面關閉網站防護；排除规则僅限必要的 webhook 路徑。

## 5. 驗證

1. loopback `/actuator/health` 回 UP，再檢查對外 HTTPS 健康路徑。
2. 未簽章 webhook 應被拒，正確簽章的 `events: []` 應為 200。
3. LINE Developers Console 設定 `<服務網址>/line/webhook`、開啟 Use webhook；需重送時另啟用 Webhook redelivery。
4. 檢查 `/actuator/env`、`/actuator/configprops` 不公開。管理路徑依 gate、是否設定帳密、登入狀態回 403／404／登入頁，不是全部固定 404。
5. 用測試帳號驗證加好友、我的ID、封鎖；真實通知須使用已授權收件人。

日誌可能含 LINE ID、host 及外部錯誤；不要整包貼到公開 issue。
平台重送條件見 [LINE 官方 webhook 文件](https://developers.line.biz/en/docs/messaging-api/receiving-messages/#webhook-redelivery)。

## 6. 日常維運

優先在後台建立憑證、設定預設對象及查看通知。Admin workflow 的 `status`、`list-clients`、
`logs` 會把結果寫到 Actions summary；建立憑證動作則把明文 secret 寫 raw log。
這些都需控管存取與保留期限，見 [隱私檢查](../Docs/隱私與敏感資訊檢查.md)。

更改管理帳密後可跑 `reload-admin-login`：只改 `.env` 的兩行並重啟 app，既有 session 失效。
使用者加好友後才標記 owner；缺少 ACTIVE owner 時 `OWNER` 通知可能回 NO_RECIPIENT。

## 7. 故障排除

| 症狀 | 檢查方向 |
|---|---|
| 部署排隊 | runner online、repo access、labels 與 Docker 權限 |
| GHCR push denied | workflow permissions 與 package 權限 |
| webhook 401／403 | Channel Secret、proxy 是否改 body、是否落到錯誤的安全鏈 |
| 收事件卻回覆失敗 | Channel Token 與 LINE API 回應 |
| 所有 client 認證失敗 | 加密版本是否遺失或被覆蓋；不要直接產新 key 覆蓋 |
| 後台 404 | 帳密是否成對配置，是否重新啟動 |
| 外部 502 | app health、loopback port 與 nginx upstream |
| PostgreSQL volume 不相容 | 先備份、檢查版本與掛載路徑；18 掛 `/var/lib/postgresql`，不要用 `down -v` 當一般修復 |
