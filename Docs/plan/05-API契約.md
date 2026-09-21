# 05 — API 契約

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

← [文件索引](README.md) ｜ 前一份 [04-資料模型](04-資料模型.md)

這份文件是**給呼叫端看的**。內部設計細節在其他文件，這裡只講「你要送什麼、會拿到什麼」。

Base URL：`https://{your-host}/api/v1`

對外 API 只有三支：

| Endpoint | 章節 |
|---|---|
| `POST /api/v1/notifications` | [§2](#2-post-apiv1notifications) |
| `GET /api/v1/notifications/{id}` | [§3](#3-get-apiv1notificationsid) |
| `GET /api/v1/whoami` | [§8](#8-get-apiv1whoami) |

除此之外，`POST /line/webhook` 是給 LINE 平台呼叫的（由 LINE SDK 註冊、走 `x-line-signature` 驗簽，不是本文件的對象），管理後台自己的 `/admin/api/**` 見 [§9](#9-管理-apiadminapi)。

---

## 1. 認證

每個請求都要帶四個 header。完整規格見 [03-權限與認證設計 §2](03-權限與認證設計.md#2-hmac-認證)。

| Header | 必填 | 範例 |
|---|---|---|
| `X-Client-Id` | ✔ | `cli_a1b2c3d4e5f6g7h8i9j0` |
| `X-Timestamp` | ✔ | `1755500000` |
| `X-Nonce` | ✔ | `3f2504e0-4f89-11d3-9a0c-0305e82c3301` |
| `X-Signature` | ✔ | `k8Hf3…=` |
| `Idempotency-Key` | — | 你自己產生的字串，最長 128 字元 |
| `X-Request-Id` | — | 你的追蹤 id；不給則由 server 產生 |

簽章計算方式：

```text
canonical = METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + hex(sha256(body))
signature = base64(hmac_sha256(clientSecret, canonical))
```

- 五段以 `\n` 串接，**沒有結尾換行**。
- `METHOD` 一律大寫；`PATH` **不含 query string**（目前所有需驗簽的端點都用 body 或路徑傳參）。
- `hex(sha256(body))` 是**小寫**十六進位。GET 這類空 body 的請求要雜湊**空位元組陣列**（結果是 `e3b0c442…`），這一段不能省略。
- 時間戳與伺服器差距超過 300 秒會被拒。

關於 `X-Request-Id`：你送什麼值都會被消毒——截斷到 64 字元，並把 `[A-Za-z0-9_.:-]` 以外的字元換成 `_`。伺服器**一定**會在回應帶回同名 header，遇到問題時請附上它。

可直接複製的實作見 [§6 簽章範例](#6-簽章範例)。

---

## 2. `POST /api/v1/notifications`

送出一則通知。**非同步處理**——回應代表「已受理」，不代表「已送達」。

### 2.1 Request

```json
{
  "target": { "type": "SELF" },
  "message": {
    "title": "備份完成",
    "text": "資料庫備份耗時 42 秒，檔案大小 1.2GB"
  },
  "options": {
    "notificationDisabled": false,
    "persistPayload": true
  }
}
```

#### `target`（選填）

| `type` | 額外欄位 | 收件人 | 需要 scope |
|---|---|---|---|
| `SELF` | — | 你的金鑰綁定的那個 LINE user | `notify:self` |
| `OWNER` | — | 所有系統管理者 | `notify:owner` |
| `USER` | `userIds`（字串陣列，最多 500） | 指定的使用者 | `notify:user` |
| `ALL` | — | 所有 Bot 好友 | `notify:all` |

```json
{ "target": { "type": "USER", "userIds": ["U4af4980629...", "U0c229f96c4..."] } }
```

給了 `target` 就必須有 `type`。`userIds` 的每個元素都要符合 `^U[0-9a-f]{32}$`，格式不符是 `400 VALIDATION_ERROR`；`type: USER` 但 `userIds` 為空同樣是 `400`。

> 一般使用者金鑰只有 `notify:self`。送 `type: USER` 一律 `403`，**即使 userIds 只填自己**——要發給自己請用 `SELF`。

#### 省略 `target`：套用預設對象

`target` 可以整個不給。這時系統會改用**管理者為這組金鑰設定的預設通知對象**（在管理後台以 `PUT /admin/api/clients/{clientId}/default-target` 設定）。

```json
{ "message": { "text": "只有一行內容也能發" } }
```

- 沒有設定預設對象時省略 `target` → `400 VALIDATION_ERROR`，訊息會告訴你「請管理者設一個，或自己明確指定 target」。
- 走預設對象這條路**刻意不做 scope 檢查**：對象是管理者決定的，不是呼叫端自己挑的。所以一組只有 `notify:self` 的金鑰，仍可能因為管理者把預設對象設成 `OWNER` 而發給管理者。
- 明確給了 `target` 就一律照 scope 檢查，不會因為有預設對象而放寬。

這條路徑是給「只想 POST 一行文字、不想理解 target 模型」的呼叫端用的。

#### `message`（與 `lineMessages` 二擇一）

| 欄位 | 必填 | 限制 |
|---|---|---|
| `title` | — | ≤ 100 字元。會渲染成訊息首行 |
| `text` | ✔ | 1–5000 字元 |

`title` 有值時會渲染成訊息首行（實際送出的是 `title + "\n" + text`）。`title` + `text` 合併後仍需 ≤ 5000 字元（LINE 文字訊息上限），超過回 `400 VALIDATION_ERROR`。

> ⚠️ **純文字裡的連結也受白名單管制。** LINE 客戶端會把 `https://…` 自動變成可點連結，釣魚風險與 `lineMessages` 的 `uri` action 相同，所以簡易模式的 `text` 一樣要過 `app.allowed-uri-hosts` 檢查，不在白名單就是 `400 URI_HOST_NOT_ALLOWED`。

#### `lineMessages`（進階，需 `notify:raw` scope）

直接傳 LINE 原生 message object 陣列（最多 5 個），可用 Flex Message、Template 等。

```json
{
  "target": { "type": "OWNER" },
  "lineMessages": [
    { "type": "text", "text": "第一則" },
    { "type": "sticker", "packageId": "446", "stickerId": "1988" }
  ]
}
```

> ⚠️ **需要 `notify:raw` scope，預設不授予任何金鑰。**
>
> 原因：原生 message object 可以帶 `uri` action，等於能發出可點擊的外部連結。一組外洩的金鑰若能這樣做，就成了一個掛著官方帳號名義的釣魚訊息發送器。
>
> 即使有 `notify:raw`，訊息中所有 `uri` 的網域仍須在系統白名單（`app.allowed-uri-hosts`，環境變數 `APP_ALLOWED_URI_HOSTS`）內，否則 `400 URI_HOST_NOT_ALLOWED`。**白名單預設是空的**，代表預設不允許任何 `uri`。

#### `options`（選填）

| 欄位 | 預設 | 說明 |
|---|---|---|
| `notificationDisabled` | `false` | `true` 時使用者手機不會跳推播（訊息仍會送達） |
| `persistPayload` | `true` | `false` 時**不保存通知內容**，只留 metadata 與內容 hash |

> **關於內容保存**：預設情況下通知內容會存進資料庫供查詢與稽核。**請勿在通知內容中傳送密碼、token、身分證字號等機密資料。** 若無法避免，請設 `persistPayload: false`。
>
> ⏳ 設計上的保留期是 90 天，但**目前沒有任何排程會清理**——repository 有 `clearPayloadsBefore` / `deleteByCreatedAtBefore`，沒有任何呼叫端。實務上請當作「存進去就一直在」。

### 2.2 Response — `202 Accepted`

```json
{
  "success": true,
  "data": {
    "notificationId": "0198f3a2-7c41-7b8e-9f12-6d5a4e3c2b1a",
    "status": "QUEUED",
    "recipientCount": 137,
    "batchCount": 1
  },
  "error": null
}
```

`202` 代表**已受理並排入佇列**。實際送達結果請用 [`GET /notifications/{id}`](#3-get-apiv1notificationsid) 查詢。

回應 header 會帶 `X-Request-Id`，遇到問題時請附上它。

### 2.3 冪等

帶 `Idempotency-Key` 時：

- 相同 `(client, key)` 的第二次請求，**不會重複發送**，直接回傳第一次的結果（一樣是 `202`，`notificationId` 與第一次相同）
- 「body 相同」的判定是**比對 body 的 SHA-256**，而且算在**你實際簽章的那串原始位元組**上。同一份資料重新序列化一次（欄位順序、空白不同）就會被判成不同的 body
- 若 key 相同但 body 不同 → `409 IDEMPOTENCY_CONFLICT`。這幾乎都代表呼叫端產生 key 的邏輯有 bug，所以刻意不悄悄回傳舊結果
- key 最長 128 字元（對齊資料庫欄位寬度），超過 → `400 VALIDATION_ERROR`。空白字串視同沒帶
- key 沒有獨立的過期機制：它跟著 `notification` 這一列存在。⏳ 保留期清理尚未實作（見 §2.1 的內容保存說明），所以實務上 key 是**永久佔用**的

建議所有會自動重試的呼叫端都帶上，避免網路逾時導致重複通知。

---

## 3. `GET /api/v1/notifications/{id}`

查詢發送結果。同樣需要簽章（`PATH` 用完整路徑含 id，body 為空）。

### Response — `200 OK`

```json
{
  "success": true,
  "data": {
    "notificationId": "0198f3a2-7c41-7b8e-9f12-6d5a4e3c2b1a",
    "targetType": "ALL",
    "status": "SUCCEEDED",
    "recipientCount": 1200,
    "successCount": 1200,
    "failureCount": 0,
    "createdAt": "2026-08-18T10:15:00Z",
    "startedAt": "2026-08-18T10:15:00Z",
    "finishedAt": "2026-08-18T10:15:04Z",
    "batches": [
      {
        "batchNo": 1,
        "recipientCount": 500,
        "status": "SENT",
        "attemptCount": 1,
        "lineRequestId": "f70dd685-499a-4231-a441-35f136eff9e8",
        "errorCode": null,
        "sentAt": "2026-08-18T10:15:01Z"
      },
      {
        "batchNo": 2,
        "recipientCount": 500,
        "status": "SENT",
        "attemptCount": 2,
        "lineRequestId": "5b3c9d2e-1f47-4a8b-9c33-2e7f1a6b4d90",
        "errorCode": null,
        "sentAt": "2026-08-18T10:15:02Z"
      },
      {
        "batchNo": 3,
        "recipientCount": 200,
        "status": "SENT",
        "attemptCount": 1,
        "lineRequestId": "a1c8f0b7-6d24-4e59-8f0a-3b5c7d9e1f22",
        "errorCode": null,
        "sentAt": "2026-08-18T10:15:04Z"
      }
    ]
  },
  "error": null
}
```

### 批次欄位

| 欄位 | 說明 |
|---|---|
| `batchNo` | 批次序號，從 1 開始。每批最多 500 人（LINE multicast 上限） |
| `recipientCount` | 這一批的收件人數 |
| `status` | `PENDING`（待送或待重試）／`SENT`（LINE 已接受）／`FAILED`（終局失敗或用盡重試） |
| `attemptCount` | 已嘗試次數。> 1 代表重試過 |
| `lineRequestId` | LINE 回應的 `x-line-request-id`。與 LINE 官方客服對帳時要附這個 |
| `errorCode` | 失敗時的分類碼，不含內部細節。取值見下表 |
| `sentAt` | 送出時間；尚未送出為 `null` |

批次的 `errorCode`（**與 §4 的 API 錯誤碼是兩套不同的東西**，這些描述的是 LINE 端發生了什麼）：

| 值 | 意義 | 會重試嗎 |
|---|---|---|
| `LINE_RATE_LIMITED` | LINE 端短時間內收到太多請求 | ✔ |
| `LINE_SERVER_ERROR` | LINE 回 5xx | ✔ |
| `LINE_IO_ERROR` | 逾時、連線被拒、DNS 失敗 | ✔ |
| `LINE_UNEXPECTED_STATUS` | 沒見過的狀態碼（偏向重試） | ✔ |
| `LINE_MONTHLY_QUOTA` | LINE 官方帳號**月額度用罄** | ✘ 終局 |
| `LINE_UNAUTHORIZED` | channel token 失效或被撤銷 | ✘ 終局 |
| `LINE_BAD_REQUEST` | 訊息格式或 user id 無效 | ✘ 終局 |

> 分類刻意**偏向重試**：把可重試的判成終局會讓訊息永久遺失，把終局的判成可重試只是多打幾次註定失敗的請求。代價不對稱。

刻意**不回傳收件人清單**——那是其他使用者的 LINE User ID。

### `status` 語意

| 值 | 意義 |
|---|---|
| `QUEUED` | 已受理，尚未開始送 |
| `SENDING` | 派送中 |
| `SUCCEEDED` | 全部批次送出成功 |
| `PARTIAL` | 部分批次在用盡重試後仍失敗 |
| `FAILED` | 全部失敗，或遇到終局錯誤（例如 LINE 月額度用罄、channel token 失效） |

> **`SUCCEEDED` 的精確意義是「LINE 接受了請求」，不是「使用者看到了訊息」。**
>
> LINE 對已封鎖帳號的使用者仍會回傳成功。這是 LINE 平台的行為，任何系統都無法從發送回應判斷實際觸及。詳見 [06-LINE整合設計](06-LINE整合設計.md)。

只能查詢**你自己的金鑰**建立的通知。查別人的一律回 `404`（不是 `403`——避免洩漏該 id 是否存在）。

---

## 4. 錯誤回應

統一格式：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SCOPE_DENIED",
    "message": "This client is not permitted to send to target type ALL.",
    "requestId": "0c2f1e4a-7a3b-4c9d-8e11-5f6a7b8c9d0e"
  }
}
```

`requestId` 與回應 header `X-Request-Id` 同值。你沒帶 `X-Request-Id` 時它是伺服器產生的 UUID。

### 錯誤碼總表

| HTTP | `code` | 意義 | 你該怎麼做 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | 欄位格式、長度或數量不符 | 看 `message`，修正請求 |
| 400 | `CLIENT_NOT_BOUND` | 用了 `SELF` 但這組金鑰沒綁定使用者 | SERVICE 金鑰請改用 `OWNER` |
| 400 | `NO_RECIPIENT` | 解析後收件人為空（含套用預設對象後為空） | 確認目標使用者仍是好友 |
| 400 | `URI_HOST_NOT_ALLOWED` | 訊息內的連結網域不在白名單（`lineMessages` 與純文字都算） | 聯絡管理者加白名單 |
| 401 | `AUTH_MISSING_HEADER` | 缺必要 header | 補齊四個認證 header |
| 401 | `AUTH_TIMESTAMP_SKEW` | 時間戳與伺服器差超過 300 秒 | **校正你的系統時鐘**（回應的 `Date` header 是伺服器時間） |
| 401 | `AUTH_NONCE_REPLAY` | nonce 重複使用 | 每次請求都要產生新的 nonce |
| 401 | `AUTH_INVALID_SIGNATURE` | 簽章不符，或 client id 不存在 | 檢查 canonical string 組法與 secret |
| 401 | `AUTH_CLIENT_DISABLED` | 金鑰已被停用或撤銷 | 聯絡管理者 |
| 403 | `SCOPE_DENIED` | 這組金鑰沒有該操作的權限 | 確認 target 類型；需要更高權限請聯絡管理者 |
| 404 | `NOT_FOUND` | 查詢的通知不存在或不屬於你 | 確認 notificationId |
| 409 | `IDEMPOTENCY_CONFLICT` | 同 key 但 body 不同 | 換一個 key，或送出完全相同的 body |
| 413 | `PAYLOAD_TOO_LARGE` | request body 超過 64KB | 縮短內容 |
| 429 | `RATE_LIMITED` | 超過每分鐘請求上限 | 依 `Retry-After` header 退避後重試 |
| 429 | `CLIENT_QUOTA_EXCEEDED` | 超過每日訊息配額（以收件人數計） | 等 24 小時窗口滾過去（見 §5）；需要更高配額請聯絡管理者 |
| 429 | `LINE_MONTHLY_QUOTA_EXCEEDED` | LINE 官方帳號月額度不足 | **不要重試**，聯絡管理者 |
| 500 | `INTERNAL_ERROR` | 伺服器內部錯誤 | 附上 `requestId` 回報 |

以上 17 個就是 `ErrorCode` 的**完整清單**，沒有其他值。

> ⚠️ **`LINE_MONTHLY_QUOTA_EXCEEDED` 目前不會出現。** 這個值在 `ErrorCode` 裡有宣告，但整個 server 端**沒有任何地方產生它**——月額度耗盡時 LINE 的錯誤會走一般的派送失敗路徑，反映在 `GET /notifications/{id}` 的批次 `errorCode`，而不是這支 API 的回應。這一列保留在表中是因為錯誤碼是契約的一部分（可新增、不可改名或改語意），呼叫端仍應能處理它。管理者可用 `GET /admin/api/line-quota` 查目前月配額用量（見 §9）。

`error.message` 的幾種固定形狀（不要拿去做字串比對，只是幫你判讀）：

| 情況 | `code` | `message` |
|---|---|---|
| 欄位驗證失敗 | `VALIDATION_ERROR` | `欄位: 訊息` 以 `; ` 串接，**不回傳你送進來的值** |
| body 不是合法 JSON | `VALIDATION_ERROR` | `Request body is not valid JSON.` |
| 路徑不存在 | `NOT_FOUND` | `Resource not found.` |
| 未預期例外 | `INTERNAL_ERROR` | `An internal error occurred. Please report the requestId if this persists.` |

### 錯誤訊息的原則

錯誤 `message` **不會**包含 stack trace、SQL 片段、內部檔名，或其他使用者的 LINE User ID。需要細節時請提供 `requestId` 給管理者查詢伺服器端日誌。

### 重試建議

| 情況 | 是否重試 |
|---|---|
| `429 RATE_LIMITED` | ✔ 依 `Retry-After` 退避 |
| `500` / 連線逾時 | ✔ 指數退避，**務必帶相同的 `Idempotency-Key`** |
| `429 CLIENT_QUOTA_EXCEEDED` | ✔ 但要等很久——窗口是滾動 24 小時，短時間內重試沒有意義 |
| `409 IDEMPOTENCY_CONFLICT` | ✘ 換一把 key，或改送完全相同的 body |
| `4xx`（其他） | ✘ 請求本身有問題，重試不會變好 |

送出後失敗、且不確定伺服器有沒有收到時，**帶著同一把 `Idempotency-Key` 重送**是唯一安全的做法。沒帶 key 的重送會產生第二則通知。

> 批次層級的重試（LINE 端失敗）是伺服器自己做的，呼叫端不需要也不應該介入：重試沿用同一把 `X-Line-Retry-Key`，由 LINE 端去重。你在 `GET /notifications/{id}` 看到 `attemptCount > 1` 就是這個機制在動。

---

## 5. 速率與配額

| 限制 | 預設 | 超過時 |
|---|---|---|
| 每分鐘請求數 | 60（`APP_RATE_LIMIT_PER_MINUTE`），可對單一金鑰覆寫 | `429 RATE_LIMITED` + `Retry-After`（秒） |
| 每日訊息配額 | **未設定＝不限**，由管理者對單一金鑰指定 | `429 CLIENT_QUOTA_EXCEEDED` |

要查自己這組金鑰目前的兩個數字，用 [`GET /api/v1/whoami`](#8-get-apiv1whoami)。

**每日配額的實際行為**（三點都容易誤解）：

1. 以**實際收件人數**計，不是請求數。一次發給 200 人算 200，不是 1。用請求數計等於沒有限制——「每分鐘 60 次、每次 `target: ALL` 送 1200 人」照樣過關。
2. 窗口是**滾動的 24 小時**，不是「每天 00:00 歸零」。計算方式是「過去 24 小時內累計的收件人數」。
3. 檢查在解析出收件人清單**之後**、寫入 notification **之前**。超過就**整筆拒絕**，不做半套發送——避免「1200 人只送到 800 人」這種難以推理的狀態。錯誤訊息會帶上「已用 / 上限 / 本次需要」三個數字，你可以自己決定是要等還是要縮小批量。

> ⚠️ **速率限制是 per-instance 計數**（行程內的 token bucket）。部署多個實例時，實際上限會變成 N 倍。這是已知缺口，加開第二個實例前需改用共享儲存。

需要調整請聯絡管理者。設計理由見 [03 §4](03-權限與認證設計.md#4-呼叫端速率限制與配額缺口-g2)。

---

## 6. 簽章範例

### Bash

```bash
#!/usr/bin/env bash
set -euo pipefail

HOST="https://your-host"
CLIENT_ID="cli_xxxxxxxxxxxxxxxxxxxx"
SECRET="your-client-secret"
PATH_="/api/v1/notifications"
BODY='{"target":{"type":"SELF"},"message":{"text":"hello from bash"}}'

# uuidgen 不是每個環境都有，準備一個備援
uuid() { uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || python3 -c 'import uuid; print(uuid.uuid4())'; }

TS=$(date +%s)
NONCE=$(uuid)
# 注意 printf '%s'（不是 echo）—— echo 會多一個換行，body 就與簽章對不起來
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $NF}')
CANONICAL=$(printf 'POST\n%s\n%s\n%s\n%s' "$PATH_" "$TS" "$NONCE" "$BODY_HASH")
SIG=$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)

curl -sS -X POST "${HOST}${PATH_}" \
  -H "Content-Type: application/json" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -H "X-Timestamp: ${TS}" \
  -H "X-Nonce: ${NONCE}" \
  -H "X-Signature: ${SIG}" \
  -H "Idempotency-Key: $(uuid)" \
  --data-raw "$BODY"
```

用 `--data-raw` 而不是 `-d`：對這個 body 兩者結果相同，但 `-d` 會把**開頭是 `@`** 的值當成檔名並讀取檔案內容、順便剝掉換行。`--data-raw` 沒有這個特例，送出的位元組保證就是 `BODY_HASH` 算的那一份。

### Java

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

public final class NotifyClient {

    private static final String HOST      = "https://your-host";
    private static final String PATH      = "/api/v1/notifications";
    private static final String CLIENT_ID = System.getenv("NOTIFY_CLIENT_ID");
    private static final String SECRET    = System.getenv("NOTIFY_CLIENT_SECRET");

    public static void send(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce     = UUID.randomUUID().toString();
        String bodyHash  = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(body));

        String canonical = String.join("\n", "POST", PATH, timestamp, nonce, bodyHash);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(HOST + PATH))
                .header("Content-Type", "application/json")
                .header("X-Client-Id", CLIENT_ID)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println(response.statusCode() + " " + response.body());
    }
}
```

### Python

```python
import base64, hashlib, hmac, json, os, time, uuid
import requests

HOST = "https://your-host"
PATH = "/api/v1/notifications"
CLIENT_ID = os.environ["NOTIFY_CLIENT_ID"]
SECRET = os.environ["NOTIFY_CLIENT_SECRET"].encode()


def send(payload: dict) -> requests.Response:
    body = json.dumps(payload, separators=(",", ":")).encode()

    timestamp = str(int(time.time()))
    nonce = str(uuid.uuid4())
    body_hash = hashlib.sha256(body).hexdigest()

    canonical = "\n".join(["POST", PATH, timestamp, nonce, body_hash]).encode()
    signature = base64.b64encode(hmac.new(SECRET, canonical, hashlib.sha256).digest()).decode()

    return requests.post(
        HOST + PATH,
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Client-Id": CLIENT_ID,
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "X-Signature": signature,
            "Idempotency-Key": str(uuid.uuid4()),
        },
        timeout=10,
    )


if __name__ == "__main__":
    r = send({"target": {"type": "SELF"}, "message": {"text": "hello from python"}})
    print(r.status_code, r.text)
```

> ⚠️ **Python 範例的關鍵**：`json.dumps` 用 `separators=(",", ":")` 並且**只序列化一次**，簽章與送出用同一份 bytes。若簽章時序列化一次、送出時 requests 再序列化一次，兩者的空白處理不同就會導致 `AUTH_INVALID_SIGNATURE`。同樣的陷阱存在於所有語言——**永遠對實際送出的 bytes 簽章**。

---

## 7. 版本策略

| 變更類型 | 做法 |
|---|---|
| 新增選填欄位、新增錯誤碼 | 直接在 `v1` 加。呼叫端應忽略不認得的回應欄位 |
| 修改欄位語意、移除欄位、改變預設行為 | 開 `v2`。`v1` 保留至少 6 個月並回傳 `Deprecation` 與 `Sunset` header |
| 修 bug（行為原本就不符文件） | 直接修，並在此文件註記 |

目前只有 `v1`，也沒有任何端點會回 `Deprecation` / `Sunset` header——那是開 `v2` 時才會啟用的做法（⏳ 尚未實作）。

呼叫端應該：**忽略未知的回應欄位**、**不依賴 JSON 欄位順序**、**把未知的 `error.code` 當作該 HTTP 狀態碼的通用情況處理**。

回應信封是 `@JsonInclude(ALWAYS)`：**`null` 欄位仍然會輸出**（成功時 `"error": null`，失敗時 `"data": null`）。不要靠「欄位不存在」來判斷。

---

## 8. `GET /api/v1/whoami`

呼叫端自我診斷。回答接入時最常見的三個問題：**我的憑證有效嗎、我有哪些權限、我綁到哪個使用者**。

需要簽章（`METHOD` 為 `GET`、`PATH` 為 `/api/v1/whoami`、body 為空）。**不需要任何 scope**。

### Response — `200 OK`

```json
{
  "success": true,
  "data": {
    "clientId": "cli_a1b2c3d4e5f6g7h8i9j0",
    "boundLineUserId": "U4af4980629...",
    "scopes": ["notify:self"],
    "rateLimitPerMin": null,
    "dailyMessageQuota": null
  },
  "error": null
}
```

| 欄位 | 說明 |
|---|---|
| `clientId` | 你的 client id |
| `boundLineUserId` | 綁定的 LINE user；`null` 代表這是 SERVICE 金鑰（不能用 `SELF`） |
| `scopes` | 已排序的 scope 字串清單 |
| `rateLimitPerMin` | `null` 代表用系統預設（見 §5） |
| `dailyMessageQuota` | `null` 代表**不限** |

刻意不回傳任何其他 client 或使用者的資訊。**這支端點不會告訴你預設通知對象是什麼**——要確認省略 `target` 會送給誰，請問管理者。

接完金鑰第一件事就打這支，比看伺服器日誌快得多：

```bash
#!/usr/bin/env bash
set -euo pipefail

HOST="https://your-host"
CLIENT_ID="cli_xxxxxxxxxxxxxxxxxxxx"
SECRET="your-client-secret"
PATH_="/api/v1/whoami"

TS=$(date +%s)
NONCE=$(uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || python3 -c 'import uuid; print(uuid.uuid4())')
# 空 body：雜湊的是空位元組陣列，結果固定是 e3b0c442…
BODY_HASH=$(printf '' | openssl dgst -sha256 -hex | awk '{print $NF}')
CANONICAL=$(printf 'GET\n%s\n%s\n%s\n%s' "$PATH_" "$TS" "$NONCE" "$BODY_HASH")
SIG=$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)

curl -sS "${HOST}${PATH_}" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -H "X-Timestamp: ${TS}" \
  -H "X-Nonce: ${NONCE}" \
  -H "X-Signature: ${SIG}"
```

---

## 9. 管理 API（`/admin/api/**`）

> 這一章**不是給一般呼叫端看的**。管理 API 走完全不同的認證方式，是給管理後台前端與維運人員用的。

### 9.1 與對外 API 的差別

| | 對外 API `/api/v1/**` | 管理 API `/admin/api/**` |
|---|---|---|
| 認證 | HMAC 簽章（4 個 header） | **帳密登入 + session cookie** |
| CSRF | 停用（stateless） | **啟用**：`XSRF-TOKEN` cookie，請求回填 `X-XSRF-TOKEN` header |
| Scope | 依 `target.type` 檢查 | **完全沒有 scope 檢查**，登入即全權 |
| 速率限制 | 有 | 無 |
| 未認證的回應 | `401` + 錯誤信封 | `/admin/api/**` 回**裸的 `401`**（`HttpStatusEntryPoint`，不走統一信封）；其餘 `/admin/**` 導向登入頁 |

帳號來源是 `app.admin.username` / `app.admin.password`（環境變數 `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD`）。**`APP_ADMIN_USERNAME` 沒設定時整條 admin chain 與頁面 controller 都不註冊**，`/admin/**` 會落到全域的 `denyAll()` → `403`。

登入端點：`POST /admin/login`（form login，成功導 `/admin/`，失敗導 `/admin/login.html?error`）、`POST /admin/logout`（導 `/admin/login.html?logout` 並刪 `JSESSIONID`）。

進到 controller 之後的回應信封與錯誤碼**與對外 API 完全相同**（見 §4）——`GlobalExceptionHandler` 是全域的。`DELETE` 類端點一律回 `{"success": true, "data": null, "error": null}`。

### 9.2 端點清單（共 30 支）

| Method | Path | 成功狀態 | 用途 |
|---|---|---|---|
| GET | `/admin/api/stats` | 200 | 儀表板統計（**過去 24 小時**窗口） |
| GET | `/admin/api/line-quota` | 200 | LINE 官方帳號月配額用量 |
| GET | `/admin/api/clients` | 200 | 憑證清單 |
| POST | `/admin/api/clients` | **201** | 建立憑證，回應含**明文 secret，只此一次** |
| DELETE | `/admin/api/clients/{clientId}` | 200 | 作廢憑證（**不可回復**） |
| PUT | `/admin/api/clients/{clientId}/default-target` | 200 | 設定／清除預設通知對象（整份取代；`type: null` = 清除） |
| GET | `/admin/api/line-users` | 200 | ACTIVE LINE 使用者清單 |
| PUT | `/admin/api/line-users/{lineUserId}/owner` | 200 | 切換 owner 標記 |
| POST | `/admin/api/notifications/test` | **202** | 後台直接發送／排程一則通知 |
| GET | `/admin/api/notifications` | 200 | 近期發送紀錄（上限 100 筆） |
| GET | `/admin/api/notifications/{id}` | 200 | 單筆通知詳情（型別同 §3 的 `NotificationDetail`） |
| GET | `/admin/api/notifications/scheduled` | 200 | 未到期排程清單（上限 200 筆） |
| DELETE | `/admin/api/notifications/{id}/schedule` | 200 | 取消排程 |
| GET | `/admin/api/monitors` | 200 | 監控清單＋執行狀態摘要（**不含 header 值與 secret 值**） |
| POST | `/admin/api/monitors` | **201** | 建立監控 |
| PUT | `/admin/api/monitors/{id}` | 200 | 更新監控 |
| DELETE | `/admin/api/monitors/{id}` | 200 | 刪除監控 |
| POST | `/admin/api/monitors/{id}/enabled` | 200 | 啟用／停用 |
| POST | `/admin/api/monitors/test` | 200 | 試跑（**不存檔、不發送**） |
| GET | `/admin/api/monitors/{id}/runs` | 200 | 最近 50 筆執行紀錄 |
| GET | `/admin/api/monitors/{id}/headers` | 200 | 回傳 header **明文值**（`name -> value`） |
| DELETE | `/admin/api/monitors/{id}/secrets/{name}` | 200 | 刪除單一監控 secret（**不可回復**） |
| POST | `/admin/api/monitors/import` | 200 | 解析貼上的 cURL／`fetch(...)`／JSON，**不存檔** |
| GET | `/admin/api/sessions` | 200 | 站台登入狀態列表（**絕不回 cookie 值**） |
| DELETE | `/admin/api/sessions/{host}` | 200 | 清除該 host 的 cookie jar |
| GET | `/admin/api/logins` | 200 | 站台登入設定列表；不含密碼與 token |
| POST | `/admin/api/logins` | 201 | 建立站台登入設定 |
| PUT | `/admin/api/logins/{id}` | 200 | 更新設定；空白密碼代表沿用原值 |
| DELETE | `/admin/api/logins/{id}` | 200 | 刪除設定；引用它的監控改為無登入 |
| POST | `/admin/api/logins/{id}/test` | 200 | 立即測試登入；可能更新 token 快取或停用永久失敗的設定 |

三支帶 `Cache-Control: no-store` 的端點：`POST /admin/api/monitors/test`、`GET /admin/api/monitors/{id}/headers`、`POST /admin/api/monitors/import`。

監控相關端點的欄位語意（`compareMode`、`extractRules`、`computedFields`、模板佔位符等）見 [11-API監控輪詢設計](11-API監控輪詢設計.md)、[12-API監控易用性升級](12-API監控易用性升級.md)、[13-監控計算欄位設計](13-監控計算欄位設計.md)。

### 9.3 幾個容易踩到的點

- `POST /admin/api/clients` 回應中的 `secret` 是**明文且只出現這一次**，資料庫只存加密後的值。沒抄到就只能作廢重建。
- `PUT /admin/api/monitors/{id}` 的 `headers`：`null` 或空 = **不變更**既有 header；非空 = **整份覆寫**。沒有「明確清除全部 header」的表示法。
- `PUT /admin/api/monitors/{id}` 的 `secrets`：逐筆判斷，空白值 = 該名稱不變更。要刪除請走 `DELETE /admin/api/monitors/{id}/secrets/{name}`。
- `POST /admin/api/notifications/test` 的 `scheduledAt`：必須**至少 30 秒之後**、且**不超過 366 天**，違反回 `400 VALIDATION_ERROR`。省略 = 立即發送。
- `POST /admin/api/monitors/import` 的原始文字上限 **64 KB**（UTF-8 位元組），超過回 `413 PAYLOAD_TOO_LARGE`。
- `GET /admin/api/monitors/{id}/headers` 會回傳 header 明文值。這是後台編輯功能需要的，但也代表**任何登入者都能讀到監控用的 API token**。

---

**下一份** → [06-LINE整合設計](06-LINE整合設計.md)
