# NotificatioLineBot 規劃文件集

> 統一 LINE Notification Service — 讓所有內部服務共用同一套 LINE 通知管道。

## 這是什麼

多個內部服務（Server Monitor、Website、Backup Service…）都想發 LINE 通知。如果每個專案各自接 LINE SDK，會出現四個問題：

1. LINE Channel Token 散落在各專案，輪替一次要改 N 個地方
2. LINE User 名單各自維護，誰封鎖了 Bot 沒人知道
3. 想停掉某個來源的通知，只能進去改那個專案的程式
4. 沒有集中的通知紀錄可查，出事無法追溯

本專案建立一個集中式 Notification Server，對外只暴露一個端點：

```http
POST /api/v1/notifications
```

呼叫端只負責三件事：**組通知內容 → 用自己的 secret 簽章 → 打 API**。

其餘全部由 Server 收斂：LINE Token、LINE SDK、User 名單、發送 routing、權限控管、通知紀錄。

需求源頭見 [初步SPEC.md](../初步SPEC.md)（保持原樣，本文件集是它的細化）。

---

## 決策摘要

| 項目 | 決定 | 理由 |
|---|---|---|
| 實作範圍 | Phase 1 MVP | 先讓端到端跑通，Admin 與排程留後續 |
| 後端 | Java 21 + Spring Boot 4.1.0 + Maven | 本機工具鏈現況；LINE SDK 10.1.0 即建構於 SB 4.1 |
| 資料庫 | PostgreSQL from day 1 | [ADR-0001](adr/0001-採用-PostgreSQL-而非-SQLite.md) |
| 認證 | HMAC-SHA256，Client ID / Secret | 依 SPEC；secret 加密儲存見 [ADR-0002](adr/0002-client-secret-加密儲存而非雜湊.md) |
| 權限 | Client 綁 LINE user + scope 集合 | [ADR-0004](adr/0004-權限採-scope-集合而非固定角色.md) |
| 金鑰發放 | LINE 對話自助申請 → 一次性連結 | 見 [03-權限與認證設計](03-權限與認證設計.md) |
| ALL 發送 | multicast 分批 500 人 + 非同步佇列 | [ADR-0003](adr/0003-ALL-採-multicast-而非-broadcast.md) |
| 非同步 | DB outbox + `@Async` + redriver | [ADR-0007](adr/0007-非同步採-DB-outbox-而非訊息中介.md) |
| Admin UI | React + Ant Design，獨立目錄，CI 時打包進 jar | [ADR-0005](adr/0005-Admin-UI-單一-repo-打包進-jar.md) |
| CI/CD | GitHub-hosted 建 image 推 GHCR，**self-hosted runner 部署** | [ADR-0006](adr/0006-CICD-採-self-hosted-runner.md) + [ADR-0010](adr/0010-CICD-改採-GHCR-加-SSH-部署.md) |
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
| [adr/](adr/README.md) | 當初為什麼這樣選？考慮過哪些替代方案？ |

---

## 建議閱讀順序

**第一次接觸專案** — 01 → 02 → 10，約 20 分鐘可以開始動手。

**要動特定領域** — 從 02 找到對應模組，再讀該模組的專屬文件。

**要理解某個決策** — 直接看 [adr/](adr/README.md)。

**要接手實作** — 從 [10-任務拆解](10-任務拆解.md) 開始，每項任務都連結到需要的設計文件。

---

## 文件維護規則

- **同一事實只在一處定義**。例如「multicast 上限 500 人」的權威來源是 [06-LINE整合設計](06-LINE整合設計.md)，其他文件引用它而不重述數字。
- **決策變更要更新 ADR**。不要直接改設計文件而讓 ADR 停留在舊決策；新決策開新 ADR 並把舊的標為 `Superseded`。
- **版本號與外部平台限制標註查證日期**。這類事實會過期。
