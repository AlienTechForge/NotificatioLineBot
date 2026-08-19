# ADR-0003 — 全體發送採 multicast 而非 broadcast

**狀態**：Accepted ｜ 2026-08-18

## 背景

[初步SPEC.md](../../初步SPEC.md) §5 要求 `ALL` 目標：「通知所有加入 Bot 且目前有效的使用者」。

LINE Messaging API 提供三種可能的做法（限制查證日期 2026-08-18，來源 [LINE 官方文件](https://developers.line.biz/en/reference/messaging-api/)）：

| 端點 | 收件人 | 速率限制 |
|---|---|---|
| `push` | 1 人 | 2,000 req/s |
| `multicast` | 最多 **500** 個 user ID | **200 req/s** |
| `broadcast` | 所有好友 | **60 req/hour** |

`broadcast` 看起來最直接——一次呼叫解決，不用自己管名單、不用分批。

## 決策

**全部發送統一走 `multicast`**，包含單一收件人的情況。`ALL` 以 500 人為單位分批。

`broadcast` 不使用。

## 理由

### 1. `broadcast` 的 60 req/hour 是硬限制

平均每分鐘一次。系統若有多個來源同時發全體公告，很容易撞牆，而撞牆後除了等別無他法。`multicast` 的 200 req/s 相差四個數量級。

### 2. `broadcast` 無法排除特定使用者

它發給「所有好友」，範圍由 LINE 決定，我們無法介入。而本系統維護自己的 `line_user` 表並有 `status` 欄位——`broadcast` 會繞過這個名單。兩邊不一致時，我們的紀錄會與實際發送對不上。

### 3. `broadcast` 拿不到任何個別結果

只回一個整體的成功/失敗。無法知道送給了幾個人、哪一批出問題。這讓 [05-API契約](../05-API契約.md#3-get-apiv1notificationsid) 的送達統計查詢功能無法實現。

`multicast` 至少能得知**每一批**的結果與 `x-line-request-id`（對帳憑據）。

### 4. 統一路徑讓系統只有一種行為要推理

單一收件人時 LINE 建議用 `push`（延遲較低）。但為了少一條分支，本專案**單人也走 multicast**——就是一批一個人。

好處：分批、重試、`X-Line-Retry-Key`、錯誤分類、狀態回寫全部只有一套邏輯。`SELF` 與 `ALL` 在派送層完全同構，只差收件人數量。

代價是單人發送的延遲略高於 `push`。以通知類應用的容忍度而言，這個差異不重要。

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **`broadcast` 給 ALL，`multicast` 給其他** | 兩套路徑、兩種錯誤處理、兩種狀態語意。而 `broadcast` 的三個缺點（速率、無法排除、無個別結果）沒有一個能被繞過 |
| **`push` 逐人發送** | 1200 人 = 1200 次呼叫，vs 3 次。延遲、失敗處理複雜度、重試狀態管理都明顯較差 |
| **依收件人數自動選擇端點** | 1 人用 `push`、多人用 `multicast`、全體用 `broadcast`。「聰明」但代價是三條路徑都要測試與維護，且 `broadcast` 的語意差異會滲透到上層 |
| **兩者都支援，由請求參數決定** | API 多一個 `deliveryMode` 欄位。實作與測試面積翻倍，換來的彈性目前沒有使用場景 |

## 後果

**正面**

- 不受 60 req/hour 限制
- 只發給**我們確認為 ACTIVE** 的使用者，紀錄與實際一致
- 每批都有 `x-line-request-id`，可與 LINE 對帳
- 派送層只有一套邏輯

**負面**

| 後果 | 緩解 |
|---|---|
| 必須自己維護 user 名單的正確性 | webhook 事件 + 每日 Profile API 校正（[06 §4](../06-LINE整合設計.md#4-line-user-名單維護)） |
| 必須自己實作分批 | 常數集中於 `LineLimits.MULTICAST_MAX_RECIPIENTS`；500/501 邊界有專門測試 |
| 單人發送延遲略高於 `push` | 通知類應用可接受 |
| 名單漏更新時會對無效使用者發送（浪費額度） | 每日校正 + 額度守門 |

**未解決的限制**

`multicast` 對已封鎖使用者**仍然回 200**（LINE 平台行為，見 [06 §1.3(a)](../06-LINE整合設計.md#a-對已封鎖使用者-multicast-仍然回-200)）。換成 `broadcast` 也一樣，這不是本決策造成的，但要在 API 文件中誠實說明「`SUCCEEDED` 代表 LINE 接受了請求，不代表使用者看到了訊息」。
