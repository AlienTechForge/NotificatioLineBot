# ADR-0004 — 權限採 scope 集合而非固定角色

**狀態**：Accepted ｜ 2026-08-18

## 背景

[初步SPEC.md](../../初步SPEC.md) §5 定義三種通知對象：`SELF`、`USER`、`ALL`，其中 `SELF` 描述為「通知自己的 LINE」。

規劃過程中釐清出更精確的需求：

> 權限需要更細分。每個 USER 要可以通知自己，同時也要有 Owner 這種層級的通知（一般 USER 不能發）。

這揭示 SPEC 原本的 `SELF` 有語意混淆——「自己」指的是**呼叫者綁定的那個人**，還是**系統主人**？兩者在單人系統中重合，在多使用者系統中完全不同。

釐清後的實際需求：

| 需求 | 說明 |
|---|---|
| 一般使用者能通知自己 | 每人有自己的金鑰，只能發給自己 |
| 服務能通知管理者 | 後端服務沒有綁定使用者，但要能回報給 owner |
| 管理者能通知任何人／全體 | 高權限，一般使用者不得擁有 |
| 進階功能需額外授權 | 例如原始 LINE message object（可帶連結，有釣魚風險） |

## 決策

**權限模型 = Client 綁定的 LINE user + 一組 scope**。

五個 scope：

| scope | 意義 |
|---|---|
| `notify:self` | 發給該 client 綁定的那一個 LINE user |
| `notify:owner` | 發給所有 `is_owner = true` 且 ACTIVE 的 user |
| `notify:user` | 發給請求中任意指定的 userIds |
| `notify:all` | 發給所有 ACTIVE user |
| `notify:raw` | 允許使用原始 LINE message object |

「Client 類型」不是資料庫欄位，而是綁定狀態與 scope 組合的**結果**：

| 類型 | 綁定 | 預設 scopes |
|---|---|---|
| USER client | 該使用者 | `notify:self` |
| SERVICE client | 無 | `notify:owner` |
| OWNER client | owner 的 user | `notify:self`, `notify:owner`, `notify:user`, `notify:all` |

同時確立一條嚴格規則：**一般 client 送 `target: USER` 一律 403，即使 userIds 只填自己。**

## 理由

### scope 相對於固定角色的優勢

- **新增權限組合不用改程式**。例如「這個服務只能發給 owner，但需要用 Flex Message」——授予 `notify:owner` + `notify:raw` 即可。若是固定角色，就得新增一個角色列舉值、改判斷邏輯、重新部署。
- **權限檢查是集合包含判斷**，不是條件分支。`principal.scopes.contains(requiredScope)` 一行，沒有 if-else 樹可以寫錯。
- **`notify:raw` 這種安全開關天然適合 scope**。它與「發給誰」正交——任何類型的 client 都可能需要或不需要它。用角色模型就得為每個角色開一個「加強版」，組合爆炸。

### 為什麼 `SELF` 解析成「綁定的人」而非「系統主人」

因為多使用者場景下這兩者不同，而**「自己」的自然語意就是呼叫者自己**。系統主人有專屬的 `OWNER` target，語意清晰不重疊。

這也讓 SERVICE client（無綁定）用 `SELF` 時能給出明確錯誤 `CLIENT_NOT_BOUND`，而不是靜默地發給某個「預設的人」。

### 為什麼 `target: USER` 填自己也要拒絕

看起來這是無害的——結果與 `SELF` 相同。但開這個後門會讓權限規則從「沒有 `notify:user` 就不准出現 userIds」變成「沒有 `notify:user` 時，userIds 只能包含自己，且要檢查陣列長度為 1，且要比對綁定的 user id」。

**權限檢查的正確性與它的分支數成反比。** 單一無條件規則在 code review 時一眼可驗；帶例外的規則需要逐案推理，而每一次推理都是出錯的機會。

要發給自己就用 `SELF`——這不是限制，只是要求用對的 API。

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **三級固定角色（OWNER / MEMBER / SERVICE）** | 實作最簡單，但每個新的權限組合都要改程式。`notify:raw` 這種正交能力無法乾淨表達 |
| **每個 client 一張目標白名單** | 更細緻（可指定「這個 client 能發給這三個人」），但多一張表、多一組檢查，而目前沒有這個場景。日後需要時可加 `notify:user` 的補充限制，不衝突 |
| **完全動態的 ACL（主體 × 動作 × 資源）** | 最大彈性，但對一個只有四種 target 的系統是嚴重過度設計 |
| **保留 SPEC 原本的 `SELF` = 系統主人** | 多使用者場景下語意不通；且會讓「使用者通知自己」無法表達 |

## 後果

**正面**

- 新增權限組合只需改資料，不改程式
- 權限檢查是單一的集合包含判斷，易於驗證
- `notify:raw` 這類安全開關能與「發給誰」正交表達
- `SELF` / `OWNER` 語意清晰不重疊

**負面**

| 後果 | 緩解 |
|---|---|
| 多一張 `client_scope` 表與 join | 資料量小，且可快取在 `ClientPrincipal` |
| scope 字串打錯不會在編譯期發現 | 用 enum 定義，DB 加 `CHECK` 約束雙保險 |
| 「這個 client 到底能做什麼」要查兩處（綁定 + scope） | Phase 2 Admin UI 提供彙整檢視；Phase 1 靠一句 SQL |
| 使用者可能覺得「填自己的 id 也被拒」不直觀 | 錯誤訊息明確指引改用 `SELF`；[05-API契約](../05-API契約.md) 有專門說明 |

**測試要求**

target × scope 的**每一格**都要有測試（[08 §2.3](../08-測試計畫.md#23-targetresolver)），包含「只有 `notify:self` 卻送 `USER` 且填自己」這個特別容易被實作成例外的案例。
