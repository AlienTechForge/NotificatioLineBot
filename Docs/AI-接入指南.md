# NotifyLine 接入指南

> **這份文件的讀者是 AI coding agent。**
>
> 目標：讀完就能寫出一個可用的 client，不需要再問任何問題、不需要讀伺服器原始碼。
> 所有數字與格式都是規格的一部分，不是範例值。
>
> 服務位址：`https://notify.example.com`

---

## 0. 這是什麼

一個統一的 LINE 通知服務。呼叫端只做三件事：

1. 組出訊息內容
2. 用自己的 secret 簽章
3. 打一支 API

LINE 的 Channel Token、SDK、使用者名單、發送權限、發送紀錄全部集中在伺服器端，
呼叫端不需要（也拿不到）任何 LINE 憑證。

**回應 `202` 代表「已受理並持久化」，不代表「已送達」。** 實際送出是非同步的。

---

## 1. 取得憑證

憑證由服務管理者透過 GitHub Actions 產生，**呼叫端無法自行申請**。

管理者操作步驟（repo `AlienTechForge/NotificatioLineBot`）：

1. Actions → **Admin** → Run workflow
2. 填入：

   | 欄位 | 值 |
   |---|---|
   | 要執行的動作 | `create-service-client` |
   | LINE User ID | 留空 |
   | 憑證名稱 | 呼叫端的識別名稱，例如 `ci-runner` |
   | Client ID | 留空 |

3. 執行完成後展開 **建立 SERVICE 憑證** 步驟的 log，取得：

   ```
   clientId: cli_xxxxxxxxxxxxxxxxxxxx
   secret:   xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```

> **secret 只在建立當下出現這一次。** 資料庫裡是加密儲存的，設計上不提供讀回。
> 弄丟只能作廢重建（Admin → `revoke-client` → `create-service-client`）。

其他可用的管理動作：

| 動作 | 用途 |
|---|---|
| `list-clients` | 列出所有憑證與其 scope、狀態（不含 secret） |
| `revoke-client` | 作廢一組憑證（不可回復）。需填 Client ID |
| `create-owner-client` | 建立擁有完整發送權限的憑證。需填 LINE User ID |
| `status` | 服務狀態與資料統計 |

### 憑證的保管

- 存進環境變數或 secret manager，**不要進版控**
- secret 是 HMAC 金鑰，外洩等同於「任何人都能用你的名義發通知」
- 懷疑外洩時立刻請管理者 `revoke-client`

---

## 2. 權限模型（scope）

`create-service-client` 建立的憑證**只有 `notify:owner`**。
也就是說它只能發給服務的管理者，不能發給其他 LINE 使用者。

| scope | 允許的 `target.type` | 誰有 |
|---|---|---|
| `notify:owner` | `OWNER` | SERVICE 憑證預設 |
| `notify:self` | `SELF` | 綁定使用者的憑證 |
| `notify:user` | `USER` | 僅 OWNER 憑證 |
| `notify:all` | `ALL` | 僅 OWNER 憑證 |
| `notify:raw` | 使用 `lineMessages` 欄位 | 需另外明確授予 |

**如果你不確定自己有什麼權限，先打 `GET /api/v1/whoami`**（見 §6）。

scope 不足一律回 `403 SCOPE_DENIED`，不會退化成「送給比較少人」。

---

## 3. 認證：HMAC-SHA256

每個 `/api/v1/**` 的請求都要帶四個 header。

| Header | 內容 |
|---|---|
| `X-Client-Id` | `cli_` 開頭的 client id |
| `X-Timestamp` | 目前時間，Unix epoch **秒**，十進位字串 |
| `X-Nonce` | 每個請求都不同的隨機字串，建議 UUID v4 |
| `X-Signature` | 見下方 |

### 3.1 canonical string

把五個欄位以 **LF（`\n`，0x0A）** 串接，**結尾不加換行**：

```
{HTTP METHOD，大寫}
{請求路徑，不含 query string}
{X-Timestamp}
{X-Nonce}
{hex_lowercase(sha256(raw request body bytes))}
```

具體例子（`→` 代表該處是一個 LF 字元）：

```
POST→
/api/v1/notifications→
1700000000→
abc-123→
314701043308c543409c76c660248a051f2ef2a5b7b84d1efbbff8b19e712e14
```

規則細節：

- **method 一律大寫**（`POST`、`GET`）
- **path 不含 query string、不含網域**。`/api/v1/notifications`，不是
  `https://notify.example.com/api/v1/notifications`
- **body 為空時**（例如 GET），雜湊的是「空位元組陣列」，也就是
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。
  不是空字串的字面值，也不是省略這一段
- body 雜湊是**小寫**十六進位

### 3.2 簽章

```
X-Signature = base64_standard( HMAC_SHA256( key = secret_utf8, message = canonical_utf8 ) )
```

- 標準 Base64（含 `+` `/` `=`），**不是** URL-safe 變體
- secret 與 canonical string 都以 **UTF-8** 編碼

### 3.3 ⚠️ 最重要的一條規則

> **雜湊必須算在「實際送出的那串位元組」上。**

不要把物件序列化一次拿去算雜湊、再序列化一次拿去送。兩次序列化只要有任何差異
（欄位順序、空白、Unicode 逸出、小數格式），簽章就會失敗，而伺服器只會回一句
「簽章不符」，完全無法從錯誤訊息看出原因。

**正確做法**：先產生 `bytes`，對它算雜湊，然後把**同一份 bytes** 當作 request body 送出。

這是接入端最常見的錯誤，沒有之一。

### 3.4 測試向量

實作完先用這組驗證，**對不上就不要往下做**。
§8 的 Python / Node.js / Bash / PowerShell 實作都已對這組向量逐位元組驗證過。

| 項目 | 值 |
|---|---|
| secret | `test-secret-key` |
| method | `POST` |
| path | `/api/v1/notifications` |
| X-Timestamp | `1700000000` |
| X-Nonce | `abc-123` |
| body | `{"target":{"type":"OWNER"},"message":{"text":"測試"}}` |
| body 位元組長度 | `55`（UTF-8，中文各 3 bytes） |
| body sha256 hex | `314701043308c543409c76c660248a051f2ef2a5b7b84d1efbbff8b19e712e14` |
| **期望的 X-Signature** | `A8InT6lAaimJ+IRPaMuBVJxCgNY+V3Gckgw0px/P9MA=` |

body 長度是 55 而不是 49，就是在確認你的字串是以 UTF-8 而非其他編碼取位元組的。

### 3.5 伺服器端的驗證順序

依序檢查，任何一項失敗就回 401：

1. 四個 header 都存在 → 否則 `AUTH_MISSING_HEADER`
2. `X-Timestamp` 與伺服器時間差 **≤ 300 秒** → 否則 `AUTH_TIMESTAMP_SKEW`
3. client 存在且狀態為 ACTIVE → 否則 `AUTH_INVALID_SIGNATURE` / `AUTH_CLIENT_DISABLED`
4. 簽章比對（常數時間）→ 否則 `AUTH_INVALID_SIGNATURE`
5. nonce 未使用過 → 否則 `AUTH_NONCE_REPLAY`

補充：

- **時鐘同步很重要。** 偏差超過 300 秒就一律失敗。容器裡跑的服務尤其要注意
- **nonce 在 600 秒內不可重複。** 每個請求產生一個新的 UUID 即可
- **`client id 不存在` 與 `簽章錯誤` 回同一個錯誤碼**，這是刻意的，避免被列舉出哪些
  client id 存在。所以 `AUTH_INVALID_SIGNATURE` 有兩種可能，兩個都要檢查

---

## 4. 發送通知

```
POST https://notify.example.com/api/v1/notifications
Content-Type: application/json
```

### 4.1 可選 header

| Header | 說明 |
|---|---|
| `Idempotency-Key` | 最長 128 字元。見 §4.5 |
| `X-Request-Id` | 你自己的追蹤碼，會出現在錯誤回應與伺服器日誌中 |

### 4.2 Request body

```jsonc
{
  "target": {
    "type": "OWNER",          // 必填：SELF | OWNER | USER | ALL
    "userIds": null           // 只有 type=USER 需要，最多 500 個
  },
  "message": {                // 簡易模式。與 lineMessages 二擇一
    "title": null,            // 選填，最多 100 字，會渲染成訊息首行
    "text": "備份完成"         // 必填，1..5000 字
  },
  "lineMessages": null,       // 進階模式，需要 notify:raw scope。見 §4.4
  "options": {
    "notificationDisabled": false,  // true = 訊息會送達但手機不跳推播
    "persistPayload": true          // false = 送完立刻清除內容，只留 metadata 與 hash
  }
}
```

限制（違反回 `400 VALIDATION_ERROR`）：

| 項目 | 上限 |
|---|---|
| `message.text` | 5000 字元 |
| `message.title` | 100 字元 |
| `title` + `text` 合計 | 5000 字元 |
| `target.userIds` | 500 個 |
| `lineMessages` | 5 個 message object |
| 整個 request body | 65536 bytes（64 KiB） |

`userIds` 的每個元素必須符合 `^U[0-9a-f]{32}$`。

**`message` 與 `lineMessages` 必須恰好給一個。** 兩個都給或都不給都回 400 ——
不會替你猜哪個優先。

### 4.3 Response（202 Accepted）

```json
{
  "success": true,
  "data": {
    "notificationId": "c9f00a2c-eb87-4650-a3be-563c101ae1ff",
    "status": "QUEUED",
    "recipientCount": 1,
    "batchCount": 1
  },
  "error": null
}
```

拿到 202 就代表工作已寫入資料庫，即使伺服器下一秒被 kill 也不會遺失。

### 4.4 進階模式：lineMessages

直接傳 LINE 原生的 message object 陣列（Flex Message、貼圖、圖片等）。

```json
{
  "target": { "type": "OWNER" },
  "lineMessages": [
    { "type": "sticker", "packageId": "446", "stickerId": "1988" }
  ]
}
```

- 需要 `notify:raw` scope，否則 `403`
- 每個物件必須有非空的 `type` 字串
- 格式本身由 LINE 驗證，本服務只做最低限度檢查

### 4.5 連結白名單 ⚠️

**訊息中所有 `http(s)` 連結的網域必須在伺服器的白名單內，否則整個請求回
`400 URI_HOST_NOT_ALLOWED`。**

適用範圍是**所有通知**，不只 `lineMessages`：

- LINE 客戶端會自動把純文字裡的 `https://...` 變成可點連結
- 所以純文字訊息裡的連結一樣要過白名單
- 掃描是遞迴的，Flex Message 深層巢狀裡的連結也會被檢查

其他規則：

- 子網域自動涵蓋：白名單有 `example.com` 時，`docs.example.com` 通過
- `evil-example.com`、`example.com.attacker.net` **不會**通過
- `tel:`、`mailto:`、`line://` 放行（LINE 原生 action）
- `javascript:`、`data:`、`file:` 等一律拒絕
- 一般含冒號的文字（`Warning: disk full`、`ratio 16:9`）不受影響

被擋時錯誤訊息會指出是哪個連結。要新增網域請找服務管理者。

### 4.6 冪等

帶 `Idempotency-Key` header：

| 情況 | 結果 |
|---|---|
| 同一把 key，**body 位元組完全相同** | 回傳原本那筆的結果，不會重複發送 |
| 同一把 key，body 不同 | `409 IDEMPOTENCY_CONFLICT` |
| 不同 client 用同一把 key | 互不影響（範圍是 client + key） |
| 沒帶 key | 每次都是新的一筆 |

**逾時或連線中斷後的重試一定要帶同一把 key**，否則使用者會收到兩則相同通知。

比對依據是 request body 的原始位元組雜湊，所以重試時要送出**完全相同的 bytes**。

---

## 5. 查詢結果

```
GET https://notify.example.com/api/v1/notifications/{notificationId}
```

同樣要簽章（method 用 `GET`，body 為空，path 含 id）。

```json
{
  "success": true,
  "data": {
    "notificationId": "c9f00a2c-eb87-4650-a3be-563c101ae1ff",
    "targetType": "OWNER",
    "status": "SUCCEEDED",
    "recipientCount": 1,
    "successCount": 1,
    "failureCount": 0,
    "createdAt": "2026-08-19T08:49:47.172934Z",
    "startedAt": "2026-08-19T08:49:47.212502Z",
    "finishedAt": "2026-08-19T08:49:47.578651Z",
    "batches": [
      {
        "batchNo": 0,
        "recipientCount": 1,
        "status": "SENT",
        "attemptCount": 1,
        "lineRequestId": "2784968e-7a5a-429d-966d-9464c8605f91",
        "errorCode": null,
        "sentAt": "2026-08-19T08:49:47.578651Z"
      }
    ]
  },
  "error": null
}
```

### status 的意義

| status | 意義 | 是否終局 |
|---|---|---|
| `QUEUED` | 已受理，尚未開始送 | 否 |
| `SENDING` | 派送中 | 否 |
| `SUCCEEDED` | LINE 已接受全部批次 | 是 |
| `PARTIAL` | 部分批次用盡重試仍失敗 | 是 |
| `FAILED` | 全部失敗 | 是 |

> **`SUCCEEDED` 的意思是「LINE 接受了請求」，不是「使用者看到了訊息」。**
> LINE 對已封鎖官方帳號的使用者仍會回 200，任何系統都無法從發送回應判斷實際觸及。

失敗時看 `batches[].errorCode`：

| errorCode | 意義 |
|---|---|
| `LINE_MONTHLY_QUOTA` | LINE 月訊息額度用罄，不會重試 |
| `LINE_RATE_LIMITED` | 短時間送太多，已排入重試 |
| `LINE_UNAUTHORIZED` | 伺服器的 LINE 憑證失效，需管理者處理 |
| `LINE_BAD_REQUEST` | 訊息內容被 LINE 拒絕，不會重試 |
| `LINE_SERVER_ERROR` | LINE 端故障，已排入重試 |
| `LINE_IO_ERROR` | 網路層問題，已排入重試 |
| `PAYLOAD_GONE` | 內容已被保留期清理，無法重送 |

**只能查自己送出的通知。** 查別人的與查不存在的都回 `404`（刻意相同，避免試探）。

### 輪詢建議

發送通常在 1 秒內完成。若要確認結果：

1. 收到 202 後等 2–3 秒再查第一次
2. 未到終局狀態則以指數退避重試，上限約 60 秒
3. 重試最多 5 次，每次退避 1/2/4/8/16 秒，所以最壞情況約 31 秒後才會是終局

**不要用緊迫的迴圈輪詢** —— 查詢也計入速率限制。

---

## 6. 自我診斷

```
GET https://notify.example.com/api/v1/whoami
```

```json
{
  "success": true,
  "data": {
    "clientId": "cli_xxx",
    "boundLineUserId": null,
    "scopes": ["notify:owner"],
    "rateLimitPerMin": null,
    "dailyMessageQuota": null
  },
  "error": null
}
```

**接入時第一支要打的就是這個。** 它同時回答三個問題：憑證有沒有效、簽章寫對了沒、
我有哪些權限。`null` 代表使用系統預設值。

---

## 7. 錯誤處理

所有錯誤都是同一個信封：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SCOPE_DENIED",
    "message": "This client is not permitted to send to target type ALL.",
    "requestId": "..."
  }
}
```

**分支處理請依 `error.code`，不要依 `message`。** message 是給人看的，會改。

| HTTP | code | 意義 | 該怎麼辦 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | 請求格式或欄位不合法 | 修正請求。**不要重試** |
| 400 | `CLIENT_NOT_BOUND` | 憑證沒綁定 LINE 使用者卻送 `SELF` | 改用 `target.type = OWNER` |
| 400 | `NO_RECIPIENT` | 解析後沒有有效收件人 | 檢查目標使用者是否還是好友 |
| 400 | `URI_HOST_NOT_ALLOWED` | 連結網域不在白名單 | 移除連結，或請管理者加白名單 |
| 401 | `AUTH_MISSING_HEADER` | 少了必要 header | 補齊四個 header |
| 401 | `AUTH_TIMESTAMP_SKEW` | 時鐘偏差超過 300 秒 | 同步系統時鐘 |
| 401 | `AUTH_NONCE_REPLAY` | nonce 重複使用 | 每個請求產生新的 nonce |
| 401 | `AUTH_INVALID_SIGNATURE` | 簽章不符**或** client id 不存在 | 先用 §3.4 測試向量自我檢查 |
| 401 | `AUTH_CLIENT_DISABLED` | 憑證已停用或作廢 | 找管理者重發 |
| 403 | `SCOPE_DENIED` | 權限不足 | 打 `whoami` 看實際有哪些 scope |
| 404 | `NOT_FOUND` | 查不到，或不是你的 | — |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一把 key 送了不同內容 | 換一把 key |
| 413 | `PAYLOAD_TOO_LARGE` | body 超過 64 KiB | 縮短內容 |
| 429 | `RATE_LIMITED` | 超過每分鐘請求數 | 退避後重試 |
| 429 | `CLIENT_QUOTA_EXCEEDED` | 超過每日收件人數配額 | 等待或請管理者調高 |
| 500 | `INTERNAL_ERROR` | 伺服器問題 | 退避重試，附上 `requestId` 回報 |

### 該重試與不該重試

| 一律不重試 | 退避後可重試 |
|---|---|
| `VALIDATION_ERROR` | `RATE_LIMITED` |
| `SCOPE_DENIED` | `INTERNAL_ERROR` |
| `URI_HOST_NOT_ALLOWED` | 連線失敗 / 逾時 |
| `IDEMPOTENCY_CONFLICT` | |
| `AUTH_*`（先修好設定） | |

**逾時特別注意**：請求可能其實已經被受理，只是回應沒回來。重試時務必沿用**同一把
`Idempotency-Key`**，否則會重複發送。

### 速率限制

- 預設每個 client **每分鐘 60 個請求**（可個別調整，查 `whoami` 的 `rateLimitPerMin`）
- 回應**不含** `Retry-After` header，請自行退避
- 每日配額以**收件人數**計，不是請求數。`dailyMessageQuota` 為 `null` 代表不限

---

## 8. 參考實作

以下每一份都是完整可跑的。挑一個對應你的語言直接用。

### 8.1 Python 3

```python
"""NotifyLine client. 只依賴標準函式庫。"""
import base64
import hashlib
import hmac
import json
import time
import urllib.request
import urllib.error
import uuid

BASE = "https://notify.example.com"


class NotifyLineError(RuntimeError):
    def __init__(self, status, code, message):
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code


def _signed_request(method, path, client_id, secret, body_bytes=b"", extra_headers=None):
    timestamp = str(int(time.time()))
    nonce = str(uuid.uuid4())
    body_hash = hashlib.sha256(body_bytes).hexdigest()

    # 五段以 \n 串接，無結尾換行
    canonical = "\n".join([method, path, timestamp, nonce, body_hash])
    signature = base64.b64encode(
        hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).digest()
    ).decode("ascii")

    headers = {
        "X-Client-Id": client_id,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
    }
    if body_bytes:
        headers["Content-Type"] = "application/json"
    headers.update(extra_headers or {})

    request = urllib.request.Request(
        BASE + path, data=body_bytes or None, headers=headers, method=method
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as e:
        payload = json.loads(e.read() or b"{}")
        error = payload.get("error") or {}
        raise NotifyLineError(e.code, error.get("code"), error.get("message")) from None


def notify(client_id, secret, text, target="OWNER", idempotency_key=None):
    body = {"target": {"type": target}, "message": {"text": text}}

    # 先固定 bytes，再對「同一份 bytes」算雜湊並送出。
    # 序列化兩次是簽章失敗最常見的原因。
    body_bytes = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    headers = {"Idempotency-Key": idempotency_key or str(uuid.uuid4())}
    _, payload = _signed_request(
        "POST", "/api/v1/notifications", client_id, secret, body_bytes, headers
    )
    return payload["data"]["notificationId"]


def get_notification(client_id, secret, notification_id):
    _, payload = _signed_request(
        "GET", f"/api/v1/notifications/{notification_id}", client_id, secret
    )
    return payload["data"]


def whoami(client_id, secret):
    _, payload = _signed_request("GET", "/api/v1/whoami", client_id, secret)
    return payload["data"]


if __name__ == "__main__":
    import os

    cid = os.environ["NOTIFY_CLIENT_ID"]
    sec = os.environ["NOTIFY_SECRET"]

    print(whoami(cid, sec))
    nid = notify(cid, sec, "來自 Python 的測試")
    time.sleep(3)
    print(get_notification(cid, sec, nid)["status"])
```

### 8.2 Node.js 18+ / TypeScript

```typescript
import { createHash, createHmac, randomUUID } from "node:crypto";

const BASE = "https://notify.example.com";

export class NotifyLineError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(`${status} ${code}: ${message}`);
  }
}

interface Credentials {
  clientId: string;
  secret: string;
}

async function signedRequest(
  method: "GET" | "POST",
  path: string,
  { clientId, secret }: Credentials,
  bodyBytes: Uint8Array = new Uint8Array(0),
  extraHeaders: Record<string, string> = {},
): Promise<any> {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const nonce = randomUUID();
  const bodyHash = createHash("sha256").update(bodyBytes).digest("hex");

  // 五段以 \n 串接，無結尾換行
  const canonical = [method, path, timestamp, nonce, bodyHash].join("\n");
  const signature = createHmac("sha256", Buffer.from(secret, "utf8"))
    .update(canonical, "utf8")
    .digest("base64");

  const headers: Record<string, string> = {
    "X-Client-Id": clientId,
    "X-Timestamp": timestamp,
    "X-Nonce": nonce,
    "X-Signature": signature,
    ...extraHeaders,
  };
  if (bodyBytes.length > 0) headers["Content-Type"] = "application/json";

  const response = await fetch(BASE + path, {
    method,
    headers,
    body: bodyBytes.length > 0 ? bodyBytes : undefined,
    signal: AbortSignal.timeout(15_000),
  });

  const payload = await response.json();
  if (!response.ok) {
    throw new NotifyLineError(response.status, payload?.error?.code, payload?.error?.message);
  }
  return payload.data;
}

export async function notify(
  credentials: Credentials,
  text: string,
  target = "OWNER",
  idempotencyKey = randomUUID(),
): Promise<string> {
  // 先固定 bytes，再對「同一份 bytes」算雜湊並送出
  const bodyBytes = new TextEncoder().encode(
    JSON.stringify({ target: { type: target }, message: { text } }),
  );

  const data = await signedRequest("POST", "/api/v1/notifications", credentials, bodyBytes, {
    "Idempotency-Key": idempotencyKey,
  });
  return data.notificationId;
}

export const getNotification = (c: Credentials, id: string) =>
  signedRequest("GET", `/api/v1/notifications/${id}`, c);

export const whoami = (c: Credentials) => signedRequest("GET", "/api/v1/whoami", c);
```

### 8.3 Go

```go
package notifyline

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

const base = "https://notify.example.com"

type Client struct {
	ClientID string
	Secret   string
	HTTP     *http.Client
}

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type envelope struct {
	Success bool            `json:"success"`
	Data    json.RawMessage `json:"data"`
	Error   *apiError       `json:"error"`
}

func (c *Client) do(method, path string, body []byte, extra map[string]string) (json.RawMessage, error) {
	timestamp := strconv.FormatInt(time.Now().Unix(), 10)
	nonce := uuid.NewString()
	sum := sha256.Sum256(body)

	// 五段以 \n 串接，無結尾換行
	canonical := strings.Join([]string{method, path, timestamp, nonce, hex.EncodeToString(sum[:])}, "\n")
	mac := hmac.New(sha256.New, []byte(c.Secret))
	mac.Write([]byte(canonical))
	signature := base64.StdEncoding.EncodeToString(mac.Sum(nil))

	req, err := http.NewRequest(method, base+path, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Client-Id", c.ClientID)
	req.Header.Set("X-Timestamp", timestamp)
	req.Header.Set("X-Nonce", nonce)
	req.Header.Set("X-Signature", signature)
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range extra {
		req.Header.Set(k, v)
	}

	client := c.HTTP
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)
	var env envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, fmt.Errorf("unexpected response (HTTP %d): %s", resp.StatusCode, raw)
	}
	if !env.Success {
		return nil, fmt.Errorf("%d %s: %s", resp.StatusCode, env.Error.Code, env.Error.Message)
	}
	return env.Data, nil
}

func (c *Client) Notify(text, target string) (string, error) {
	// 先固定 bytes，再對「同一份 bytes」算雜湊並送出
	body, err := json.Marshal(map[string]any{
		"target":  map[string]string{"type": target},
		"message": map[string]string{"text": text},
	})
	if err != nil {
		return "", err
	}

	data, err := c.do("POST", "/api/v1/notifications",
		body, map[string]string{"Idempotency-Key": uuid.NewString()})
	if err != nil {
		return "", err
	}

	var accepted struct {
		NotificationID string `json:"notificationId"`
	}
	err = json.Unmarshal(data, &accepted)
	return accepted.NotificationID, err
}
```

### 8.4 Bash（curl + openssl）

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE="https://notify.example.com"
PATH_NOTIFY="/api/v1/notifications"
TEXT="${1:-測試訊息}"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# 一定要寫進檔案再用 --data-binary。
# 用 --data-raw 傳非 ASCII 內容時，argv 的編碼轉換會改變實際送出的位元組，
# 於是雜湊與送出的內容不一致，簽章必定失敗。
printf '%s' "{\"target\":{\"type\":\"OWNER\"},\"message\":{\"text\":\"${TEXT}\"}}" > "$TMP/req.json"

TS="$(date +%s)"
NONCE="$(uuidgen 2>/dev/null || openssl rand -hex 16)"
HASH="$(openssl dgst -sha256 -hex < "$TMP/req.json" | awk '{print $NF}')"

# 五段以 \n 串接，無結尾換行
CANONICAL="$(printf '%s\n%s\n%s\n%s\n%s' "POST" "$PATH_NOTIFY" "$TS" "$NONCE" "$HASH")"
SIG="$(printf '%s' "$CANONICAL" | openssl dgst -sha256 -hmac "$NOTIFY_SECRET" -binary | base64)"

curl -sS -X POST "${BASE}${PATH_NOTIFY}" \
  -H 'Content-Type: application/json' \
  -H "X-Client-Id: ${NOTIFY_CLIENT_ID}" \
  -H "X-Timestamp: ${TS}" \
  -H "X-Nonce: ${NONCE}" \
  -H "X-Signature: ${SIG}" \
  -H "Idempotency-Key: ${NONCE}" \
  --data-binary "@$TMP/req.json"
```

### 8.5 PowerShell 7+

```powershell
function Send-NotifyLine {
    param(
        [Parameter(Mandatory)] [string] $Text,
        [string] $Target   = "OWNER",
        [string] $Base     = "https://notify.example.com",
        [string] $ClientId = $env:NOTIFY_CLIENT_ID,
        [string] $Secret   = $env:NOTIFY_SECRET
    )

    $path = "/api/v1/notifications"
    $json = ConvertTo-Json -Compress @{
        target  = @{ type = $Target }
        message = @{ text = $Text }
    }
    # 先固定 bytes，再對「同一份 bytes」算雜湊並送出
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)

    $timestamp = [System.DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $nonce     = [System.Guid]::NewGuid().ToString()
    $hash      = [System.BitConverter]::ToString(
        [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    ).Replace("-", "").ToLowerInvariant()

    # 一定要用 "`n"（LF）。[Environment]::NewLine 在 Windows 上是 CRLF，
    # 多出來的 \r 會讓每一次簽章都失敗。
    $canonical = ("POST", $path, $timestamp, $nonce, $hash) -join "`n"

    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($Secret))
    $signature = [System.Convert]::ToBase64String(
        $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical)))

    Invoke-RestMethod -Uri "$Base$path" -Method POST -Body $bytes `
        -ContentType "application/json" -Headers @{
            "X-Client-Id"     = $ClientId
            "X-Timestamp"     = $timestamp
            "X-Nonce"         = $nonce
            "X-Signature"     = $signature
            "Idempotency-Key" = $nonce
        }
}
```

### 8.6 Java 17+

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class NotifyLineClient {

    private static final String BASE = "https://notify.example.com";

    private final String clientId;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public NotifyLineClient(String clientId, String secret) {
        this.clientId = clientId;
        this.secret = secret;
    }

    public String notify(String text, String target) throws Exception {
        // 先固定 bytes，再對「同一份 bytes」算雜湊並送出
        String json = """
                {"target":{"type":"%s"},"message":{"text":"%s"}}"""
                .formatted(target, text.replace("\\", "\\\\").replace("\"", "\\\""));
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = send("POST", "/api/v1/notifications", body,
                "Idempotency-Key", UUID.randomUUID().toString());

        if (response.statusCode() != 202) {
            throw new IllegalStateException("send failed: " + response.body());
        }
        return response.body();
    }

    private HttpResponse<String> send(String method, String path, byte[] body,
                                      String... extraHeaders) throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String bodyHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body));

        // 五段以 \n 串接，無結尾換行
        String canonical = String.join("\n", method, path, timestamp, nonce, bodyHash);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(15))
                .header("X-Client-Id", clientId)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature);

        if (body.length > 0) {
            request.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        for (int i = 0; i < extraHeaders.length; i += 2) {
            request.header(extraHeaders[i], extraHeaders[i + 1]);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
```

---

## 9. 接入檢查清單

依序做，不要跳：

- [ ] 用 §3.4 的測試向量驗證簽章實作，得到
      `A8InT6lAaimJ+IRPaMuBVJxCgNY+V3Gckgw0px/P9MA=`
- [ ] 打 `GET /api/v1/whoami`，確認回 200 且 scope 符合預期
- [ ] 打 `POST /api/v1/notifications`，`target.type` 用 `whoami` 顯示的 scope 對應的型別
- [ ] 確認收到 202 且拿得到 `notificationId`
- [ ] 等 3 秒後 `GET /api/v1/notifications/{id}`，確認 `status` 是 `SUCCEEDED`
- [ ] 加上 `Idempotency-Key`，並確保逾時重試時沿用同一把
- [ ] 錯誤處理依 `error.code` 分支，區分「可重試」與「不可重試」
- [ ] secret 從環境變數或 secret manager 讀取，不在原始碼裡

## 10. 401 的排查順序

`AUTH_INVALID_SIGNATURE` 是最常遇到的，按這個順序查：

1. **canonical string 用的是 LF 不是 CRLF？** Windows 上最常見
2. **結尾多了換行？** 五段之間有 4 個 LF，最後一段之後沒有
3. **path 帶了網域或 query string？** 只能是 `/api/v1/notifications`
4. **body 序列化了兩次？** 見 §3.3
5. **secret 或 canonical 用了非 UTF-8 編碼？** 中文內容會立刻暴露這個問題
6. **method 沒大寫？**
7. **base64 用了 URL-safe 變體？** 必須是標準 Base64
8. **client id 打錯或憑證已作廢？** 這兩種也回同一個錯誤碼

前七項都可以用 §3.4 的測試向量離線驗證，不需要連線。
