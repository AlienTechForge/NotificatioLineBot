# NotificatioLineBot 規劃文件集

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

> 統一 LINE Notification Service — 讓所有內部服務共用同一套 LINE 通知管道。

## 這是什麼

多個內部服務（Server Monitor、Website、Backup Service…）都想發 LINE 通知。如果每個專案各自接 LINE SDK，會出現四個問題：

1. LINE Channel Token 散落在各專案，輪替一次要改 N 個地方
2. LINE User 名單各自維護，誰封鎖了 Bot 沒人知道
3. 想停掉某個來源的通知，只能進去改那個專案的程式
4. 沒有集中的通知紀錄可查，出事無法追溯

本專案建立一個集中式 Notification Server，對呼叫端只暴露三支 HMAC 端點，核心是：

```http
POST /api/v1/notifications
```

另有 `GET /api/v1/notifications/{id}`（查單筆狀態）與 `GET /api/v1/whoami`（查自己的 scope 與綁定，不回傳預設對象）。

呼叫端只負責三件事：**組通知內容 → 用自己的 secret 簽章 → 打 API**。

其餘全部由 Server 收斂：LINE Token、LINE SDK、User 名單、發送 routing、權限控管、通知紀錄。

需求源頭見 [初步SPEC.md](../初步SPEC.md)（已補上目前實作對照，本文件集提供細節）。

---

## 決策摘要

| 項目 | 決定 | 理由 |
|---|---|---|
| 實作範圍 | 對外 API、LINE 派送、管理後台、API 監控子系統均已落地；金鑰自助發放與稽核紀錄**尚未實作** | 見下方「目前實作狀態」 |
| 後端 | Java 21 + Spring Boot 4.1.0 + Maven | 本機工具鏈現況；LINE Bot SDK 10.1.0 即建構於 SB 4.1 |
| 資料庫 | PostgreSQL 18（`postgres:18-alpine`），Flyway `V1`~`V7`、共 15 張應用資料表 | [ADR-0001](adr/0001-採用-PostgreSQL-而非-SQLite.md) |
| 對外認證 | HMAC-SHA256，Client ID / Secret | 依 SPEC；secret 加密儲存見 [ADR-0002](adr/0002-client-secret-加密儲存而非雜湊.md) |
| 後台認證 | 帳密 form login + session + CSRF | [ADR-0011](adr/0011-管理介面採帳密登入.md)，取代 [ADR-0009](adr/0009-Admin-認證採-LINE-Login.md) 的 LINE Login |
| 權限 | Client 綁 LINE user + scope 集合 | [ADR-0004](adr/0004-權限採-scope-集合而非固定角色.md) |
| 金鑰發放 | ⏳ 規劃為 LINE 對話自助申請 → 一次性連結，**尚未實作** | 設計見 [03-權限與認證設計](03-權限與認證設計.md)；`enrollment/` 套件目前為空 |
| ALL 發送 | multicast 分批 500 人 + 非同步佇列（**沒有 push、沒有 GROUP/ROOM**） | [ADR-0003](adr/0003-ALL-採-multicast-而非-broadcast.md) |
| 非同步 | DB outbox + `@Async`；claim 用 `FOR UPDATE SKIP LOCKED`，預設每 10 秒補償取件 | [ADR-0007](adr/0007-非同步採-DB-outbox-而非訊息中介.md) |
| Admin UI | 實際為**手寫原生 HTML/JS/CSS**，直接放在 `server/src/main/resources/static/admin/`，無框架、無 build step | [ADR-0012](adr/0012-Admin-UI-採原生靜態資源.md) 取代 ADR-0005 的框架方案；`admin-ui/` 目前只有 `.gitkeep` |
| CI/CD | 公開 repo 停用 Actions；私人 Operations repo 測試、建私人 GHCR image，**self-hosted runner 部署** | [ADR-0006](adr/0006-CICD-採-self-hosted-runner.md) + [ADR-0010](adr/0010-CICD-改採-GHCR-加-SSH-部署.md) + [ADR-0013](adr/0013-公開原始碼與私人維運分離.md) |
| 對外 Endpoint | 使用者自行配置反向代理與 HTTPS | 不在本專案範圍 |

---

## 文件職責

| 文件 | 負責回答 |
|---|---|
| [01-PRD](01-PRD.md) | 要解決誰的什麼問題？做到什麼程度算完成？什麼**不做**？ |
| [02-架構設計](02-架構設計.md) | 系統長什麼樣？有哪些模組？用什麼版本的什麼東西？ |
| [03-權限與認證設計](03-權限與認證設計.md) | 誰能發給誰？怎麼證明你是你？金鑰怎麼發、怎麼存？ |
| [04-資料模型](04-資料模型.md) | 資料表長什麼樣？索引與約束為何這樣設？ |
| [05-API契約](05-API契約.md) | 呼叫端要送什麼、會拿到什麼？錯誤怎麼判讀？ |
| [06-LINE整合設計](06-LINE整合設計.md) | Webhook 收什麼、怎麼處理？LINE 平台有哪些硬限制？ |
| [07-非同步與可靠性設計](07-非同步與可靠性設計.md) | 大量發送怎麼不塞爆、不掉單、不重複？ |
| [08-測試計畫](08-測試計畫.md) | 測什麼、怎麼測、測到什麼程度？ |
| [09-CICD與維運](09-CICD與維運.md) | 怎麼部署？出事怎麼查？金鑰外洩怎麼辦？ |
| [10-任務拆解](10-任務拆解.md) | 實作照什麼順序做？每步做完怎麼驗？ |
| [11-API監控輪詢設計](11-API監控輪詢設計.md) | 監控項目怎麼定時抓、怎麼比對？三種比對模式差在哪？SSRF 怎麼擋？ |
| [12-API監控易用性升級](12-API監控易用性升級.md) | 怎麼貼一段 DevTools 複製的 cURL/fetch 就建好監控？站台登入狀態怎麼保存？ |
| [13-監控計算欄位設計](13-監控計算欄位設計.md) | 目標站要簽章怎麼辦？`{{computed.*}}` 怎麼定義、怎麼求值、密鑰存哪？ |
| [14-模板輸入輔助](14-模板輸入輔助.md) | 使用者怎麼知道有哪些變數可用？`{{` 自動完成與預設模板長什麼樣？ |
| [15-監控站台登入設計](15-監控站台登入設計.md) | Cognito SRP、token 快取、header 注入與登入失敗如何處理？ |
| [隱私與敏感資訊檢查](../隱私與敏感資訊檢查.md) | main 的部署資訊、機密與歷史殘留有哪些風險？ |
| [adr/](adr/README.md) | 當初為什麼這樣選？考慮過哪些替代方案？ |

01~10 描述核心通知服務；11~15 是後續追加的 API 監控子系統，逐份往前延伸，
彼此是「後者只增加東西，不放寬前者的安全約束」的關係。

---

## 建議閱讀順序

**第一次接觸專案** — 01 → 02 → 10，約 20 分鐘可以開始動手。

**要動特定領域** — 從 02 找到對應模組，再讀該模組的專屬文件。

**要理解某個決策** — 直接看 [adr/](adr/README.md)。

**要接手實作** — 從 [10-任務拆解](10-任務拆解.md) 開始，每項任務都連結到需要的設計文件。

**要動 API 監控** — 11 → 12 → 13 → 14 → 15；12 的 cookie jar 與 15 的 Cognito 登入是不同功能。

---

## 目前實作狀態

一般文件以程式碼現況為準；歷史決策保留原背景但標示取代關係。以下項目仍未完成。

**已落地** — 對外 3 支 HMAC API、LINE webhook、管理後台（手寫前端 + 30 支 `/admin/api/**`）、
API 監控子系統（`server/src/main/java/com/jason/notifyline/monitor/`）、排程發送（`notification.scheduled_at`）。

**尚未實作 / 尚未接線**（文件裡讀到這些時請當成規劃）：

- 金鑰自助發放：`GET /enroll/{token}` 不存在，`enrollment/` 套件為空
- 稽核紀錄：`audit_log` 表建了但全 codebase 零讀寫，`audit/` 套件為空
- redriver：`NotificationRepository.findByStatusAndStartedAtBefore` 無呼叫端，卡在 SENDING 的通知不會被救回
- 資料保留期清理：清理方法存在但沒有任何排程呼叫
- 可觀測性：`/actuator/prometheus` 被 `SecurityConfig` 擋在外部，且無自訂 metric、無告警
- 多實例：排程類元件尚無分散式鎖，目前是單實例假設（`DeliveryStore.claim` 的
  `FOR UPDATE SKIP LOCKED` 是例外，本身多實例安全）

---

## 文件維護規則

- **同一事實只在一處定義**。例如「multicast 上限 500 人」的權威來源是 [06-LINE整合設計](06-LINE整合設計.md)，其他文件引用它而不重述數字。
- **決策變更要更新 ADR**。不要直接改設計文件而讓 ADR 停留在舊決策；新決策開新 ADR 並把舊的標為 `Superseded`。
- **版本號與外部平台限制標註查證日期**。這類事實會過期。
