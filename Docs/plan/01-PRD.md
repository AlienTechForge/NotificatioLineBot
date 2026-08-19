# 01 — 產品需求（PRD）

← [文件索引](README.md)

## 1. 問題陳述

目前每個需要發 LINE 通知的專案都要自己接 LINE Messaging API。這造成四個具體痛點：

| 痛點 | 具體後果 |
|---|---|
| **Token 四散** | Channel Access Token 複製在 N 個專案的設定檔裡。輪替一次要改 N 個地方，漏改一個就靜默失敗。 |
| **User 名單各自維護** | 每個專案自己記 LINE User ID。使用者封鎖 Bot 後，各專案不會知道，繼續對空氣發送。 |
| **無法統一停用** | 某個服務發瘋狂洗版，要停掉它只能進去改該專案程式並重新部署。沒有「拔掉這個來源」的開關。 |
| **無紀錄可查** | 「上週三那則備份失敗通知到底有沒有送出去？」無法回答。 |

再加上一個結構性問題：**新增通知來源時要改 LINE Bot 主程式**，這讓每次擴充都變成一次發布。

## 2. 目標

建立集中式 Notification Server，達成：

- **單一端點** — 所有專案呼叫同一個 `POST /api/v1/notifications`
- **零主程式改動** — 新增通知來源只需在系統中建立一組 Client 金鑰，不動任何程式碼
- **來源可獨立停用** — 停用某組 Client 金鑰即切斷該來源，不影響其他來源
- **權限分層** — 一般使用者只能通知自己；OWNER 才能通知他人或全體
- **可追溯** — 每一次發送、每一批投遞、每一個失敗都有紀錄

## 3. 使用者與場景

### 3.1 三類使用者

| 角色 | 說明 | 持有的憑證 |
|---|---|---|
| **SERVICE**（呼叫服務） | 內部後端服務，如 Server Monitor、Backup Service | SERVICE client 金鑰，無綁定 LINE user |
| **USER**（一般 LINE 使用者） | 加入 Bot 好友的一般使用者 | 自助申請取得的 USER client 金鑰，綁定自己 |
| **OWNER**（管理者） | 系統擁有者，可能不只一人 | OWNER client 金鑰，擁有全部權限 |

### 3.2 場景

#### S1 — 服務回報給管理者

> Backup Service 凌晨三點跑完備份，要通知管理者結果。

- 呼叫方：SERVICE client
- target：`OWNER`
- 期待：所有標記為 owner 且未封鎖 Bot 的使用者都收到

**驗收條件**
- SERVICE client 用正確簽章呼叫，回 `202` 並附 `notificationId`
- 所有 `is_owner = true` 且 `status = ACTIVE` 的使用者收到訊息
- 該 SERVICE client 若嘗試送 `target: ALL`，回 `403 SCOPE_DENIED`

#### S2 — 使用者自我提醒

> 使用者小明在自己的個人腳本裡串接，跑完長時間任務時通知自己。

- 呼叫方：USER client（小明自助申請的）
- target：`SELF`
- 期待：只有小明收到

**驗收條件**
- 小明在 LINE 傳「申請金鑰」，收到一次性連結
- 開啟連結取得 clientId 與 secret，該連結再次開啟回 `410`
- 用該金鑰送 `target: SELF`，只有小明收到
- 用該金鑰送 `target: USER` 指定他人 user id，回 `403`
- 用該金鑰送 `target: USER` 指定自己的 user id，**同樣回 `403`**（不開後門，要發給自己就用 `SELF`）
- 用該金鑰送 `target: ALL`，回 `403`

#### S3 — 全體公告

> OWNER 要對所有 Bot 好友發布服務維護公告。

- 呼叫方：OWNER client
- target：`ALL`
- 期待：所有未封鎖的好友收到，並可事後查詢送達統計

**驗收條件**
- 收件人自動以 500 人為單位切批（見 [07-非同步與可靠性設計](07-非同步與可靠性設計.md)）
- API 立即回 `202`，不阻塞等待全部送完
- `GET /api/v1/notifications/{id}` 可查到批次數、成功數、失敗數
- 發送前檢查 LINE 月額度，不足時直接回 `429`，不做半套發送

#### S4 — 停用失控來源

> 某服務 bug 導致每秒發一則通知。

- 操作方：OWNER
- 期待：把該 Client 狀態改為 `DISABLED` 後，該來源後續請求一律 `401`，其他來源不受影響

**驗收條件**
- Client 停用後，該金鑰請求回 `401 AUTH_CLIENT_DISABLED`
- 其他 Client 正常運作
- 已排入佇列但尚未送出的批次，行為需明確定義（Phase 1：仍會送完；於文件中註明）

#### S5 — 使用者封鎖 Bot

> 使用者封鎖 Bot 後，不應再被列入 `ALL` 收件人。

**驗收條件**
- 收到 `UnfollowEvent` 後，該 user 的 `status` 變為 `BLOCKED`
- 該 user 綁定的 USER client 自動變為 `DISABLED`
- 後續 `ALL` 發送不含該 user
- 使用者解除封鎖再加好友，`status` 回到 `ACTIVE`

## 4. 範圍

### Phase 1 — MVP（本輪實作）

- [x] 通知 API：`POST /api/v1/notifications`、`GET /api/v1/notifications/{id}`
- [x] HMAC-SHA256 認證，含重放防護
- [x] Client 與 scope 權限模型
- [x] LINE Webhook：follow / unfollow / 文字指令
- [x] LINE User 管理與 Profile 同步
- [x] 自助申請金鑰（一次性連結）
- [x] 四種 target：SELF / OWNER / USER / ALL
- [x] 非同步分批發送、重試、冪等
- [x] 通知紀錄與查詢
- [x] Docker Compose 部署 + CI/CD

### Phase 2 — Admin Panel

- Client CRUD 與 scope 調整
- LINE User 列表、owner 標記
- 通知紀錄查詢與篩選
- 手動發送介面

技術方向見 [ADR-0005](adr/0005-Admin-UI-單一-repo-打包進-jar.md)，結構預留見 [02-架構設計](02-架構設計.md)。

### Phase 3 — 定時通知

- 以 Quartz + JDBC JobStore 實作動態排程
- 排程實體只存 target + message + cron，執行時**走與 API 完全相同的派送路徑**，不另開第二套發送邏輯

### 非目標（明確不做）

| 不做 | 原因 |
|---|---|
| Discord / Email / Slack 等其他管道 | SPEC 已列為「後續如有需求再擴充」。現在做會過早抽象。 |
| 多租戶（多個 LINE Official Account） | 單一 Bot 已滿足需求。多租戶會讓權限模型複雜度翻倍。 |
| 訊息模板管理系統 | 呼叫端自行組內容即可。真有共用需求再說。 |
| 通知已讀 / 互動回饋追蹤 | LINE 需要額外的 insight API 與 audience 設定，成本高於當前價值。 |
| Rich menu / LIFF | 與通知核心無關。 |
| 使用者自行設定「不想收哪類通知」 | Phase 1 沒有通知分類概念。要做需先引入 category，留待日後。 |

## 5. 成功指標

Phase 1 完成的判準（可客觀驗證，非感覺）：

1. 一個全新的內部服務要接入，**只需要拿到一組金鑰 + 複製一段簽章程式碼**，不需要任何人改 Notification Server 的程式
2. LINE Channel Token 只存在於 Notification Server 的 `.env`，全文檢索其他專案找不到第二份
3. 對 1200 名使用者發送 `ALL`，API 回應時間 < 500ms（因為非同步），且送達統計正確
4. 任意一次發送都能用 `notificationId` 查到完整投遞結果
5. 停用一組 Client 後，該來源立即失效且不影響其他來源

## 6. 假設與相依

| 項目 | 說明 |
|---|---|
| LINE Official Account | 已建立，且已取得 Channel Access Token 與 Channel Secret |
| 公開 HTTPS 端點 | 由使用者自行配置反向代理，**不在本專案範圍**。Server 只需正確處理 `X-Forwarded-*` |
| LINE 訊息額度 | 免費方案有月額度上限。`ALL` 發送前會檢查，但額度規劃是營運決策 |
| Server 環境 | 具備 Docker 與可供 CI 部署的 SSH 存取 |

---

**下一份** → [02-架構設計](02-架構設計.md)
