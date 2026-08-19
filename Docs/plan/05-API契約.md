# 05 — API 契約

← [文件索引](README.md) ｜ 前一份 [04-資料模型](04-資料模型.md)

這份文件是**給呼叫端看的**。內部設計細節在其他文件，這裡只講「你要送什麼、會拿到什麼」。

Base URL：`https://{your-host}/api/v1`

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
| `X-Request-Id` | — | 你的追蹤 id；不給則由 server 產生並回傳 |

簽章計算方式：

```text
canonical = METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + hex(sha256(body))
signature = base64(hmac_sha256(clientSecret, canonical))
```

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

#### `target`（必填）

| `type` | 額外欄位 | 收件人 | 需要 scope |
|---|---|---|---|
| `SELF` | — | 你的金鑰綁定的那個 LINE user | `notify:self` |
| `OWNER` | — | 所有系統管理者 | `notify:owner` |
| `USER` | `userIds`（字串陣列，1–500） | 指定的使用者 | `notify:user` |
| `ALL` | — | 所有 Bot 好友 | `notify:all` |

```json
{ "target": { "type": "USER", "userIds": ["U4af4980629...", "U0c229f96c4..."] } }
```

> 一般使用者金鑰只有 `notify:self`。送 `type: USER` 一律 `403`，**即使 userIds 只填自己**——要發給自己請用 `SELF`。

#### `message`（與 `lineMessages` 二擇一）

| 欄位 | 必填 | 限制 |
|---|---|---|
| `title` | — | ≤ 100 字元。會渲染成訊息首行 |
| `text` | ✔ | 1–5000 字元 |

`title` + `text` 合併後仍需 ≤ 5000 字元（LINE 文字訊息上限）。

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
> 即使有 `notify:raw`，訊息中所有 `uri` 的網域仍須在系統白名單內，否則 `400 URI_HOST_NOT_ALLOWED`。

#### `options`（選填）

| 欄位 | 預設 | 說明 |
|---|---|---|
| `notificationDisabled` | `false` | `true` 時使用者手機不會跳推播（訊息仍會送達） |
| `persistPayload` | `true` | `false` 時**不保存通知內容**，只留 metadata 與內容 hash |

> **關於內容保存**：預設情況下通知內容會保存 **90 天**供查詢與稽核。**請勿在通知內容中傳送密碼、token、身分證字號等機密資料。** 若無法避免，請設 `persistPayload: false`。

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

- 相同 `(client, key)` 的第二次請求，**不會重複發送**，直接回傳第一次的結果（一樣是 `202`）
- 若 key 相同但 request body 不同 → `409 IDEMPOTENCY_CONFLICT`
- key 的有效期同 `notification` 的保留期

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
      { "batchNo": 1, "recipientCount": 500, "status": "SENT", "sentAt": "2026-08-18T10:15:01Z" },
      { "batchNo": 2, "recipientCount": 500, "status": "SENT", "sentAt": "2026-08-18T10:15:02Z" },
      { "batchNo": 3, "recipientCount": 200, "status": "SENT", "sentAt": "2026-08-18T10:15:04Z" }
    ]
  },
  "error": null
}
```

### `status` 語意

| 值 | 意義 |
|---|---|
| `QUEUED` | 已受理，尚未開始送 |
| `SENDING` | 派送中 |
| `SUCCEEDED` | 全部批次送出成功 |
| `PARTIAL` | 部分批次在用盡重試後仍失敗 |
| `FAILED` | 全部失敗，或遇到終局錯誤（例如額度耗盡） |

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
    "requestId": "req_01H8X…"
  }
}
```

### 錯誤碼總表

| HTTP | `code` | 意義 | 你該怎麼做 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | 欄位格式、長度或數量不符 | 看 `message`，修正請求 |
| 400 | `CLIENT_NOT_BOUND` | 用了 `SELF` 但這組金鑰沒綁定使用者 | SERVICE 金鑰請改用 `OWNER` |
| 400 | `NO_RECIPIENT` | 解析後收件人為空 | 確認目標使用者仍是好友 |
| 400 | `URI_HOST_NOT_ALLOWED` | `lineMessages` 內的連結網域不在白名單 | 聯絡管理者加白名單 |
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
| 429 | `CLIENT_QUOTA_EXCEEDED` | 超過每日訊息配額 | 明日重置；需要更高配額請聯絡管理者 |
| 429 | `LINE_MONTHLY_QUOTA_EXCEEDED` | LINE 官方帳號月額度不足 | **不要重試**，聯絡管理者 |
| 500 | `INTERNAL_ERROR` | 伺服器內部錯誤 | 附上 `requestId` 回報 |

### 錯誤訊息的原則

錯誤 `message` **不會**包含 stack trace、SQL 片段、內部檔名，或其他使用者的 LINE User ID。需要細節時請提供 `requestId` 給管理者查詢伺服器端日誌。

### 重試建議

| 情況 | 是否重試 |
|---|---|
| `429 RATE_LIMITED` | ✔ 依 `Retry-After` 退避 |
| `500` / 連線逾時 | ✔ 指數退避，**務必帶相同的 `Idempotency-Key`** |
| `429 LINE_MONTHLY_QUOTA_EXCEEDED` | ✘ 重試無用 |
| `4xx`（其他） | ✘ 請求本身有問題，重試不會變好 |

---

## 5. 速率與配額

| 限制 | 預設 | 超過時 |
|---|---|---|
| 每分鐘請求數 | 60 | `429 RATE_LIMITED` + `Retry-After` |
| 每日訊息則數 | USER 200 ／ SERVICE 500 ／ OWNER 不限 | `429 CLIENT_QUOTA_EXCEEDED` |

每日配額以**實際收件人數**計，不是請求數。一次發給 200 人算 200 則。

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

TS=$(date +%s)
NONCE=$(uuidgen | tr 'A-Z' 'a-z')
BODY_HASH=$(printf '%s' "$BODY" | openssl dgst -sha256 -hex | awk '{print $NF}')
CANONICAL=$(printf 'POST\n%s\n%s\n%s\n%s' "$PATH_" "$TS" "$NONCE" "$BODY_HASH")
SIG=$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$SECRET" -binary | base64)

curl -sS -X POST "${HOST}${PATH_}" \
  -H "Content-Type: application/json" \
  -H "X-Client-Id: ${CLIENT_ID}" \
  -H "X-Timestamp: ${TS}" \
  -H "X-Nonce: ${NONCE}" \
  -H "X-Signature: ${SIG}" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "$BODY"
```

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

呼叫端應該：**忽略未知的回應欄位**、**不依賴 JSON 欄位順序**、**把未知的 `error.code` 當作該 HTTP 狀態碼的通用情況處理**。

---

**下一份** → [06-LINE整合設計](06-LINE整合設計.md)
