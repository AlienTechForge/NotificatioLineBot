# 12 — API 監控易用性升級

> 目標：把「手打 URL、手填 header、手寫 JsonPointer」變成
> **貼上 F12 複製的請求 → 點選要監控的欄位 → 完成**。
>
> 延伸自 [11-API監控輪詢設計](11-API監控輪詢設計.md)。該文件的安全約束（§5 SSRF 防護、
> §8 防洗版）全部繼續適用，本文件只增加東西，不放寬任何一條。

---

## 0. 一個會改變操作流程的事實

**Chrome 的「Copy as fetch」不含 cookie。** cookie 是 forbidden header，由瀏覽器自行附加，
不會出現在複製出來的字串裡。

| DevTools 選項 | 含 cookie | 建議 |
|---|---|---|
| Copy as cURL (bash) | ✅ | **要帶登入狀態就用這個** |
| Copy as fetch (Node.js) | ✅ | 可用 |
| Copy as fetch | ❌ | 只適合公開 API |

UI 必須在貼上框旁標明這件事，否則使用者會貼了「Copy as fetch」然後困惑為什麼一直 401。

---

## 1. 決策摘要

| 項目 | 決定 |
|---|---|
| cookie jar 範圍 | **依 host 自動共用**。同網域所有監控共用一份登入狀態，過期重貼一次全部復活 |
| Set-Cookie | **自動寫回 jar**。站台滾動刷新 session 時自己續期，不用一直重貼 |
| 推進方式 | W5 → W6 → W7 依序，每波獨立可上線 |
| 解析方式 | **純文字解析，絕不 eval**。`fetch(...)` 當字串處理，不進任何 JS 引擎 |
| 動態變數 | **封閉集合**，不開放自訂。開放了就能拼出任意 host，變成 SSRF 繞道 |

---

## 2. W5 — 匯入解析 + 請求模板

無 migration。模板寫在既有的 `url` / `headers` / `request_body` 欄位裡。

### 2.1 套件

```
com.jason.notifyline.monitor.importer
├── ImportedRequest.java      record(url, method, headers, body)
├── RequestImporter.java      統一入口，自動辨識格式
├── CurlParser.java
├── FetchParser.java
└── MonitorBundleParser.java  自訂 JSON 格式
```

```
com.jason.notifyline.monitor.request
└── RequestTemplate.java      URL / header / body 的動態值替換
```

### 2.2 cURL 解析

處理 Chrome「Copy as cURL (bash)」與「(cmd)」兩種。需要一個小型 shell tokenizer：

- bash 形式：單引號字串，`'\''` 是跳脫序列，`\` 換行接續
- cmd 形式：雙引號字串，`^` 換行接續
- 旗標：`-H/--header`、`-X/--request`、`-d/--data/--data-raw/--data-binary`、
  `-b/--cookie`、`-A/--user-agent`；`--compressed` 忽略
- **`-L/--location` 要拒絕**（附錯誤說明），不是忽略 —— 這個功能永不跟隨 redirect，
  靜靜忽略會讓使用者以為有跟隨
- URL = 第一個非旗標參數

### 2.3 fetch 解析

```js
fetch("https://example.com/api", { "headers": {...}, "body": null, "method": "GET" });
```

取第一個字串字面量當 URL，取其後第一個 `{` 到對應 `}` 的區段丟給 Jackson（寬鬆模式，
容忍尾逗號）。只讀 `headers` / `method` / `body` 三個鍵，其餘（`mode`、`credentials`、
`referrer`…）忽略。

### 2.4 自訂 JSON 格式

給使用者拿去餵 AI 的格式。UI 要能一鍵複製這份 schema。

```json
{
  "version": 1,
  "name": "範例監控",
  "request": {
    "url": "https://example.com/api/orders?since={{now-1h.iso8601}}",
    "method": "GET",
    "headers": { "accept": "application/json" },
    "body": null
  },
  "intervalSeconds": 300,
  "compare": {
    "mode": "NEW_ITEMS",
    "itemPointer": "/data/orders",
    "itemKeyPointer": "/id",
    "rules": [{ "name": "amount", "pointer": "/amount" }]
  },
  "messageTemplate": "新訂單 {{item.amount}} 元"
}
```

### 2.5 動態值模板（`RequestTemplate`）

送出前套用在 URL、每個 header value、body。**封閉集合**：

| 佔位符 | 產出 |
|---|---|
| `{{now.epochSeconds}}` / `{{now.epochMillis}}` | 數字 |
| `{{now.iso8601}}` | UTC，`2026-08-24T03:00:00Z` |
| `{{now.format:PATTERN}}` | `Asia/Taipei`，`PATTERN` 走 `DateTimeFormatter` |
| `{{now±N[smhd].…}}` | 位移，例：`{{now-7d.format:yyyy-MM-dd}}`、`{{now+1h.epochSeconds}}` |
| `{{uuid}}` | 隨機 UUID |

規則：

- `PATTERN` 只允許 `[yMdHmsSa\-/:.\s']`，長度上限 32。不可放任意字元進
  `DateTimeFormatter.ofPattern` —— 畸形 pattern 會拋例外，變成每輪必失敗
- 未知佔位符 → **拋錯拒絕存檔**（與訊息模板不同：訊息模板替換成破折號是為了不擋發送，
  但請求模板打錯會導致每一輪都打到錯的網址，早點失敗比較好）
- 取 `Clock` bean，測試要能固定時間

**替換後的 URL 必須再過一次 `OutboundUrlGuard`。** 目前變數集合產不出 host，
但這是縱深防禦：日後有人加了新變數時，這道檢查已經在那裡了。

### 2.6 匯入端點

`POST /admin/api/monitors/import`，body：`{ "raw": "<貼上的內容>" }`

- 回傳解析後的 `ImportedRequest`，**不存檔**
- 輸入長度上限 64 KB
- 解析出的 URL 立刻過 `OutboundUrlGuard`，擋掉就回錯誤，不回傳解析結果
- **回應必須帶 `Cache-Control: no-store`**，且解析內容**絕不可寫進任何 log**（含 debug）
  —— 貼上的內容含 cookie 與 API token

---

## 3. W6 — 站台登入狀態

### 3.1 `V5__site_session.sql`

```sql
CREATE TABLE site_session (
    host              VARCHAR(255) PRIMARY KEY,
    jar_ciphertext    BYTEA        NOT NULL,
    jar_iv            BYTEA        NOT NULL,
    jar_key_version   INT          NOT NULL,
    cookie_names      TEXT         NOT NULL,
    last_refreshed_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

- `host` 正規化後當主鍵（`IDN.toASCII` + `Locale.ROOT` 小寫），與
  `OutboundUrlGuard` 的正規化方式一致
- jar 明文格式：`{"session":"abc","csrf":"def"}`，AAD = `"site_session:" + host`
- `cookie_names` 明文存 cookie **名稱**（逗號分隔），純粹給後台顯示
  「這個站存了哪些 cookie」用。**值絕不明文落地**

### 3.2 ⚠️ 這一波最危險的地方

**把 A 站的 cookie 送到 B 站 = 把你的 session token 洩漏給第三方。**

附加 cookie 前的比對必須照 cookie 規則：

- 請求 host 與 jar host **完全相等**，或請求 host 以 `"." + jarHost` 結尾
- **不可用** `contains`、`endsWith(jarHost)`、`startsWith` —— `endsWith("example.com")`
  會讓 `evil-example.com` 拿到你的 cookie
- 比對前兩邊都走同一套正規化

這條與 `UriHostValidator.isAllowed()`（`UriHostValidator.java:151`）是同一個陷阱的
不同版本，實作可以互相參照。

### 3.3 流程

**匯入時**：從 `cookie:` header（cURL 的 `-b`）抽出 cookie → 寫進該 host 的 jar →
**從 monitor 自己的 headers 移除**。同站台多個監控共用一份，過期只要重貼一次。

**抓取時**：依請求 host 找 jar，比對通過才附上 `Cookie` header。

**回應時**：解析 `Set-Cookie`（`HttpResponse.headers().allValues("set-cookie")`），
合併進 jar，更新 `last_refreshed_at`。
注意：`Set-Cookie` 的 `Domain=` 屬性若指向不同網域，**忽略該筆**，不可讓回應
自己擴張 jar 的適用範圍。

**過期偵測**：連續 N 次（沿用 `failure-notify-threshold`）401/403 → 發一則
「{host} 登入已過期，請重新貼上請求」。沒有這個，監控會靜悄悄死掉而使用者不知道。

### 3.4 端點

| 方法 | 路徑 | 用途 |
|---|---|---|
| GET | `/admin/api/sessions` | 列出：host、cookie 名稱、更新時間。**絕不回傳值** |
| DELETE | `/admin/api/sessions/{host}` | 清除該站登入狀態 |

### 3.5 不做

**不存帳號密碼、不做自動登入。** 每個站台的登入流程都不一樣（表單、OAuth、2FA、
驗證碼），做不完也維護不動。貼 cookie + 自動續期已經覆蓋實際需求。

---

## 4. W7 — 視覺化

### 4.1 匯入

貼上框（標明「要帶登入狀態請用 Copy as cURL」）→ 呼叫 `/monitors/import` →
自動填入編輯抽屜的各欄位。旁邊放自訂 JSON schema 的一鍵複製。

### 4.2 欄位選取器（取代手打 JsonPointer）

按「立即測試」抓一次 → 把回應 JSON 渲染成可展開的樹 → **點任一節點就把它的
JsonPointer 插入解析規則**，並自動用最後一段路徑當欄位名（可改）。

`NEW_ITEMS` 模式下，點陣列節點 = 設 `itemPointer`，點陣列元素內的欄位 =
設 `itemKeyPointer`（自動轉成相對路徑）。

### 4.3 變動欄位偵測

「再抓一次」按鈕：間隔數秒抓第二次，diff 兩份回應，**把值有變的欄位標黃**。
直接回答「我該監控哪個欄位」——這是整個功能最花時間的一步。

注意時間戳類欄位每次都會變，要能讓使用者一眼看出「這個一直在變、不適合當監控目標」。

### 4.4 登入狀態頁

列出各 host、cookie 名稱與數量、最後更新時間、清除鈕。

### 4.5 前端約束

沿用既有慣例：全部 DOM 寫入走 `textContent` 不用 `innerHTML`；`$()`/`el()` 輔助函式；
CSRF 由既有 `call()` 包裝處理；新的 view 要註冊進 `VIEWS` map。

**樹狀檢視渲染的是第三方 API 的回應內容 —— 這正是 `innerHTML` 會變成 XSS 的地方。**

---

## 5. 測試要求

**W5**
- cURL：bash / cmd 兩種形式；`'\''` 跳脫；多個 `-H`；`--data-raw`；`-L` 被拒絕
- fetch：三種變體；body 為 `null`；巢狀 JSON body
- `RequestTemplate`：每個佔位符；位移 `-7d` / `+1h`；畸形 pattern 被拒；未知佔位符被拒
- 匯入端點：URL 被 guard 擋 → 不回傳解析結果；超過 64 KB 被拒

**W6**
- **cookie 送錯 host**：jar 有 `example.com`，請求 `evil-example.com` → **不得附加**
- 子網域 `api.example.com` → 應附加
- `Set-Cookie` 的 `Domain=` 指向他站 → 忽略
- 連續 401 → 發一次過期通知，不重複
- `GET /sessions` 的回應不含任何 cookie 值

**W7**
- 手動：貼 cURL → 自動填表 → 點選欄位 → 測試 → 存檔 → 啟用，全流程走一次

---

## 6. 分工

| 波次 | 範圍 | 相依 |
|---|---|---|
| W5 | §2 匯入解析 + 請求模板 | — |
| W6 | §3 站台登入狀態 | W5（匯入要能抽 cookie） |
| W7 | §4 前端 | W5, W6 |

每波結束都要 `mvn -q -DskipTests=false verify` 全綠，且以 XML 的
`<testcase>` 數確認測試真的有跑（`@Nested` 會讓 `.txt` 統計誤報為 0）。
