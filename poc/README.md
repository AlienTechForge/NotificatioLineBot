# POC 與本機驗證

> 校訂日期：2026-09-21；依 `poc.sh`、`notify.sh`、`notify.ps1` 與 Compose 核對。

## 1. 範圍

`poc/poc.sh` 是 T1～T5 的歷史端到端腳本：檢查 Docker／DB、Bootstrap、HMAC、速率限制
及合成 webhook。**不涵蓋**已實作的通知派送、指定時間通知、管理後台或 API 監控。

腳本仍有 V1 時代的固定斷言：migration 數預期 1、public 表數預期 10；
目前是 7 個 migration、15 張應用表加 Flyway 歷史表共 16 張，所以這兩项會失敗。
不要把該腳本全綠當成本版驗收條件，完整自動化驗證用 Maven verify。

## 2. 優先執行自動化測試

```bash
mvn -B -ntp -f server/pom.xml test
mvn -B -ntp -f server/pom.xml verify
```

verify 需可用 Docker，Testcontainers 啟動隔離 PostgreSQL，外部服務使用 stub／fake。
不需要正式 LINE、Cognito 或 client 憑證，也不應載入正式 `.env`。

## 3. 如需執行歷史 POC

先閱讀 `poc/poc.sh`：它會建／撤銷測試 client、寫 LINE 使用者及 webhook 資料，
並可能取消或移除腳本建立的資料。僅限可丟棄的獨立開發資料庫。

```bash
cp .env.example .env
# 填入隔離開發設定，勿覆蓋既有正式 .env
bash poc/poc.sh
```

腳本需要 Bash、Docker Compose、openssl、curl、一般 Unix 工具及可用資料庫設定；UUID fallback 需要 Python。
合成 webhook 使用本機 Channel Secret 簽章；假 LINE token 可測 handler，實際 reply／Profile
不會成功。真實測試需專用 Official Account，避免向正式好友發送。

## 4. 發送通知腳本

`poc/notify.sh` 与 `poc/notify.ps1` 已可呼叫通知 API；其參數與環境變數依腳本說明使用。
Base URL 必須由 `NOTIFY_BASE` 或腳本參數提供；`https://notify.example.com` 只是範例，腳本沒有正式服務預設值。
憑證從環境變數或秘密管理器注入，不要寫回腳本。

呼叫前依 [AI 接入指南](../Docs/AI-接入指南.md) §3.4 先驗離線簽章向量，
再 GET whoami。通知取得 202 後用 notificationId 查狀態；SUCCEEDED 只表示 LINE 接受請求。
若要重試 POST，沿用同一個 Idempotency-Key 與相同 body bytes，每次重新產生 nonce／timestamp／signature。

## 5. 本機服務與 port

```bash
docker compose -f docker/docker-compose.yml --env-file .env up -d --build
curl -fsS http://127.0.0.1:19080/actuator/health
```

19080 是預設主機 port，8080 是容器內 port；若改 APP_PORT，檢查 URL 也要改。
PostgreSQL 沒有公開主機 port。測試與正式容器／volume 不可混用。

## 6. 疑難排解

| 症狀 | 處理 |
|---|---|
| POC schema 數量失敗 | 見 §1 的過時斷言；以 Flyway 與 SchemaIT 為準 |
| Maven PKIX 錯誤 | Windows 可依本機憑證政策使用 WINDOWS-ROOT trust store；不要把 Windows 設定提交給 Linux CI |
| PostgreSQL 掛載錯誤 | 檢查版本與 `/var/lib/postgresql` 掛載，先備份；不要直接刪 volume |
| 401 簽章錯誤 | 確認 LF、UTF-8、路徑與同一份 body bytes，使用 `--data-binary @檔案` |
| 401 時鐘偏移 | 校時並核對 X-Timestamp 是秒 |
| 400 NO_RECIPIENT | 確认目標對象 ACTIVE 與 client 預設目標 |

尚未完成的是 enrollment、稽核及部分保留／復原功能；通知 API、派送及 CI/CD 均已存在。

### 通知示例的限制

既有腳本每次執行產生新的 Idempotency-Key，重跑整支腳本不等於安全重試。
Bash 範例手工拼 JSON，不支援引號、反斜線與控制字元；一般內容請先以 JSON serializer 固定 bytes。
兩支腳本都沒有組裝 USER 所需的 userIds，不能只將 Target 設成 USER 就送給特定人。
