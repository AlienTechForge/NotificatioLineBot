# 12 — API 監控易用性升級

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

> 目標：把「手打 URL、手填 header、手寫 JsonPointer」變成
> **貼上 F12 複製的請求 → 點選要監控的欄位 → 完成**。
>
> 延伸自 [11-API監控輪詢設計](11-API監控輪詢設計.md)。該文件的安全約束（§5 SSRF 防護、
> §8 防洗版）全部繼續適用，本文件只增加東西，不放寬任何一條。
>
> **狀態：本文件定義的三個波次（W5 匯入解析、W6 站台登入狀態、W7 視覺化）全部已實作落地。**
> 這份文件同時是設計依據與實作現況記錄——各節在原本的設計意圖之外，補上程式碼裡實際的
> 行為、錯誤訊息與**刻意留下的缺口**（見 §7）。設計與實作不一致的地方一律以程式碼為準，
> 並在該處註明。
>
> 後續的「計算欄位」（`{{computed.NAME}}` / `{{secret.*}}`）不屬於本文件的波次範圍，
> 設計見 [13-監控計算欄位設計](13-監控計算欄位設計.md)；它擴充了 §2.5 的佔位符集合，
> 因此 §2.5 一併記錄。

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

**實作現況**：後台匯入貼上框下方確實有這段提示（`server/src/main/resources/static/admin/index.html:358`
的 `.hint`，內容為「要帶登入狀態請用 Chrome 的『Copy as cURL (bash)』——『Copy as fetch』不含 cookie」）。

---

## 1. 決策摘要

| 項目 | 決定 |
|---|---|
| cookie jar 範圍 | **依 host 自動共用**。同網域所有監控共用一份登入狀態，過期重貼一次全部復活 |
| Set-Cookie | **自動寫回 jar**。站台滾動刷新 session 時自己續期，不用一直重貼 |
| 推進方式 | W5 → W6 → W7 依序，每波獨立可上線 |
| 解析方式 | **純文字解析，絕不 eval**。`fetch(...)` 當字串處理，不進任何 JS 引擎 |
| 動態變數 | **封閉集合**，不開放自訂。開放了就能拼出任意 host，變成 SSRF 繞道 |

以上五條在實作裡全部成立，沒有任何一條在落地過程中被放寬。

### 1.1 實作現況

| 波次 | 落地情形 | 主要位置 |
|---|---|---|
| W5 匯入解析 + 請求模板 | **已實作** | `monitor/importer/`（7 個類別）、`monitor/request/RequestTemplate.java`、`POST /admin/api/monitors/import` |
| W6 站台登入狀態 | **已實作** | `monitor/session/`（5 個類別）、`V5__site_session.sql`、`GET`/`DELETE /admin/api/sessions` |
| W7 視覺化 | **已實作** | `static/admin/index.html`、`static/admin/assets/app.js`（貼上框、欄位選取樹、再抓一次比對、`#sessions` view） |

W6 的 cookie jar 不做自動登入；後續已由 [15-監控站台登入設計](15-監控站台登入設計.md) 新增 Cognito SRP provider。cookie 生命週期與 host 比對限制仍見 §7。

---

## 2. W5 — 匯入解析 + 請求模板

**狀態：已實作。** 無 migration。模板寫在既有的 `url` / `headers` / `request_body` 欄位裡。

### 2.1 套件

實際落地的檔案（比原設計多兩個：tokenizer 與 JSON 共用工具都被抽出來獨立）：

```
com.jason.notifyline.monitor.importer
├── ImportedRequest.java      record(url, method, headers, body)
├── RequestImporter.java      @Component，統一入口，自動辨識格式
├── ShellTokenizer.java       package-private final，cURL 用的小型 shell tokenizer
├── CurlParser.java
├── FetchParser.java
├── ImportJsonSupport.java    FetchParser 與 MonitorBundleParser 共用的 JSON 讀取
└── MonitorBundleParser.java  自訂 JSON 格式
```

```
com.jason.notifyline.monitor.request
└── RequestTemplate.java      @Component，URL / header / body 的動態值替換
```

**格式偵測（`RequestImporter.importRequest`）**：`raw` null/blank → `VALIDATION_ERROR`
（`Pasted content is empty.`）；`raw.strip()` 後**一次判定、無回溯**——以 `{` 開頭 →
`MonitorBundleParser`，以 `fetch(`（不分大小寫）開頭 → `FetchParser`，其餘一律 →
`CurlParser`。判錯了不會退回去試另一個解析器，錯誤訊息就是被選中那個解析器的訊息。

**`RequestImporter` 與三個解析器完全不寫任何 log（含 debug）**，錯誤訊息也絕不回顯
`raw` 片段——貼上的內容含 cookie 與 API token。

**`ImportedRequest` 是解析結果，不是已驗證可送出的請求。** 型別本身不強制任何檢查；
guard 由匯入端點（§2.6）負責。`method` 一律大寫，解析器沒看到明確方法時依內容推斷
（有 body → `POST`，否則 `GET`），「哪些方法合法」由 `ApiMonitor` 的 GET/POST 限制把關，
這一層只忠實回報解析結果。

### 2.2 cURL 解析

處理 Chrome「Copy as cURL (bash)」與「(cmd)」兩種。

#### `ShellTokenizer`——三狀態機

`NORMAL` / `SINGLE_QUOTE` / `DOUBLE_QUOTE`，**只涵蓋 Chrome DevTools「Copy as cURL」
實際會產生的語法子集**，不是完整 shell 文法（沒有變數展開、命令替換、管線、重導向）。

| 規則 | 行為 |
|---|---|
| 單引號 `'...'` | 內容**一律照字面**，不認識任何跳脫；遇 `'` 回 `NORMAL` |
| 雙引號 `"..."` | 只有 `\"` → `"`、`\\` → `\`；其餘反斜線照字面 |
| `NORMAL` 的空白 | ` `、`\t`、`\r`、`\n` 是 token 分隔 |
| 行尾續行 | `\`（bash）或 `^`（cmd）後接（可夾 `\r`）`\n` → 整段跳過，**不留字元也不當分隔**，讓接續行無縫接上 |
| `NORMAL` 的 `\X` | 跳脫下一個字元、照字面附加——`'it'\''s'` 中間的 `\'` 就靠這條 |
| 未閉合引號 | 結束時 `state != NORMAL` → `VALIDATION_ERROR`：`cURL command has an unterminated quote.` |

#### `CurlParser`——支援的旗標與**不支援時的行為**

先 `tokenize`；第一個 token 若是 `curl`（不分大小寫）則從 index 1 開始。

| 旗標 | 支援行為 | 出錯／缺值時 |
|---|---|---|
| `-H` / `--header` | 以第一個 `:` 切 name/value，各自 `trim()`；**同名後者覆蓋** | 沒有 `:` → `-H/--header value must be in "Name: Value" form.`；name 空 → `-H/--header value has an empty header name.` |
| `-X` / `--request` | 設 method，**最後出現者勝**，最終 `toUpperCase(Locale.ROOT)` | 缺值 → `-X/--request is missing its value.` |
| `-d` / `--data` / `--data-raw` / `--data-binary` | 多個時以 `&` 串接（form-encoded 語意）；`body == null` 代表完全沒出現過任何 data 旗標 | 缺值 → `{token} is missing its value.` |
| `-b` / `--cookie` | 存成 header，key 是**小寫字面 `cookie`** | 缺值 → `-b/--cookie is missing its value.` |
| `-A` / `--user-agent` | 存成 header，key 是**小寫字面 `user-agent`** | 缺值 → `-A/--user-agent is missing its value.` |
| `--url VALUE` | 明確處理，經 `stripCurlGlobEscapes`；**只有第一個 URL 生效** | 缺值 → `--url is missing its value.` |
| `--url=VALUE` | 明確處理（單一 token 形式），同樣 strip glob | — |
| `--compressed`、`-g`、`--globoff` | **認得但無動作**，且不消耗下一個 token | — |
| `-L` / `--location` | **明確拒絕**（不是靜默忽略） | `-L/--location is not supported: this feature never follows redirects. Remove the flag and paste the command again.` |
| 其他任何 `-` 開頭的 token | **靜默忽略旗標本身，且不消耗下一個 token** | 刻意保守：錯判成「帶參數」有可能吃掉真正的 URL |
| 非旗標參數 | 第一個當 URL（經 `stripCurlGlobEscapes`），**其後的靜默忽略** | 完全找不到 → `cURL command is missing a URL.` |

- `stripCurlGlobEscapes()`：`\[ \] \{ \}` → `[ ] { }`。**只能套在 URL 上**，絕不可套到
  header 值或 body——body 常是 JSON，反斜線有意義。
- method 推斷：有 `-X` 就用它；否則有 body → `POST`，無 body → `GET`（與真 curl 一致）。
- **「不支援時的行為」有兩種，刻意分開**：`-L` 會**明確報錯**（因為靜靜忽略會讓使用者
  以為有跟隨 redirect），其餘未知旗標與第二個以後的 URL 則是**靜默忽略**。

### 2.3 fetch 解析

```js
fetch("https://example.com/api", { "headers": {...}, "body": null, "method": "GET" });
```

取第一個字串字面量當 URL，取其後第一個 `{` 到對應 `}` 的區段丟給 Jackson（寬鬆模式，
容忍尾逗號）。只讀 `headers` / `method` / `body` 三個鍵，其餘（`mode`、`credentials`、
`referrer`…）忽略。

**純文字掃描，絕不 eval**——不進 `ScriptEngine`、不做任何表達式求值。逐步：

| 步驟 | 支援行為 | 不支援／出錯時 |
|---|---|---|
| 1. 找 URL | 第一個引號（`"` 或 `'`）起讀字串字面量 | 未閉合 → `fetch(...) URL string literal is not terminated.`；完全沒有引號 → `fetch(...) call is missing a URL string literal.` |
| 2. JS 跳脫 | `\n`→換行、`\t`→tab、`\r`→CR | 其餘（含 `\"`、`\'`、`\\`）一律**照字面**，不再解一層 |
| 3. 找 options | URL 之後**第一個 `{`**，配對到對應 `}`（會正確跳過字串字面量內的大括號） | 沒有 `{` → 視同 `fetch(url)`，回 `(url, "GET", Map.of(), null)`；找不到配對 → `fetch(...) options object has no matching '}'.`；區段內字串未閉合 → `fetch(...) options object has an unterminated string.` |
| 4. 解析 options | 寬鬆 mapper：**只啟用 `ALLOW_TRAILING_COMMA`** | **不容忍**無引號鍵名、單引號字串等其他 JSON5 特性；失敗 → `fetch(...) options object is not valid JSON.`（**刻意不回顯 Jackson 原訊息**） |
| 5. 取欄位 | `method`（缺 → `GET`）、`headers`、`body` | `await`、分號、變數賦值、`credentials`／`mode`／`referrer` 等**一概靜默忽略** |

`ImportJsonSupport`（與 `MonitorBundleParser` 共用）：
- `readHeaders`：非物件 → `Map.of()`；值是 value node 用 `asString()`，否則 `toString()`；
  以 `LinkedHashMap` 保留順序。
- `readBody`：null/missing → `null`；value node → `asString()`；否則 `toString()`
  （容忍使用者沒有先 stringify 的巢狀物件）。
- `readMethodOrDefault`：null/missing/非 value node → fallback；否則
  `asString().toUpperCase(Locale.ROOT)`。

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

**實作現況（`MonitorBundleParser`）**：用**嚴格** mapper（`JsonMapper.builder().build()`，
沒有任何寬鬆選項——與 §2.3 的 `fetch` 解析不同，這是給人／AI 產生的正式格式，尾逗號也不放行）。

| 檢查 | 錯誤訊息 |
|---|---|
| 不是合法 JSON | `JSON bundle is not valid JSON.` |
| 根不是物件 | `JSON bundle must be a JSON object.` |
| 缺 `request` 物件 | `JSON bundle is missing the "request" object.` |
| 缺／非字串／空白 `request.url` | `JSON bundle is missing "request.url".` |

⚠️ **這一層只讀 `request.{url, method, headers, body}`。** 上面 schema 裡的 `name`、
`intervalSeconds`、`compare`、`messageTemplate` 這些**監控層級欄位本層完全不解析**
（`MonitorBundleParser` 類別註解說明是刻意的：匯入端點的回傳型別是 `ImportedRequest`，
只有請求四欄）。這些欄位對使用者仍有意義——它們是給 AI 產出時的上下文與給人閱讀用的——
但**不會**自動填進編輯抽屜的對應欄位，使用者要自己照著填。UI 的「複製自訂 JSON 格式」
按鈕複製的是 `static/admin/assets/app.js:670` 的 `MONITOR_IMPORT_SCHEMA` 常數。

### 2.5 動態值模板（`RequestTemplate`）

送出前套用在 URL、每個 header value、body。**封閉集合**：

| 佔位符 | 產出 |
|---|---|
| `{{now.epochSeconds}}` / `{{now.epochMillis}}` | 數字 |
| `{{now.iso8601}}` | UTC，`2026-08-24T03:00:00Z` |
| `{{now.format:PATTERN}}` | `Asia/Taipei`，`PATTERN` 走 `DateTimeFormatter` |
| `{{now±N[smhd].…}}` | 位移，例：`{{now-7d.format:yyyy-MM-dd}}`、`{{now+1h.epochSeconds}}` |
| `{{uuid}}` | 隨機 UUID |
| `{{computed.NAME}}` | 計算欄位的求值結果（後續擴充，見 [13-監控計算欄位設計](13-監控計算欄位設計.md)） |

規則：

- `PATTERN` 只允許 `[yMdHmsSa\-/:.\s']`，長度上限 32。不可放任意字元進
  `DateTimeFormatter.ofPattern` —— 畸形 pattern 會拋例外，變成每輪必失敗
- 未知佔位符 → **拋錯拒絕存檔**（與訊息模板不同：訊息模板替換成破折號是為了不擋發送，
  但請求模板打錯會導致每一輪都打到錯的網址，早點失敗比較好）。錯誤訊息回顯的佔位符
  文字上限 100 字元，避免異常長的輸入撐爆訊息
- 取 `Clock` bean，測試要能固定時間

**刻意不接受 `{{secret.*}}`。** secret 只能透過計算欄位間接使用，這樣「secret 可能出現在
哪裡」的範圍被限制在 `ComputedFieldEvaluator` 一個類別內，不會散落到 URL／header 組裝邏輯裡。

**替換後的 URL 必須再過一次 `OutboundUrlGuard`。** 目前 computed 變數可引用使用者定義內容，
但這是縱深防禦：日後有人加了新變數時，這道檢查已經在那裡了。`RequestTemplate` 本身
**不會、也不能**呼叫 guard——它不知道呼叫端是排程輪詢（`ApiMonitorRunner`）還是後台
試跑（`AdminService`），這兩條路徑分屬不同的交易／非交易邊界。**每個用 `render` 處理
URL 的呼叫端，都必須在替換完成後、實際送出之前自己再呼叫一次 `OutboundUrlGuard.check()`。**

#### `RequestTemplate.Session`——一次請求只取一次「現在」

實作上多了一層設計裡沒有的 `Session(Instant, UUID)`：

- **同一次請求裡的 URL、每個 header、body、全部計算欄位共用同一個 `Session`**，
  呼叫端在處理一個請求的最開始呼叫一次 `newSession()`。
- 理由是硬故障而非潔癖：早期版本每個佔位符各取一次 `Instant.now(clock)`／
  `UUID.randomUUID()`。沒有簽章時只是小瑕疵；**有簽章時，計算欄位用 T1 算出 sign、
  `timestamp` header 卻送出 T2**，第三方 API 回 401，而且是幾百輪才發生一次的間歇性
  失敗，長得像對方不穩定，其實是我方同一次請求裡用了兩個不同的「現在」。
- ⚠️ 無參數的 `render(String)` / `renderHeaders(Map)` 仍然存在，但**每次呼叫各自建立
  一個新的 ad-hoc session 且不接受 `{{computed.*}}`**，只適合「只在乎語法對不對」的
  場合（如存檔時的語法驗證）。**用在真要送出的請求上會讓簽章與時間戳對不上**——
  類別註解有警告，但型別上沒有任何阻擋（見 §7）。

⚠️ **與訊息模板的佔位符集合不同且行為相反**：`MessageTemplate` 只認 `{{now}}`（無子指令），
未知佔位符 → `—` 照樣送出；`RequestTemplate` 認 `{{now.*}}`／`{{uuid}}`／`{{computed.*}}`，
未知 → 存檔時 400。使用者很容易在訊息模板裡寫 `{{now.epochSeconds}}` 而只得到 `—`。
後台的「完整變數對照表」（`index.html`）與 `{{` 自動完成把兩組集合分開列，就是為了這件事。

### 2.6 匯入端點

`POST /admin/api/monitors/import`，body：`{ "raw": "<貼上的內容>" }`

- 回傳解析後的 `ImportedRequest`，**不存檔監控本身**
- 輸入長度上限 64 KB，**以 UTF-8 位元組數檢查，不是字元數**（貼上內容常帶中文 header 值，
  用字元數當上限會低估實際傳輸量）；超過 → `PAYLOAD_TOO_LARGE`
- 解析出的 URL 立刻過 `OutboundUrlGuard`，擋掉就回錯誤，不回傳解析結果
- **回應必須帶 `Cache-Control: no-store`**，且解析內容**絕不可寫進任何 log**（含 debug）
  —— 貼上的內容含 cookie 與 API token

**實作現況（`AdminController.importMonitor` → `AdminService.importMonitorRequest`）**，
執行順序：

1. `raw` UTF-8 位元組 > 64 KB → `PAYLOAD_TOO_LARGE`。
2. `requestImporter.importRequest(raw)`（§2.1 的格式偵測）。
3. `new URI(imported.url())`——**刻意用 checked 的 `new URI(String)` 而不是
   `URI.create(String)`**，後者把 `URISyntaxException` 包成 `IllegalArgumentException`，
   `getIndex()` / `getReason()` 這些「哪個字元、第幾個位置」的細節就丟了。
   失敗 → `VALIDATION_ERROR`；對外訊息**回顯 URL**（含 token 的 URL 仍屬敏感資料，不能再轉貼到日誌或公開回報），另給一版**不含 URL** 的訊息供寫進日誌（URL 常帶 token/sign）。
4. **`OutboundUrlGuard.check(uri)`；擋下就丟例外、什麼都不回**——否則這個端點會變成
   「先幫你把 cookie/token 解出來，再告訴你打不到」的外洩管道。
5. header 裡若有 `cookie`（**大小寫不拘**，來自 cURL 的 `-b`、`-H`，或 `fetch(...)` 的
   headers 物件）→ 寫進 `SiteSessionService` 對應 host 的 jar，並**從回傳給呼叫端的
   header 移除**（§3.3）。

**刻意不加 `@Transactional`**：`guard.check()` 內含 DNS 解析（外部 I/O），交易絕不可以
撐過它。真正需要交易的只有 `SiteSessionService.importCookies` 那段 DB 寫入，它自己是
獨立的短交易。

前端在解析成功後**立即清空貼上框**（`app.js` 的註解：內容含 cookie／token，不留在 DOM）。

---

## 3. W6 — 站台登入狀態

**狀態：已實作**，套件 `com.jason.notifyline.monitor.session`：

```
SiteSession.java           entity，表 site_session
SiteSessionRepository.java
SiteSessionService.java    @Service，唯一的加解密與比對入口
CookieCodec.java           final、不可實例化，Cookie / Set-Cookie 的純字串解析與組裝
HostMatcher.java           final、不可實例化，⚠️ 這個功能最危險的地方（§3.2）
```

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
  `OutboundUrlGuard` 的正規化方式一致。**不另配序號主鍵**——這張表天生就是「依 host
  查一筆」，沒有同一個 host 存多筆的情境
- jar 加密：**AES-256-GCM**，與 `client.secret_ciphertext` / `api_monitor.headers_ciphertext`
  同一套金鑰管理（`jar_key_version` 記錄金鑰版本）。`jar_iv` 是 12 bytes，**每次回寫都
  重新產生**——GCM 在相同金鑰下重用 IV 會直接洩漏明文
- **AAD = `"site_session:" + host`**。這裡沒有 `api_monitor` header 那種「要先 insert
  拿到 id 才能組 AAD」的順序陷阱：`host` 是主鍵，加密前必定已經存在
- jar 明文格式：`{"cookieName":"value",...}`，例如 `{"session":"abc","csrf":"def"}`
- `cookie_names` 明文存 cookie **名稱**（逗號分隔），純粹給後台顯示
  「這個站存了哪些 cookie」用。**值絕不明文落地，也絕不透過任何 API 回傳**
- `last_refreshed_at`：使用者重貼（匯入）與 `Set-Cookie` 合併回寫**兩種來源都算**，
  `applyJar()` 一律推到 `now`
- entity 的 `byte[]` getter 一律 `clone()`；`toString()` 不輸出密文與 IV（cookie 名稱
  視為非機密）

### 3.2 ⚠️ 這一波最危險的地方

**把 A 站的 cookie 送到 B 站 = 把你的 session token 洩漏給第三方。**

附加 cookie 前的比對必須照 cookie 規則：

- 請求 host 與 jar host **完全相等**，或請求 host 以 `"." + jarHost` 結尾
- **不可用** `contains`、`endsWith(jarHost)`、`startsWith` —— `endsWith("example.com")`
  會讓 `evil-example.com` 拿到你的 cookie
- 比對前兩邊都走同一套正規化

這條與 `UriHostValidator.isAllowed()`（`UriHostValidator.java:151`）是同一個陷阱的
不同版本，實作可以互相參照。

**實作現況（`HostMatcher`）**：

- `normalize(host)`：null → `""`；`trim`、**去掉所有尾端 `.`**、
  `IDN.toASCII(h, IDN.ALLOW_UNASSIGNED)`（轉不了就用原字串比，比不中就是不比對——fail closed）、
  `toLowerCase(Locale.ROOT)`。`Locale.ROOT` 是刻意的：土耳其語系的 JVM 會把 `I` 轉成 `ı`，
  讓合法網域比對失敗。
- `matches(candidateHost, scopeHost)`：兩邊先正規化，任一為空 → `false`；**只允許完全相等，
  或 `candidate.endsWith("." + scope)`**。
- **同一個方法被兩個地方共用**：(1) 附加 cookie 前 `matches(請求host, jar host)`；
  (2) 驗證 `Set-Cookie` 的 `Domain=` 屬性 `matches(請求host, domain屬性)`。
- 三個類別（`HostMatcher`、`UriHostValidator`、`OutboundUrlGuard`）各自 private，
  無法直接共用，這裡是照同樣規則**重寫一份**。

⚠️ **這裡有一個與真實瀏覽器不同的語意，是刻意的簡化但要知道**：`matches` 的語意是
「等於**或**為子網域」，**不區分 host-only cookie**。真實瀏覽器只有在 `Set-Cookie` 明確
帶了 `Domain=` 時才把 cookie 送到子網域，沒帶 `Domain=` 的 cookie 是 host-only、只送回
原本那個 host。這裡的 jar 一律以 host 為 scope 並允許子網域，**所以貼給 `example.com`
的 cookie 會被送到 `api.example.com`、`static.example.com` 等所有子網域**——瀏覽器不會
這樣送。見 §7。

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

#### 實作現況：`SiteSessionService` 逐個方法

| 方法 | 交易 | 行為 |
|---|---|---|
| `importCookies(host, cookieHeaderValue)` | `@Transactional` | **唯一的 jar 建立入口**。解析為空就返回；host 先正規化；既有值墊底、新貼的覆蓋。log 只記 host 與數量 |
| `attachCookies(requestHost, headers)` | `@Transactional(readOnly=true)` | headers 已有（大小寫不拘）`Cookie` → **不覆蓋**，原樣回傳；找不到 jar 或 jar 是空的 → 原樣回傳；否則加上 key 為固定字面 `"Cookie"` 的 header |
| `mergeSetCookies(requestHost, values)` | `@Transactional` | 空清單直接返回；**找不到既有 jar 就整批放棄**（jar 只由使用者主動貼上誕生，回應不能無中生有）；逐筆 `parseSetCookie`，`null` 跳過；**`Domain` 比對不過就忽略那一筆**（不是整批）；有變更才寫回並 `log.info(host, cookieCount)` |
| `list()` | `@Transactional(readOnly=true)` | 依 host 排序，回穩定列表 |
| `delete(host)` | `@Transactional` | 正規化後不存在 → `NOT_FOUND`（`No stored login state for host: ...`） |
| `findJarForHost(requestHost)` | private | `repository.findAll()` **全表掃描** + `HostMatcher.matches` 過濾，取 host 字串**最長**的那筆（最具體的登入狀態勝） |

**`CookieCodec` 的解析語意**：

- `parseCookieHeader("a=1; b=2")` → `{a:"1", b:"2"}`：以 `;` 切，每段 `trim`，
  **沒有 `=` 或名稱是空字串就跳過該段**，不讓一段壞掉的內容導致整串解析失敗。
- `serializeCookieHeader` → `"a=1; b=2"`，保留呼叫端傳入的順序。
- `parseSetCookie(raw)` → `(name, value, domain)`：只讀第一段的 `name=value` 與可選的
  `Domain=`（比對不分大小寫，前導 `.` 去掉）；格式不合法回 `null`，呼叫端只忽略這一筆。
- ⚠️ **刻意忽略 `Path`、`Secure`、`SameSite`、`Expires`、`Max-Age`**——類別註解明示
  這不是完整的瀏覽器 cookie jar。後果見 §7。

**呼叫端的容錯**：

- `ApiMonitorRunner` 對 `attachCookies` / `mergeSetCookies` 的例外**只 `log.warn` 不中斷**
  （`ApiMonitorRunner.java:238-242`、`250-254`）——寧可當成「這次沒有登入狀態可用」繼續打，
  也不要讓一筆壞掉的 jar 拖垮整個監控。
- **`ApiMonitorTestRunner` 只附加、絕不合併回寫**：試跑是預覽，不能把排程依賴的 session
  換掉。但它**必須**附加，否則一個對排程來說會成功的監控在試跑時回 401，使用者會誤以為
  設定壞了。
- 抓取回應**不論成功或失敗都嘗試合併 `Set-Cookie`**——401 也可能夾帶新的（或清空的）session。

**過期通知的實際實作**（`ApiMonitorStore`）：

- 判定條件 `isSessionExpiryFailure`：`httpStatus` 是 **401 或 403**，且已知請求 host。
  今天只有 `ApiFetcher` 的 `HTTP_ERROR` 分類會把 401/403 帶進 `httpStatus`（guard 擋下、
  逾時、樣板錯誤都是 `null` 或非 4xx），所以**刻意不額外檢查分類字串**——用狀態碼本身
  判斷已經足夠精確，也不會因為分類碼日後改名而跟著壞掉。
- 門檻沿用一般失敗通知：`monitor.isNotifyOnFailure() && monitor.shouldNotifyFailure(properties.failureNotifyThreshold())`，
  只是把訊息換成登入過期版本：
  `🔒 「{{monitor.name}}」登入已過期，請重新貼上請求（含 Cookie）以恢復監控。（{{now}}）`
- ⚠️ 這裡**刻意重用 `{{monitor.name}}` 欄位帶入 host 文字**——`RenderContext.of` 的該欄位
  本質上只是「要代入的一段文字」，借用它換來與 `{{now}}` 的時區／格式化邏輯共用，
  不必在這個類別裡再刻一份 `DateTimeFormatter`。所以這則訊息的引號裡是 **host**，不是監控名稱。

### 3.4 端點

| 方法 | 路徑 | 用途 |
|---|---|---|
| GET | `/admin/api/sessions` | 列出：host、cookie 名稱、更新時間。**絕不回傳值** |
| DELETE | `/admin/api/sessions/{host}` | 清除該站登入狀態（不可回復） |

**實作現況**：兩個端點都在 `AdminController`（`/sessions`、`/sessions/{host}`）。

`GET /admin/api/sessions` 回 `List<AdminDto.SiteSessionSummary>`：

```
SiteSessionSummary(String host, List<String> cookieNames, int cookieCount,
                   Instant lastRefreshedAt, Instant createdAt, Instant updatedAt)
```

**這個 record 裡沒有任何可以裝 cookie 值的欄位**——不是「呼叫時記得別放值」，而是型別上
根本放不進去。`cookieNames` 由 entity 的明文 `cookie_names` 逗號切開而來，`cookieCount`
就是它的長度。**解密後的 cookie 值只活在 `SiteSessionService` 的方法呼叫堆疊裡，不進任何
DTO、不進任何 log。**

`DELETE /admin/api/sessions/{host}`：host 正規化後不存在 → `NOT_FOUND`。

### 3.5 W6 原本不做的事（後續擴充見 15）

**不存帳號密碼、不做自動登入。** 每個站台的登入流程都不一樣（表單、OAuth、2FA、
驗證碼），做不完也維護不動。貼 cookie + 自動續期已經覆蓋實際需求。

這條決定在實作後仍然成立，且寫進了 `V5__site_session.sql` 的表頭註解。除此之外，
以下也**沒有**做（不是漏掉，是這一波的範圍就到這裡）：

- **不做 cookie 過期／清理**：`CookieCodec` 忽略 `Expires`／`Max-Age`，`ApiMonitorSweeper`
  也不掃 `site_session`。失效的 cookie 只能靠使用者手動 `DELETE /sessions/{host}` 或重貼。見 §7
- **不做 host-only cookie 語意**：見 §3.2 末段與 §7

---

## 4. W7 — 視覺化

**狀態：已實作**，全部在 `server/src/main/resources/static/admin/index.html` 與
`server/src/main/resources/static/admin/assets/app.js`。

### 4.1 匯入

貼上框（標明「要帶登入狀態請用 Copy as cURL」）→ 呼叫 `/monitors/import` →
自動填入編輯抽屜的各欄位。旁邊放自訂 JSON schema 的一鍵複製。

**實作現況**：`#monImportRaw`（`textarea rows=5`）→ `importMonitorRaw()` →
`POST /monitors/import` → `applyImportedRequest()` 套進 url／method／body／headers 四個欄位。
解析成功後**立即清空貼上框**（內容含 cookie／token，不留在 DOM）。旁邊的按鈕複製
`MONITOR_IMPORT_SCHEMA` 常數。

⚠️ 只有請求四欄會被自動填入——自訂 JSON 裡的 `name`／`intervalSeconds`／`compare`／
`messageTemplate` **不會**自動套用，見 §2.4。

### 4.2 欄位選取器（取代手打 JsonPointer）

按「立即測試」抓一次 → 把回應 JSON 渲染成可展開的樹 → **點任一節點就把它的
JsonPointer 插入解析規則**，並自動用最後一段路徑當欄位名（可改）。

`NEW_ITEMS` 模式下，點陣列節點 = 設 `itemPointer`，點陣列元素內的欄位 =
設 `itemKeyPointer`（自動轉成相對路徑）。

**實作現況**：`fieldPickerTree()` 從試跑回應的完整 `body` 建 `<details>` 樹；
`appendPickButtons()` 依模式給不同按鈕——非 `NEW_ITEMS` 只有「插入」；`NEW_ITEMS` 有
「設為項目陣列」（陣列節點）／「設為項目鍵」／「插入為項目欄位」。`sanitizeRuleName()`
把 pointer 末段轉成 `[A-Za-z0-9_]{1,32}`。已設定的節點會顯示「項目陣列」／「項目鍵」tag。

**後端配合**（`POST /admin/api/monitors/test` → `ApiMonitorTestRunner`）：

- 回應帶目標 API 的**原始回應內容**（`MonitorTestOutcome.Success.body`），這是對
  「持久化路徑不得回顯回應內容」規則的**刻意放寬**——不落地、不進 log、端點帶
  `Cache-Control: no-store`。沒有它就畫不出樹。
- body 上限 **256 KB**（UTF-8 位元組），超過就截斷並回 `bodyTruncated` / `bodyOriginalLength`。
  截斷點可能切在多位元組字元中間（會變 U+FFFD，不拋例外）。
  **截斷只影響回給瀏覽器的那份 body**；`values` / `items` / `renderedMessage` 一律用完整 body 算。
- ⚠️ `NEW_ITEMS` 模式下 `itemPointer` 或 `itemKeyPointer` 空白時，**刻意回空的項目預覽但仍帶
  body**——不然使用者要先填 pointer 才能看到樹、要先看到樹才知道 pointer 怎麼填，picker
  流程會卡死。
- 試跑**絕不送出通知、絕不寫入任何狀態**：`ApiMonitorTestRunner` 連 `NotificationService`
  和 `ApiMonitorStore` 都沒有注入，不是「不呼叫」而是連可以呼叫的依賴都不存在。

### 4.3 變動欄位偵測

「再抓一次」按鈕：間隔數秒抓第二次，diff 兩份回應，**把值有變的欄位標黃**。
直接回答「我該監控哪個欄位」——這是整個功能最花時間的一步。

注意時間戳類欄位每次都會變，要能讓使用者一眼看出「這個一直在變、不適合當監控目標」。

**實作現況**：`#monRetestBtn`「再抓一次比對」。**兩層 diff**：`computeTestDiff()`（抽取值／
項目層級）＋ `computeBodyDiff()`（整棵 body 攤平成 `JsonPointer → 值`）。`bumpVolatility()`
累計次數，`isAlwaysChanging()` 的判定是 `total >= 2 && changed === total`。標記分兩級：
`有變動`（`tag--warning`）／`每次都變·不建議監控`（`tag--danger`）。

⚠️ `rawLeafText()` 用**完整值**比對、`formatLeafValue()` 才截斷顯示——**刻意分開**，
用截斷後的字串比對會讓「只有尾端不同」的長值被誤判為沒變。

### 4.4 登入狀態頁

列出各 host、cookie 名稱與數量、最後更新時間、清除鈕。

**實作現況**：`#sessions` view，6 欄表格（Host／Cookie 名稱／數量／最後更新／建立時間／操作），
資料來自 `GET /sessions` 的 `SiteSessionSummary`（§3.4，不含值）；清除鈕呼叫
`DELETE /sessions/{host}`。空狀態文案：「還沒有任何站台登入狀態。匯入含 cookie 的請求時會自動建立。」

### 4.5 前端約束

沿用既有慣例：全部 DOM 寫入走 `textContent` 不用 `innerHTML`；`$()`/`el()` 輔助函式；
CSRF 由既有 `call()` 包裝處理；新的 view 要註冊進 `VIEWS` map。

**樹狀檢視渲染的是第三方 API 的回應內容 —— 這正是 `innerHTML` 會變成 XSS 的地方。**

**實作現況**：這些約束都遵守了，另外還多做了幾件設計裡沒寫的：

- **`{{` 自動完成**（`wireAutocomplete`）掛在 5 類欄位：`#monTemplate`（訊息模板變數）、
  `#monUrl` / `#monHeaders` / `#monBody`（請求模板變數）、每個計算欄位的輸入框。
  **三組變數集合彼此獨立且現算不快取**——這正是 §2.5 那個「兩套集合行為相反」問題的解法。
- **完整變數對照表**（`index.html` 的 `<details class="var-ref">`）分 3 節列出訊息模板／
  請求模板／計算欄位輸入各自可用的變數，並明確標出兩者行為相反：訊息模板打錯 → `—`；
  請求模板打錯 → **存檔時 400**。
- **訊息模板預設值**：`defaultMessageTemplate(fieldName)` 的欄位名**動態取自使用者自己的
  抽取規則**，不寫死；用 `templatePristine` 旗標而非「內容等於預設字串」判定，使用者一動
  就永久停止自動覆寫；**編輯既有監控時 `templatePristine = false`，一律不套用預設**。

---

## 5. 測試要求

以下要求全部仍然有效。已落地的自動化測試位置：

```
monitor/importer/  ShellTokenizerTest、CurlParserTest、FetchParserTest、
                   MonitorBundleParserTest、RequestImporterTest
monitor/request/   RequestTemplateTest
monitor/session/   CookieCodecTest、HostMatcherTest、SiteSessionServiceTest
```

W7 沒有自動化測試，仍然是下面那條手動流程。

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

| 波次 | 範圍 | 相依 | 狀態 |
|---|---|---|---|
| W5 | §2 匯入解析 + 請求模板 | — | **已完成** |
| W6 | §3 站台登入狀態 | W5（匯入要能抽 cookie） | **已完成** |
| W7 | §4 前端 | W5, W6 | **已完成** |

每波結束都要 `mvn -q -DskipTests=false verify` 全綠，且以 XML 的
`<testcase>` 數確認測試真的有跑（`@Nested` 會讓 `.txt` 統計誤報為 0）。

---

## 7. 已知缺口（實作後補記）

以下**不是待辦清單**，是已經上線的行為裡刻意留下或尚未處理的取捨。列在這裡是為了讓
下一個接手的人不必再從程式碼裡重新發現一次。

### 7.1 cookie 生命週期：jar 內的 cookie 永不過期、永不清理

`CookieCodec` **刻意忽略 `Path`、`Secure`、`SameSite`、`Expires`、`Max-Age`**
（`CookieCodec.java:8-12` 的類別註解明示這不是完整的瀏覽器 cookie jar）。直接後果：

- **jar 裡的 cookie 永遠不會過期。** 站台送 `Set-Cookie: session=; Max-Age=0` 想清掉
  session 時，這裡只會把 `session` 的值更新成空字串，不會刪掉這筆。
- **`ApiMonitorSweeper` 也不掃 `site_session`。** 它只清 `api_monitor_run` 與
  `api_monitor_seen_item` 兩張表，`site_session` 完全不在保留期清理的範圍內。
- 所以**失效的登入狀態只能靠使用者手動處理**：`DELETE /admin/api/sessions/{host}` 或
  重貼一次含新 cookie 的請求。§3.3 的「連續 401/403 → 發過期通知」就是為了讓使用者
  知道該去做這件事——**通知是唯一的清理觸發機制，系統自己不會清**。
- `Secure` 被忽略在這裡影響有限（`OutboundUrlGuard` 本來就不允許 http），
  但 `Path` 被忽略代表**同一個 host 底下所有路徑都會收到全部 cookie**。

### 7.2 host 比對：jar 會被送到所有子網域

`HostMatcher.matches` 的語意是「**等於或為子網域**」，**不區分 host-only cookie**。

真實瀏覽器的規則是：`Set-Cookie` 沒帶 `Domain=` 的 cookie 是 **host-only**，只會送回
原本那個 host；帶了 `Domain=example.com` 才會送到子網域。這裡的 jar 一律以 host 為 scope
並允許子網域，所以：

> 貼給 `example.com` 的 cookie，會被送到 `api.example.com`、`static.example.com`、
> `cdn.example.com`……**所有**子網域。**瀏覽器不會這樣送。**

這比瀏覽器**寬鬆**，方向是「多送」而不是「少送」——如果目標站台有子網域屬於不同信任層級
（例如使用者上傳內容的子網域），session token 會被送過去。設計上接受這個取捨的理由是
「同一個註冊網域下的子網域視為同一站台」，但這個假設**不是普遍成立的**，所以記在這裡。

比對本身沒有 §3.2 警告的那個漏洞（`evil-example.com` 拿不到 `example.com` 的 cookie），
這一條講的是**另一個方向**的過寬。

### 7.3 `findJarForHost` 全表掃描

`SiteSessionService.findJarForHost()`（`SiteSessionService.java:215-222`）用
`repository.findAll()` 全表掃描再在記憶體過濾。**每次抓取前後各一次**（`attachCookies`
＋ `mergeSetCookies`）。規模假設是「使用者貼過幾個站的登入狀態」，對單人／小團隊系統
不成問題；`SiteSessionRepository` 的類別註解也明說了這件事。站台數量成長時這裡要改。

### 7.4 `RequestTemplate` 的 ad-hoc session 沒有型別阻擋

`RequestTemplate.render(String)` / `renderHeaders(Map)` 這兩個無參數版本**每次呼叫各自
建立一個新的一次性 session**，只適合語法驗證。用在真要送出的請求上，會讓計算欄位的簽章
與 `timestamp` header 對不上（§2.5）。類別註解有警告，但**型別上沒有任何阻擋**——
新的呼叫端很容易挑到錯的多載。

### 7.5 doc drift：`ImportedRequest` 的 javadoc 已過時

⚠️ **`ImportedRequest.java:22-24` 的 `@param headers` 註解仍然寫著**：

> 「含 `cookie`——W5 尚未實作 site_session cookie jar，cookie 目前當成一般 header 處理，
> W6 才會把它移出去」

**這段已經不成立。** `AdminService.importMonitorRequest`（`AdminService.java` 的匯入流程
第 5 步）**早就會**把 cookie 抽出來寫進 `SiteSessionService`，並從回傳的 header 裡移除。

**最終語意以本文件 §2.6 第 5 點為準**：`POST /admin/api/monitors/import` 回傳的
`ImportedRequest.headers()` 裡**不會**有 `cookie`（cookie 已被抽進該 host 的 jar）。
該 javadoc 需要同步修正——同一段註解裡指向本文件 §0 的連結也一併過時，因為 §0 講的是
「Copy as fetch 不含 cookie」，不是 cookie 的存放位置決策。

## 8. 現行操作邊界

1. 貼上請求會解析設定；含 cookie 時，匯入**立即寫入**對應 host 的 cookie jar。取消建立監控不會撤回已匯入 cookie。
2. 立即測試可能送出有副作用的 POST。它不發 LINE 通知、不寫監控基準，但可更新登入快取或記錄登入失敗；不能視為完全唯讀。
3. 自訂 headers 可透過受 session 保護且 no-store 的 `/monitors/{id}/headers` 讀回；cookie jar 值與 monitor_secret 不提供讀回。
4. Cognito 的 loginId 應在表單「站台登入」選擇，token 自動注入，不貼進 URL、body 或訊息模板。
5. EXTRACTED 缺失／null 不再當成變更；立即測試會報 PARSE_ERROR，正式輪詢保留有效基準。
6. cURL／fetch 匯出的內容可能含登入機密，勿提交範例真值；匯入結果 URL／body 並不會被加密。
