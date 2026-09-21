# LINE 通知機器人 — SPEC 與實作對照

> 校訂日期：2026-09-21；依當日 main 程式碼核對。
> 本文件保留最初需求的用途，以下內容已更新為目前實作。歷史取捨見 [ADR](plan/adr/README.md)。

## 1. 專案目標

集中 LINE Channel Token、好友名單、發送權限與通知紀錄。新增通知來源只需建立 client，
呼叫端自行組內容、以 client secret 簽章，再呼叫通知 API。

## 2. 現有架構

Java 21 + Spring Boot 4.1.0 提供 HTTP API、靜態管理後台與背景工作；PostgreSQL 18
保存通知及投遞 outbox。Flyway V1～V7 建立 15 張應用表，另有 Flyway 自己的歷史表。
LINE Bot SDK 10.1.0 負責 webhook 驗簽、回覆、Profile 與 multicast。

管理後台直接位於 `server/src/main/resources/static/admin/`，使用原生 HTML／JS／CSS。
`admin-ui/` 只是未使用的框架前端預留目錄，沒有可執行的 npm 專案。

## 3. 呼叫端介面

| 方法與路徑 | 行為 |
|---|---|
| `POST /api/v1/notifications` | 建立通知；202 表示已受理並持久化 |
| `GET /api/v1/notifications/{id}` | 查詢自己建立的通知與批次狀態 |
| `GET /api/v1/whoami` | 確認憑證、綁定與 scopes，不回傳預設收件人 |

三者均使用 HMAC-SHA256、timestamp 與 nonce；不得把 secret 直接當 Bearer token。
完整可接入契約見 [AI 接入指南](AI-接入指南.md)。

## 4. 通知對象與權限

| Target | 收件人 | 明確指定時所需 scope |
|---|---|---|
| `SELF` | client 綁定且仍有效的使用者 | `notify:self` |
| `OWNER` | ACTIVE 且 `is_owner=true` 的使用者 | `notify:owner` |
| `USER` | 指定名單中仍 ACTIVE 的使用者 | `notify:user` |
| `ALL` | 全部 ACTIVE 好友 | `notify:all` |

管理員可替 client 設定預設目標，呼叫端省略 `target` 時使用該授權名單。
`lineMessages` 另需 `notify:raw`；建立 OWNER client 並不自動授予 raw。

## 5. LINE 使用者與憑證

follow／unfollow 事件維護好友狀態；Profile 背景同步補齊名稱、頭像等資料。
封鎖會停用綁定的 ACTIVE client，重新加好友不自動恢復憑證。

憑證可由管理後台或 Bootstrap CLI 建立；Actions 刻意不提供憑證管理。自助申請／重設的一次性連結
仍未實作，`enrollment_token` 目前只有資料表；不要把「申請金鑰」指令當成已可用流程。

## 6. 資料庫與機密

一開始即使用 PostgreSQL，沒有 SQLite 相容模式。Client secret、監控 headers、
cookie jar、監控密鑰及 Cognito 密碼／token 使用 AES-256-GCM 加密。
通知 payload、收件人 ID、監控 URL／body／抽取結果並非全部加密，詳見
[資料模型](plan/04-資料模型.md)與[隱私檢查](隱私與敏感資訊檢查.md)。

## 7. 管理後台

`/admin/` 使用環境提供的帳密、session 與 CSRF。功能包括 client 建立／撤銷／預設目標、
好友與 owner 管理、手動及指定時間發送、通知內容與投遞紀錄、監控、cookie jar、站台登入。
目前沒有完整 client 編輯、任意 scope 編輯、多管理者帳號或稽核介面。

## 8. 指定時間通知與 API 監控

通知可用 `scheduledAt` 設定一次性的未來時間；沒有 cron 週期通知或 Quartz JobStore。
監控則按間隔抓取 HTTPS API，使用 JsonPointer 與三種比對模式，必要時透過同一通知管線發送。
支援 cURL／fetch／JSON 匯入、動態請求模板、計算欄位與 Cognito SRP 自動登入。
詳見 [11](plan/11-API監控輪詢設計.md)～[15](plan/15-監控站台登入設計.md)。

## 9. 部署與尚未完成項目

公開 repo 不含且停用 GitHub Actions。私人 Operations repo 每 10 分鐘偵測公開 `main`，
有新 SHA 時由 GitHub-hosted runner 測試、建置並推送私人 GHCR，再由 self-hosted runner 在部署主機執行 Compose。
不使用 SSH deploy。正式網址由部署者配置，文件一律以 `notify.example.com` 示範。

尚未完成：enrollment、audit_log 寫入、一般通知歷史的保留期排程、自訂指標與告警、
卡住的通知彙總狀態修復、多實例協調。不要把規劃當成現有可靠性保證。
