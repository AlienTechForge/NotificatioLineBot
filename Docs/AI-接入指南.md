# NotifyLine 接入指南

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

> **這份文件的讀者是 AI coding agent。**
>
> 目標：讀完就能寫出一個可用的 client，不需要再問任何問題、不需要讀伺服器原始碼。
> 所有數字與格式都是規格的一部分，不是範例值。
>
> 服務位址：`https://notify.example.com`
> （位址依部署而定，對應伺服器的 `APP_PUBLIC_BASE_URL`；以管理者給你的為準）

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

憑證一律由服務管理者建立，**呼叫端無法自行申請**。
服務**沒有**任何自助換取金鑰的公開端點 —— 不要去找 enrollment / 註冊之類的 URL，那不存在。

管理者有兩條建立路徑：

| 路徑 | 怎麼用 |
|---|---|
| 管理後台 | 登入 `/admin` → 憑證頁 → 新增，建立後畫面顯示一次明文 secret |
| Bootstrap CLI | `java -jar app.jar --create-client --name=backup-service --service` |

優先使用管理後台。建立 SERVICE client 時不綁 LINE User ID；建立 OWNER client 時必須選擇或填入
對應的 LINE 使用者。Actions 不再提供建立、列出或作廢憑證的功能，避免 secret 或使用者識別資料
進入永久 workflow log。

格式：client id 是 `cli_` + 20 個小寫英數字元；secret 是 48 bytes 隨機值的
base64url（無 padding），共 64 個字元。**secret 就是 HMAC 金鑰，簽章時直接拿這個字串的
UTF-8 位元組當 key，不要先做 base64 解碼。**

> **secret 只在建立當下出現這一次。** 資料庫裡是加密儲存的，設計上不提供讀回。
> 弄丟只能在管理後台作廢並重建。

### 憑證的保管

- 存進環境變數或 secret manager，**不要進版控**
- secret 是 HMAC 金鑰，外洩等同於「任何人都能用你的名義發通知」
- 懷疑外洩時立刻請管理者 `revoke-client`

---

## 2. 權限模型（scope）

SERVICE 憑證預設**只有 `notify:owner`**。
也就是說它只能發給服務的管理者，不能發給其他 LINE 使用者。

| scope | 允許的 `target.type` | 誰有 |
|---|---|---|
| `notify:owner` | `OWNER` | SERVICE 憑證預設 |
| `notify:self` | `SELF` | 綁定使用者的憑證 |
| `notify:user` | `USER` | 僅 OWNER 憑證 |
| `notify:all` | `ALL` | 僅 OWNER 憑證 |
| `notify:raw` | 使用 `lineMessages` 欄位 | 需另外明確授予 |

`notify:user` / `notify:all` / `notify:raw` 三個是 OWNER-only，一般憑證拿不到。
其中 **`notify:raw` 連 OWNER 憑證都不會預設帶**，
要用進階模式必須請管理者單獨授予。

**如果你不確定自己有什麼權限，先打 `GET /api/v1/whoami`**（見 §6）。

scope 不足一律回 `403 SCOPE_DENIED`，不會退化成「送給比較少人」。

---

## 3. 認證：HMAC-SHA256

每個 `/api/v1/**` 的請求都要帶四個 header。

| Header | 內容 |
|---|---|
| `X-Client-Id` | `cli_` 開頭的 client id |
| `X-Timestamp` | 目前時間，Unix epoch **秒**，十進位字串 |
| `X-Nonce` | 每個請求都不同的隨機字串，**最長 64 字元**（資料庫欄位是 `VARCHAR(64)`），建議 UUID v4 |
| `X-Signature` | 見下方 |

header 名稱依 HTTP 慣例不分大小寫，但**值**是原封不動拿去算的，一個字元都不能差。
伺服器對 `X-Timestamp` 與 `X-Nonce` 會先 `trim()` 再放進 canonical string，
所以**值前後不要留空白**，否則你算的與伺服器算的會不一致。

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

```
✗  hash(serialize(obj))  … 然後  send(serialize(obj))     ← 兩次序列化，遲早分岔
✓  bytes = serialize(obj);  hash(bytes);  send(bytes)      ← 只有一份位元組
```

伺服器端是把**收到的原始位元組**整包快取起來直接算 SHA-256，中間不做任何反序列化、
不做正規化、不重排欄位。所以只要你送出的位元組跟你雜湊的位元組差一個 byte，就一定失敗。

同一個陷阱的變形，全部會壞：

- HTTP client 幫你把 `body` 物件再序列化一次（傳物件而不是 bytes 給它）
- 中間層加了 gzip 之後才算雜湊，或算完雜湊才 gzip
- 用 `--data-raw` 傳含非 ASCII 的字串，殼層／argv 改寫了編碼（見 §8.4）
- 先用 pretty-print 印出來除錯，然後不小心把那份縮排過的字串送出去

這是接入端最常見的錯誤，沒有之一。

### 3.4 測試向量

實作完先用這組驗證，**對不上就不要往下做**。這組向量完全離線，不需要有效憑證、
不需要連得到伺服器。
§8 的 Python / Node.js / Bash / PowerShell / Java 實作都應先對這組向量逐位元組驗證；
Go 版走的是完全相同的步驟。

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

這段 JSON 有 **51 個字元**，UTF-8 編碼後是 **55 bytes**（中文各 3 bytes）。
先對長度：拿到 51 代表你雜湊的是字元不是位元組；拿到 53 代表用了 Big5／GBK；
拿到 55 才往下比雜湊值。

### 3.5 伺服器端的驗證順序

依序檢查，任何一項失敗就回 401：

1. 四個 header 都存在 → 否則 `AUTH_MISSING_HEADER`
2. `X-Timestamp` 與伺服器時間差 **≤ 300 秒** → 否則 `AUTH_TIMESTAMP_SKEW`
3. client 存在且狀態為 ACTIVE → 否則 `AUTH_INVALID_SIGNATURE` / `AUTH_CLIENT_DISABLED`
4. 簽章比對（常數時間，`MessageDigest.isEqual`）→ 否則 `AUTH_INVALID_SIGNATURE`
5. nonce 未使用過 → 否則 `AUTH_NONCE_REPLAY`

補充：

- **時鐘同步很重要。** 偏差是取絕對值後與 300 秒比較（快慢都算），`= 300` 過、`> 300` 拒。
  容器裡跑的服務尤其要注意
- **nonce 在 600 秒內不可重複。** 每個請求產生一個新的 UUID 即可。
  nonce 是**驗簽通過之後**才寫入的，所以簽章錯誤的請求不會消耗掉那個 nonce ——
  修好簽章後可以用同一個 nonce 重送
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
| `Idempotency-Key` | 最長 128 字元，超過回 `400 VALIDATION_ERROR`。見 §4.6 |
| `X-Request-Id` | 你自己的追蹤碼。**最長 64 字元**，`[A-Za-z0-9_.:-]` 以外的字元會被換成 `_`；沒帶時伺服器自己產一個 UUID |

`X-Request-Id` 一律會回填在**回應的同名 header** 與錯誤信封的 `error.requestId`。
回報問題時附上它，管理者才查得到對應的伺服器日誌。

以上兩個 header **不參與簽章** —— canonical string 只有 §3.1 那五段。

### 4.2 Request body

```jsonc
{
  "target": {                 // 選填！省略時使用管理者設定的預設對象。見 §4.2.1
    "type": "OWNER",          // SELF | OWNER | USER | ALL
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

簡易模式最後產生的是**一則** LINE `text` 訊息：有 `title` 時是
`title + "\n" + text`，沒有時就是 `text` 本身。5000 字上限算的是合併後的長度。

`options` 整個省略時等同 `{"notificationDisabled": false, "persistPayload": true}`。

### 4.2.1 省略 target：使用預設通知對象

管理者可以在管理介面為每組憑證指定「預設通知對象」。設定之後，**請求可以完全
省略 `target`**：

```json
{ "message": { "text": "備份完成" } }
```

這是大多數 service 該用的寫法：要通知誰是部署時的決定，不是每次呼叫都要重複的參數。
之後管理者改了對象，你的程式一行都不用動。

| 情況 | 結果 |
|---|---|
| 有設預設對象，請求省略 `target` | 送給預設對象 |
| 有設預設對象，請求帶了 `target` | **以請求為準**，且照常檢查 scope |
| 沒設預設對象，請求省略 `target` | `400 VALIDATION_ERROR` |

**關鍵差異**：管理者設定的預設對象**不需要對應的 scope**。一組只有
`notify:owner` 的憑證，可以被管理者指向任意收件人組合 —— 授權行為是管理者做的。
但這不會讓它自己有能力指定收件人：請求裡出現 `target.type = USER` 仍然需要
`notify:user`。

打 `GET /api/v1/whoami` 看不到預設對象；那是管理端的設定，呼叫端不需要知道。
如果你省略 `target` 卻拿到 400，代表管理者還沒設定。

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

- `recipientCount` 是**去重之後**的人數。`userIds` 裡填了重複的 id 只算一次
- `batchCount` 是 `ceil(recipientCount / 500)` —— LINE 的 multicast 一次最多 500 人
- `status` 在正常受理時一定是 `QUEUED`；但**冪等重播**時回的是那筆通知的
  **當下狀態**，所以可能直接看到 `SUCCEEDED`。不要把「202 + 非 QUEUED」當成異常

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

**白名單預設是空的，而空白名單代表「一個外部連結都不准」。**
換句話說，除非管理者在 `app.allowed-uri-hosts`（環境變數 `APP_ALLOWED_URI_HOSTS`）
明確列了網域，否則任何 `http(s)://` 都會被擋。要放連結就先跟管理者確認。

其他規則：

- 子網域自動涵蓋：白名單有 `example.com` 時，`docs.example.com` 通過
- `evil-example.com`、`example.com.attacker.net` **不會**通過
- `tel:`、`mailto:`、`line://` 放行（LINE 原生 action）
- `javascript:`、`data:`、`vbscript:`、`file:`、`blob:`、`jar:` 拒絕。判定條件是
  **整個欄位值就是這樣一串**（例如 Flex 的 `"uri": "javascript:..."`），
  所以一般含冒號的文字（`Warning: disk full`、`ratio 16:9`）不受影響
- 連結後面黏著的中英文句尾標點（`.,;:!?)]}>'"。，、；：！？）】》」』`）會先被修掉再判斷
- 解析不出 host 的連結是**拒絕**，不是略過
- 巢狀掃描最多 32 層，超過回 `400 VALIDATION_ERROR`

被擋時錯誤訊息會指出是哪個連結（截斷至 120 字元）。要新增網域請找服務管理者。

### 4.6 冪等

帶 `Idempotency-Key` header（選填，最長 128 字元）：

| 情況 | 結果 |
|---|---|
| 同一把 key，**body 位元組完全相同** | 回傳原本那筆的結果（同一個 `notificationId`），不會重複發送 |
| 同一把 key，body 不同 | `409 IDEMPOTENCY_CONFLICT` |
| 不同 client 用同一把 key | 互不影響（範圍是 client + key） |
| 沒帶 key | 每次都是新的一筆 |

**逾時或連線中斷後的重試一定要帶同一把 key**，否則使用者會收到兩則相同通知。

比對依據是 request body 的原始位元組雜湊，所以重試時要送出**完全相同的 bytes**。
`X-Timestamp`、`X-Nonce`、`X-Signature` 要重算（時間過了、nonce 不能重用），
但 **body 一個 byte 都不能動** —— 這跟 §3.3 是同一件事。

重播走的是最前面的快路徑：不會再解析一次訊息、**不會再扣一次每日配額**。
所以「因為逾時而重送」在配額上仍然只算一次發送。

兩個併發請求帶同一把 key 同時打進來時，只有一個會真的建立通知，另一個會拿到
與前者相同的結果（不是 409，前提是 body 相同）。

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
| `PAYLOAD_GONE` | 取件時已經沒有可送的內容（`persistPayload: false` 送完即清空，或通知已被刪除），無法重送 |

**只能查自己送出的通知。** 查別人的與查不存在的都回 `404`（刻意相同，避免試探）。

### 輪詢建議

發送通常在 1 秒內完成 —— 受理當下就會直接踢一次派送，不必等排程。
若要確認結果：

1. 收到 202 後等 2–3 秒再查第一次
2. 未到終局狀態則以指數退避重查；呼叫端自行設定總等待上限

伺服器端的重試節奏（決定你最久要等多久）：

- 一個批次**最多嘗試 5 次**
- 暫時性失敗之間的退避是 **1、2、4、8 秒**，各加 ±20% 抖動
- 所以一個一直失敗的批次大約 **15～20 秒**後就會落到終局；加上排程取件的間隔
  （預設 10 秒一輪）、限速、斷路器與 LINE 呼叫耗時；60 秒不保證一定進入終局狀態
- 斷路器把呼叫擋下來時**不算一次嘗試**，這種情況會拉長，但不會提早耗盡重試次數

**不要用緊迫的迴圈輪詢** —— 查詢也計入速率限制。

---

## 6. 自我診斷

```
GET https://notify.example.com/api/v1/whoami
```

**接入時第一支要打的就是這個，也是出問題時第一支要回頭打的。**
它不需要 body、不需要任何 scope、不會發出任何通知，所以是最乾淨的自我診斷：
只要它回 200，就代表「憑證有效 + GET 空 body 的簽章路徑可用」。POST 仍需另外核對實際 body bytes、path、timestamp、nonce 與 scope。

簽章時 method 用 `GET`、path 用 `/api/v1/whoami`、body 雜湊用空位元組陣列的
SHA-256（`e3b0c442…`）。

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

| 欄位 | 怎麼讀 |
|---|---|
| `clientId` | 確認你用的是你以為的那組憑證 |
| `boundLineUserId` | `null` = SERVICE 憑證，**不能**用 `target.type = SELF`（會回 `CLIENT_NOT_BOUND`） |
| `scopes` | 已排序的 API 字串形式。決定你能用哪些 `target.type`，對照 §2 |
| `rateLimitPerMin` | `null` = 用系統預設（60 req/min） |
| `dailyMessageQuota` | `null` = 不限；有數字時那是**每日收件人數**上限 |

診斷用法：

| 現象 | 結論 |
|---|---|
| `whoami` 200 但發通知 401 | GET 空 body 的簽章已通過；再檢查 POST 的實際 body bytes、path、timestamp 與 nonce |
| `whoami` 401 `AUTH_INVALID_SIGNATURE` | client id 或 secret 錯，或 canonical string 組錯（§10） |
| `whoami` 401 `AUTH_TIMESTAMP_SKEW` | 本機時鐘要校時 |
| `whoami` 200 但發通知 403 | scope 不足，比對回傳的 `scopes` 與 §2 的表 |

**`whoami` 看不到「預設通知對象」**，那是管理端的設定（§4.2.1）。
省略 `target` 卻拿到 `400 VALIDATION_ERROR`，就代表管理者還沒替你設定。

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
| 400 | `VALIDATION_ERROR` | 請求格式或欄位不合法，或省略 `target` 但沒有預設對象 | 修正請求。**不要重試** |
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
| 429 | `RATE_LIMITED` | 超過每分鐘請求數 | 依 `Retry-After` 秒數退避後重試 |
| 429 | `CLIENT_QUOTA_EXCEEDED` | 超過每日收件人數配額 | 等待或請管理者調高 |
| 429 | `LINE_MONTHLY_QUOTA_EXCEEDED` | 保留碼。**目前伺服器沒有任何地方會回它**，列出來只是因為它在契約的 enum 裡 | — |
| 500 | `INTERNAL_ERROR` | 伺服器問題 | 退避重試，附上 `requestId` 回報 |

上表是目前公開 API 會遇到的錯誤。版本更新可能新增 code，所以 `default` 分支請當成「未知錯誤」處理，不要當成成功。

`error.requestId` 與回應的 `X-Request-Id` header 同值，回報問題時附上它。

### 該重試與不該重試

| 一律不重試 | 退避後可重試 |
|---|---|
| `VALIDATION_ERROR` | `RATE_LIMITED`（依 `Retry-After`） |
| `SCOPE_DENIED` | `INTERNAL_ERROR` |
| `URI_HOST_NOT_ALLOWED` | 連線失敗 / 逾時 |
| `IDEMPOTENCY_CONFLICT` | `CLIENT_QUOTA_EXCEEDED`（等窗口滑掉，可能要數小時） |
| `CLIENT_NOT_BOUND` / `NO_RECIPIENT` | |
| `PAYLOAD_TOO_LARGE` / `NOT_FOUND` | |
| `AUTH_*`（先修好設定） | |

**逾時特別注意**：請求可能其實已經被受理，只是回應沒回來。重試時務必沿用**同一把
`Idempotency-Key`**，否則會重複發送。

### 速率限制

兩層限制，錯誤碼不同，別搞混：

**每分鐘請求數（`RATE_LIMITED`）**

- 預設每個 client **每分鐘 60 個請求**（可個別調整，查 `whoami` 的 `rateLimitPerMin`）
- token bucket：容量就是每分鐘上限，並且是**連續補充**的，不是整分鐘歸零。
  所以被擋之後不必等到下一分鐘，等幾秒就會有新的 token
- 429 回應**會帶 `Retry-After` header**（單位秒，最小 1）。優先照它退避
- 計數維度只有 `clientId` —— 不分端點、不分方法。**查詢也算**
- 未通過驗簽的請求不計數（先驗簽、再限流）

**每日配額（`CLIENT_QUOTA_EXCEEDED`）**

- 以**收件人數**計，不是請求數。一次送給 300 人就算 300
- 視窗是**滾動的 24 小時**，不是自然日，所以不會在午夜整批釋放
- `whoami` 的 `dailyMessageQuota` 為 `null` 代表不限
- 錯誤訊息會寫出「已用 / 上限 / 這次需要多少」，可以直接拿來決定是要等還是要縮小批量
- 冪等重播不會重複扣配額（見 §4.6）
- **這個 429 不帶 `Retry-After`** —— `Retry-After` 只有 `RATE_LIMITED` 才有。
  拿到 `CLIENT_QUOTA_EXCEEDED` 卻讀不到 `Retry-After` 是正常的，不要因此當成錯誤

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
    # 一定要用 [ordered]。普通 @{} 是雜湊表，PowerShell 不保證列舉順序，
    # 送出的 JSON 欄位順序會跟你以為的不一樣（功能上仍可用，但對不上 §3.4 的測試向量）。
    $json = ConvertTo-Json -Compress ([ordered]@{
        target  = [ordered]@{ type = $Target }
        message = [ordered]@{ text = $Text }
    })
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
        // 先固定 bytes，再對「同一份 bytes」算雜湊並送出。
        //
        // ⚠️ 這裡的手工跳脫只處理 \ 與 "，足夠跑通範例，但**不足以正式使用**：
        //    text 若含換行或其他控制字元會產生不合法的 JSON，伺服器直接回 400。
        //    正式環境請改用 Jackson／Gson 產生 byte[]，再對那份 byte[] 簽章。
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
- [ ] 加上 `Idempotency-Key`，並確保逾時重試時沿用同一把、body bytes 一個都不改
- [ ] 錯誤處理依 `error.code` 分支，區分「可重試」與「不可重試」
- [ ] 429 時讀 `Retry-After` header 決定退避秒數
- [ ] 記錄回應的 `X-Request-Id`，出問題才有東西可以回報
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

另外兩個不會被測試向量抓到、但實務上出現過的：

- **header 值前後夾了空白或換行。** 伺服器對 `X-Timestamp`／`X-Nonce` 會 `trim()`
  才組 canonical string，你沒 trim 就會對不上
- **path 被中間層改寫。** 反向代理加了前綴、或把 `//` 正規化掉，伺服器看到的
  path 就不是你簽的那一個。用 `curl` 直打服務位址比對一次即可排除

排到最後仍然只有發通知失敗、`whoami` 正常，最常見是 §3.3 的 body bytes 不一致；仍要依實際 `error.code` 檢查 path、nonce、timestamp 與 scope。
