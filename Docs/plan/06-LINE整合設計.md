# 06 — LINE 整合設計

← [文件索引](README.md) ｜ 前一份 [05-API契約](05-API契約.md)

本文件是 **LINE 平台限制的權威來源**。其他文件引用此處數字，不重述。

> 平台限制查證日期：**2026-08-18**，來源為 [LINE Messaging API reference](https://developers.line.biz/en/reference/messaging-api/) 官方文件。這類數字會變動，重大改版時需重新查證。

---

## 1. LINE 平台限制（權威表）

### 1.1 發送端點

| 端點 | 收件人 | 速率限制 | 本專案是否使用 |
|---|---|---|---|
| `POST /v2/bot/message/push` | 1 人 | 2,000 req/s | ✘（多播統一走 multicast） |
| `POST /v2/bot/message/multicast` | **最多 500 個 user ID** | **200 req/s** | ✔ **主要發送路徑** |
| `POST /v2/bot/message/broadcast` | 所有好友 | **60 req/hour** | ✘ 見 [ADR-0003](adr/0003-ALL-採-multicast-而非-broadcast.md) |
| `POST /v2/bot/message/reply` | 事件來源 | 2,000 req/s | ✔（webhook 回覆用） |
| `POST /v2/bot/message/narrowcast` | 分眾 | 60 req/hour | ✘ 不在範圍 |

### 1.2 通用限制

| 項目 | 限制 |
|---|---|
| 單次請求的 message object 數 | **最多 5 個** |
| 文字訊息長度 | **5,000 字元** |
| Reply token | **只能用一次**，且需在收到 webhook 後 **1 分鐘內** 使用 |
| 冪等機制 | `X-Line-Retry-Key` header，UUID 十六進位格式，**由開發者自行產生**（LINE 不會給） |
| 速率限制演算法 | token bucket。超過回 `429 Too Many Requests` |
| 速率限制範圍 | **per-channel**，與 IP 無關；不同 channel 各自獨立計算 |
| Webhook 簽章 | `x-line-signature` = `Base64(HMAC-SHA256(channelSecret, rawBody))` |
| Webhook header 大小寫 | **不敏感**——`X-Line-Signature` 與 `x-line-signature` 都可能出現，處理時不可區分大小寫 |
| 月訊息額度 | 依方案而定。用完回 `429`「You have reached your monthly limit.」 |

### 1.3 ⚠️ 三個會導致設計錯誤的行為

這三點若不知道，會做出看起來對但實際壞掉的系統。

#### (a) 對已封鎖使用者 multicast **仍然回 200**

LINE 官方明確說明，以下情況會回 `200` 但使用者收不到：

- 已刪除 LINE 帳號的使用者
- 已封鎖本官方帳號的使用者
- 未加本官方帳號好友的使用者
- 屬於其他 provider 的 channel 的 user ID

**結論：無法從發送回應判斷實際觸及。** 名單有效性只能靠 webhook 事件與 Profile API 主動維護（見 §4）。

這也是為什麼 [05-API契約](05-API契約.md) 要明說「`SUCCEEDED` 的意思是 LINE 接受了請求，不是使用者看到了訊息」——這是平台限制，不是我們偷懶。

#### (b) Webhook 會收到**不含事件**的連線測試請求

LINE 會送出 `events` 為空陣列的 POST 來確認連線。**必須也回 200**。若處理邏輯假設一定有事件而拋例外，LINE Developers Console 的「Verify」按鈕就會失敗，且平台可能停用 webhook。

#### (c) Webhook 失敗會**重送同一事件**

回應非 200 或逾時，LINE 會重送。重送的 webhook 帶**相同的 `webhookEventId`**。

不做去重的話：`FollowEvent` 重送 → 重複發歡迎訊息；「申請金鑰」重送 → 發出兩個 token。處理方式見 §3.1。

---

## 2. Webhook 端點

### 2.1 設定

```yaml
line.bot:
  channel-token: ${LINE_CHANNEL_TOKEN}
  channel-secret: ${LINE_CHANNEL_SECRET}
  handler.path: /line/webhook
```

`line-bot-spring-boot-webmvc:10.1.0` 會自動註冊該路徑並**自行完成 `x-line-signature` 驗證**，我們不需要重寫驗簽邏輯。

### 2.2 與 HMAC 認證的關係

> ⚠️ `/line/webhook` **必須排除在 `HmacAuthFilter` 之外**。

它有自己的簽章機制（用 LINE Channel Secret），與我們對呼叫端的 HMAC（用 Client Secret）是兩套完全不同的東西。若不排除，LINE 送來的請求會因為缺 `X-Client-Id` 而被擋在 401，webhook 永遠收不到。

路徑分流設定見 [03 §6](03-權限與認證設計.md#6-securityconfig-路徑分流)。

---

## 3. 事件處理

### 3.1 冪等閘門（缺口 G1）

**所有事件處理的第一步**，在任何業務邏輯之前：

```text
handler 進入
   → 嘗試 INSERT INTO webhook_event (webhook_event_id, event_type, line_user_id)
   → 違反主鍵約束？
        是 → 已處理過，直接 return（HTTP 仍回 200）
        否 → 繼續處理
```

這比「把每個操作各自寫成冪等」更可靠：只有一個地方需要確認正確性，而不是每加一個新事件處理就要重新思考一次冪等性。表定義見 [04 §8](04-資料模型.md#8-webhook_event缺口-g1)。

### 3.2 事件對照表

| 事件 | 觸發時機 | 處理 |
|---|---|---|
| `FollowEvent` | 加好友 或 解除封鎖 | upsert `line_user`（`status = ACTIVE`、`followed_at`）→ **非同步**呼叫 Profile API 補 display name / picture → reply 歡迎訊息（含使用說明） |
| `UnfollowEvent` | 封鎖官方帳號 | `status = BLOCKED`、`unfollowed_at`；同時把該 user 綁定的 ACTIVE client 設為 `DISABLED` |
| `MessageEvent`（text） | 使用者傳文字 | 交給 `WebhookCommandRouter`（見 §3.3） |
| `MessageEvent`（其他） | 貼圖、圖片等 | 記 log，reply 一則「我看不懂，傳『說明』看可用指令」 |
| 其他所有事件 | — | 記 log 後忽略 |

**`UnfollowEvent` 為什麼要連帶停用 client**：使用者封鎖了 Bot，代表不想再收到通知。但他的 client 金鑰仍然有效，還能繼續打 API（只是訊息送不到）。停用它讓狀態一致，也避免無效的 API 呼叫消耗配額。使用者重新加好友時，`FollowEvent` 不會自動恢復 client——需重新申請金鑰。這是刻意的，因為封鎖期間金鑰可能已外流。

### 3.3 文字指令路由

| 指令 | 動作 |
|---|---|
| `申請金鑰` | 發放 enrollment token 並回覆一次性連結（見 [03 §5](03-權限與認證設計.md#5-金鑰發放自助申請與一次性連結)） |
| `重設金鑰` | 撤銷現有金鑰後發放新的一次性連結 |
| `我的ID` | 回覆自己的 LINE User ID（管理者要標記 owner 時需要） |
| `說明` / `help` | 回覆可用指令清單 |
| 其他 | 回覆「傳『說明』看可用指令」 |

指令比對前先 `trim()` 並移除全形空白。同時接受繁體中文與英文別名（`申請金鑰` / `issue`）。

### 3.4 ⚠️ Handler 的三條硬規則

違反任何一條都會造成難以察覺的錯誤。

#### 規則一：**空事件也要回 200**

`events` 為空陣列時直接回 200，不進入任何處理邏輯。這是 LINE 的連線測試。

#### 規則二：**Reply token 1 分鐘內用掉，且只能用一次**

Handler 內**不做慢動作**：

- Profile API 同步 → 丟背景執行緒
- 大量 DB 寫入 → 丟背景執行緒
- 任何可能超過數百毫秒的操作 → 丟背景執行緒

同步段只做：冪等檢查 → 最小限度的 DB 寫入 → reply。

若 reply token 已過期或已用過，LINE 回 `400 Invalid reply token`。此時**不要改用 push 補送**——那會消耗訊息額度，且使用者可能根本不在意。記 log 即可。

#### 規則三：**Handler 拋例外不可讓 HTTP 變成 5xx**

否則 LINE 會重送，而重送通常解決不了「程式有 bug」這件事，只會讓同一個錯誤重複發生。

每個 handler 方法內部全包 `try/catch`，捕捉後記錄 error log（含 `webhookEventId` 與完整 stack trace），然後正常返回。

> 這與「冪等閘門」搭配的效果：即使處理到一半失敗，`webhook_event` 已經寫入，LINE 重送也不會重跑。這是刻意的取捨——**寧可漏處理一個事件（有 log 可查、可人工補），也不要重複處理**（可能重複發訊息給使用者）。

---

## 4. LINE User 名單維護

因為 [§1.3(a)](#a-對已封鎖使用者-multicast-仍然回-200)，發送回應完全不能用來判斷名單有效性。三層維護：

| 層 | 機制 | 時效 |
|---|---|---|
| **主要** | `FollowEvent` / `UnfollowEvent` | 即時 |
| **校正** | 每日排程用 Profile API 逐一檢查 | 24 小時 |
| **補齊** | Follow 時的非同步 Profile 同步 | 秒級 |

### 每日校正排程

```text
每日 03:00
  → 取出所有 status = ACTIVE 的 user
  → 逐一呼叫 GET /v2/bot/profile/{userId}（速率限制 2,000 req/s，實務上不會撞到）
      200 → 更新 display_name / picture_url / profile_synced_at
      404 → 標記 status = BLOCKED（該使用者已封鎖或刪除帳號）
      429 → 退避後重試
      其他 → 記 log，不改變狀態（避免因暫時性錯誤誤刪名單）
```

**為什麼需要這一層**：`UnfollowEvent` 有可能因為我們這邊當機而漏收。沒有校正機制的話，名單會慢慢累積無效使用者，每次 `ALL` 發送都在浪費額度與時間。

> Profile API 的取得條件：使用者已加好友，或未加好友但曾傳訊息給官方帳號（且未封鎖）。所以 404 是可靠的「這人已經不在了」訊號。

多實例注意事項見 [缺口 G12](02-架構設計.md#g12--多實例的排程重複執行--加第二個實例前必須解決)。

---

## 5. 發送策略

### 5.1 為什麼全部走 multicast

| 選項 | 為什麼不選 |
|---|---|
| `broadcast` | 60 req/hour 硬限制；無法排除特定人；**無法得知任何個別結果**；發給「所有好友」而非我們資料庫的 ACTIVE 名單，兩者可能不一致 |
| `push` 逐人發 | 1200 人 = 1200 次呼叫。雖然速率上限高，但延遲與失敗處理複雜度都遠高於 3 次 multicast |

`multicast` 在批次大小（500）、速率（200 req/s）、可控性（我們決定名單）三方面都是最佳解。單一收件人時 LINE 建議用 `push`（延遲較低），但為了讓派送路徑只有一條、行為一致好推理，本專案**統一用 multicast**——單人也是一批。詳見 [ADR-0003](adr/0003-ALL-採-multicast-而非-broadcast.md)。

### 5.2 分批

- 常數集中定義在 `LineLimits.MULTICAST_MAX_RECIPIENTS = 500`，**不散落在程式各處的字面值**
- 1200 人 → 3 批（500 / 500 / 200）
- 每批一個固定的 `retry_key`（UUID），**重試時沿用同一把**

### 5.3 `X-Line-Retry-Key`

LINE 提供的冪等機制。用途：網路逾時時我們不知道請求到底送出去沒有，重試若不帶 retry key，使用者可能收到兩則相同訊息。

- 每批在**建立 delivery 記錄時**就產生並存入 `retry_key` 欄位
- 所有重試都用同一把
- 不同批用不同把（它們是不同的訊息）

### 5.4 錯誤分類

派送時必須區分三類錯誤，處理方式完全不同：

| 類別 | HTTP / 訊息 | 處理 | 計入斷路器 |
|---|---|---|---|
| **終局失敗** | `429` + "You have reached your monthly limit."<br>`400` 訊息格式錯誤 | 不重試，標 `FAILED` | ✘ |
| **憑證問題** | `401` / `403` | 不重試，標 `FAILED`，**發告警**（token 可能失效或被撤銷） | ✘ |
| **暫時性** | `5xx`、連線逾時、`429` 速率限制 | 指數退避重試 | ✔ |

**把終局失敗計入斷路器是錯的**——額度耗盡時斷路器會開路，但等待再久也不會恢復（要等到下個月）。反而會讓真正的暫時性故障判斷失準。

### 5.5 月額度守門

`target: ALL` 發送前先檢查：

```text
GET /v2/bot/message/quota              → 本月可用上限
GET /v2/bot/message/quota/consumption  → 本月已用量
剩餘 = 上限 - 已用

若 剩餘 < 本次收件人數
   → 429 LINE_MONTHLY_QUOTA_EXCEEDED，整筆拒絕
```

**整筆拒絕，不做半套發送。** 「1200 人只送到 800 人，剩下的永遠不會送」比「完全沒送，你知道要處理」更糟。

實作要點：

- 額度查詢結果**快取 5 分鐘**——每次發送都查會浪費 API 呼叫，且額度不會在幾分鐘內劇烈變化
- 只對 `ALL` 做這個檢查。`SELF`（1 人）、`OWNER`（少數人）不值得為此多一次 API 呼叫
- 額度查詢本身失敗時**放行**並記 warn——不能因為輔助性檢查失敗就擋掉主要功能

> 註：LINE 官方 FAQ 指出，有時仍有可用額度卻收到 monthly limit 錯誤。所以守門是「盡力而為」，實際發送時仍要處理 `429` 終局失敗。

---

## 6. 訊息組裝

### 6.1 簡易模式

```json
{ "title": "備份完成", "text": "耗時 42 秒" }
```

渲染為單一 `TextMessage`：

```text
備份完成
耗時 42 秒
```

`title` 存在時作為首行，與 `text` 以換行分隔。**不使用任何 Markdown 或 HTML**——LINE 文字訊息不支援格式化，加了只會顯示成字面符號。

合併後長度必須 ≤ 5000 字元，在 DTO 驗證層擋掉。

### 6.2 原始模式（`notify:raw`）

直接傳遞呼叫端提供的 message object 陣列。驗證：

1. 陣列長度 ≤ 5
2. 每個物件有合法的 `type`
3. **遞迴掃描所有 `uri` 欄位**，網域必須在 `app.line.allowed-uri-hosts` 白名單內

第 3 點是安全關鍵，理由見 [缺口 G5](02-架構設計.md#g5--linemessages-逃生門的濫用風險--phase-1)。掃描必須遞迴——Flex Message 的 action 可以巢狀在很深的結構裡。

---

## 7. 測試環境隔離（缺口 G14）

> ⚠️ **dev 環境必須用另一個 LINE Official Account。**

用同一個帳號測試，測試訊息會直接發到真實使用者的手機上。

| 環境 | LINE Channel | 說明 |
|---|---|---|
| `test`（自動化測試） | 無 | 一律用 okhttp `MockWebServer` 打樁，**絕不碰真 LINE** |
| `dev`（本機／測試機） | 專用的測試 Channel | 只有開發者自己是好友 |
| `prod` | 正式 Channel | — |

Phase 2 的 Admin LINE Login 也需要各環境獨立的 Login channel。

---

## 8. SDK 使用要點

`line-bot-sdk-java` **10.1.0**。API 與 6.x 有大幅差異，網路上的舊教學多半不適用。

```java
// 事件處理
@LineMessageHandler
public class LineWebhookHandler {

    private final MessagingApiClient client;

    @EventMapping
    public void handleFollow(FollowEvent event) { … }

    @EventMapping
    public void handleText(MessageEvent event) {
        if (event.message() instanceof TextMessageContent text) { … }
    }

    @EventMapping
    public void handleDefault(Event event) { /* 記 log */ }
}

// 回覆
client.replyMessage(new ReplyMessageRequest(
        event.replyToken(), List.of(new TextMessage("hi")), false));

// 取得 x-line-request-id（對帳用，務必記錄）
Result<Object> result = client.multicast(retryKey, request).get();
String lineRequestId = result.requestId();

// 錯誤處理
try {
    client.multicast(retryKey, request).get();
} catch (ExecutionException e) {
    if (e.getCause() instanceof MessagingApiClientException ex) {
        int    code    = ex.getCode();
        String details = ex.getDetails();
        String acceptedId = ex.getHeader("x-line-accepted-request-id");
    }
}
```

**注意事項**

- 套件路徑是 `com.linecorp.bot.spring.boot.handler.annotation.*`（10.x），不是 6.x 的 `com.linecorp.bot.spring.boot.annotation.*`
- API 方法是 record 風格的 `event.message()`、`event.replyToken()`，不是 `getMessage()`
- 回傳 `CompletableFuture`，錯誤包在 `ExecutionException` 裡，要 unwrap `getCause()`
- **`x-line-request-id` 一律記錄並存進 `notification_delivery`**——這是跟 LINE 客服對帳的唯一憑據
- SDK 內部用 Jackson 2，與 Spring Boot 4.1 的 Jackson 3 共存，版本需覆寫。見 [02 §5.1](02-架構設計.md#51-jackson-版本衝突必須處理)

---

**下一份** → [07-非同步與可靠性設計](07-非同步與可靠性設計.md)
