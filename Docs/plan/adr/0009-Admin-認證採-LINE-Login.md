# ADR-0009 — Admin 認證採 LINE Login

**狀態**：Accepted ｜ 2026-08-18（Phase 2 實作）

## 背景

架構審查時發現一個缺口（[02 缺口 G11](../02-架構設計.md#g11--admin-ui-的認證方式--phase-2但現在要定)）：**初版設計完全沒有規劃 Admin Panel 的認證方式。**

`/api/v1/**` 用 HMAC 簽章，那是機器對機器的方案——人不可能在瀏覽器裡手算 canonical string 與 HMAC。Admin UI 需要一套完全不同的認證。

這個決策雖然屬於 Phase 2，但**必須現在做**：`SecurityConfig` 的 filter chain 分界要在 Phase 1 就預留好，否則 Phase 2 要重構整個安全設定。

系統已有的條件：

- 每個管理者**已經是 LINE 使用者**（`line_user` 表中 `is_owner = true`）
- 已有 LINE Developers 帳號與 Provider
- 使用者身分與 LINE User ID 天然對應

## 決策

**用 LINE Login (OAuth 2.0) 做 Admin 認證。**

```text
管理者點「使用 LINE 登入」
   → 導向 LINE Login 授權頁
   → 使用者授權
   → callback 帶 authorization code
   → 換取 access token 與 ID token
   → 從 ID token 取出 LINE User ID
   → 查 line_user.is_owner
        true  → 建立 session
        false → 403
```

配套：

| 項目 | 做法 |
|---|---|
| Filter chain | `/admin/**` 走**獨立的第二條** `SecurityFilterChain`（session-based），與 `/api/v1/**`（HMAC、stateless）用 `securityMatcher` 分流 |
| Session | 伺服器端 session，`HttpOnly` + `Secure` + `SameSite=Lax` cookie |
| CSRF | `/admin/**` **必須啟用**（有 cookie 就有 CSRF 風險）；`/api/v1/**` 維持 disabled |
| 授權判定 | 每次請求檢查 session 中的 user 仍是 `is_owner = true`（**不只在登入時檢查一次**） |
| Channel | LINE Developers 額外建一個 **LINE Login channel**（與 Messaging API channel 分開），各環境獨立 |

## 理由

### 不用自建帳密系統，省掉一整套要維護的東西

自建帳號密碼意味著要處理：密碼雜湊（Argon2/bcrypt 選型與參數）、密碼強度規則、忘記密碼流程（需要 email 服務）、登入失敗鎖定、session 固定攻擊防護、以及未來大概會被要求的 2FA。

**這些每一項都是可能寫錯的安全關鍵**，而且與本專案的核心價值（統一通知）完全無關。

### 身分模型天然對齊

管理者本來就是 LINE 使用者，`line_user.is_owner` 已經是既有的權限來源。用 LINE Login 之後：

- 不需要「Admin 帳號」與「LINE User」之間的對應表
- 要加一個管理者：把 `is_owner` 設 true，**沒有第二步**
- 要撤銷：設 false，立即生效（因為每次請求都檢查）

自建帳密會產生第二套身分，然後就要處理「這個 admin 帳號對應哪個 LINE user」以及兩邊不同步的問題。

### 使用者體驗

管理者手機上已經登入 LINE。點登入 → 授權 → 進入，沒有密碼要記。

### 為什麼每次請求都要重查 `is_owner`

若只在登入時檢查，撤銷某人的 owner 權限後，他的既有 session 仍然有效直到過期。對一個能停用所有 client、發送全體通知的介面，這個延遲不可接受。

每次請求查一次 DB 的成本很低（有索引，且可短期快取）。

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **自建帳號密碼** | 要維護密碼雜湊、重設流程、鎖定策略、2FA；產生第二套身分需要對應與同步；每一項都是安全風險面 |
| **GitHub / Google OAuth** | 同樣避開密碼管理，但**身分與 `line_user` 不對齊**，需要額外的對應表與綁定流程。LINE Login 沒有這個問題 |
| **HTTP Basic Auth + 反向代理** | 最簡單，但共用憑證無法區分是誰操作（稽核失效）、無法個別撤銷、密碼難輪替 |
| **只在反向代理層做 IP 白名單** | 不是認證，只是網路限制。無法回答「誰做了這件事」 |
| **mTLS 客戶端憑證** | 安全性高，但憑證發放與瀏覽器安裝對使用者太不友善 |
| **沿用 HMAC** | 人無法在瀏覽器手算簽章。要做就得在前端存 secret，那反而是更大的風險 |

## 後果

**正面**

- 不需要維護任何密碼相關機制
- 身分與既有的 `line_user` / `is_owner` 完全對齊
- 加減管理者只需改一個 boolean
- 撤銷即時生效
- 稽核可以記錄到具體是哪個 LINE User 做的操作

**負面**

| 後果 | 緩解 / 說明 |
|---|---|
| **相依 LINE Login 服務可用性** | LINE 掛掉時管理者無法登入。但此時通知功能本來也不能用，影響一致。緊急情況仍可用 bootstrap CLI 直接操作 |
| 需額外建立並維護一個 LINE Login channel | 一次性設定；各環境（dev/prod）需各自一個 |
| OAuth callback URL 需正確設定，且**必須是 https** | 已由 `APP_PUBLIC_BASE_URL` 的 fail-fast 檢查涵蓋 |
| **Session 引入 CSRF 風險** | `/admin/**` 的 chain 必須啟用 CSRF 保護。這條與 `/api/v1/**` 的設定不同，容易被誤設成一致 |
| 兩條 filter chain 增加設定複雜度 | Phase 1 就把 `securityMatcher` 分界畫好，Phase 2 只是填入第二條 |
| 使用者需授權應用取得基本 profile | 只要 `profile` 與 `openid` scope，不取額外資料 |

**Phase 1 必須預留的事**

1. `SecurityConfig` 以 `securityMatcher` 分流，即使 Phase 1 只有一條 chain
2. `/admin/**` 路徑在 Phase 1 一律 deny（不是 permitAll）
3. `line_user.is_owner` 欄位已存在（[04 §2](../04-資料模型.md#2-line_user)）
4. 稽核表的 `actor_type` 已包含 `LINE_USER`（[04 §9](../04-資料模型.md#9-audit_log缺口-g8)）

這四項在 Phase 1 都是零成本的，但事後補要動到安全設定的核心。
