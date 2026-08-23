# 11 — API 監控輪詢設計

> 定時打使用者設定的 API，比對回應，**有變更才發通知**。
>
> 相關：[07-非同步與可靠性設計](07-非同步與可靠性設計.md)（outbox 與租約）、
> [03-權限與認證設計](03-權限與認證設計.md)（secret 加密）、
> [06-LINE整合設計](06-LINE整合設計.md)（月配額限制）。

---

## 1. 目標與非目標

**目標**

- 後台建立「監控項目」：目標 API、間隔秒數（使用者手動配置）
- 伺服器定時抓取、解析 JSON、與上次比對
- 只有偵測到變更才發通知，訊息內容由模板組成
- 三種比對模式：整包 / 指定欄位 / 只通知新項目

**非目標**

- 不做 GraphQL、SOAP、非 JSON 回應解析（v1 只吃 JSON）
- 不做 webhook 反向接收（那是另一個功能）
- 不做跨監控的關聯運算

---

## 2. 決策摘要

| 項目 | 決定 | 理由 |
|---|---|---|
| 發送路徑 | 復用 `NotificationService.submit()` | 免費拿到 scope 檢查、預設對象、每日配額、連結白名單、切批、重試。不開第二條發送路徑 |
| 收件人 | 每個監控綁一組 client | 誰收、配額多少全部沿用 client 既有設定 |
| 取件 | `next_run_at <= now()` + `FOR UPDATE SKIP LOCKED` | 抄 `DeliveryStore.claim()`。重啟不掉單、多實例不重複打 |
| 租約 | 取件時把 `next_run_at` 推到 `now + lease` | 沿用 outbox 同款技巧，不另加 lease 欄位 |
| 交易邊界 | 取件交易 → **放掉** → 打 API → 回寫交易 | ADR-0007 的教訓：交易跨外部 API 會把 Hikari 連線池佔滿 |
| 解析語法 | Jackson `tools.jackson.core.JsonPointer`（RFC 6901） | 零新依賴。設計上留擴充點，日後可加 JSONPath |
| SSRF 防護 | IP 層防護**強制**、host 白名單**選配** | 見 §5。IP 層零摩擦且擋掉致命的那幾條；白名單對單人情境多是自找麻煩 |
| 認證 header | `SecretCipher` 加密存（AES-256-GCM，AAD = `monitor:{id}`） | 與 client secret 同一套金鑰管理，不新增機制 |

---

## 3. 資料模型（`V4__api_monitor.sql`）

### 3.1 `api_monitor`

```sql
CREATE TABLE api_monitor (
    id                        BIGSERIAL    PRIMARY KEY,
    name                      VARCHAR(100) NOT NULL,
    client_id                 BIGINT       NOT NULL REFERENCES client (id),

    -- 目標
    url                       TEXT         NOT NULL,
    method                    VARCHAR(8)   NOT NULL DEFAULT 'GET',
    request_body              TEXT,
    headers_ciphertext        BYTEA,
    headers_iv                BYTEA,
    headers_key_version       INT,

    -- 排程
    interval_seconds          INT          NOT NULL,
    enabled                   BOOLEAN      NOT NULL DEFAULT true,

    -- 解析與比對
    compare_mode              VARCHAR(16)  NOT NULL,
    extract_rules             JSONB        NOT NULL DEFAULT '[]',
    item_pointer              TEXT,
    item_key_pointer          TEXT,
    message_template          TEXT         NOT NULL,

    -- 防洗版
    notify_on_failure         BOOLEAN      NOT NULL DEFAULT true,
    cooldown_seconds          INT          NOT NULL DEFAULT 0,
    max_notifications_per_day INT,

    -- 執行狀態
    next_run_at               TIMESTAMPTZ  NOT NULL,
    last_run_at               TIMESTAMPTZ,
    last_notified_at          TIMESTAMPTZ,
    last_fingerprint          BYTEA,
    last_state                JSONB,
    consecutive_failures      INT          NOT NULL DEFAULT 0,
    failure_notified          BOOLEAN      NOT NULL DEFAULT false,
    notified_count            INT          NOT NULL DEFAULT 0,
    notified_day              DATE,

    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT api_monitor_method_chk   CHECK (method IN ('GET', 'POST')),
    CONSTRAINT api_monitor_mode_chk     CHECK (compare_mode IN ('WHOLE_BODY', 'EXTRACTED', 'NEW_ITEMS')),
    CONSTRAINT api_monitor_interval_chk CHECK (interval_seconds >= 30),
    -- NEW_ITEMS 一定要有陣列位置與鍵位置，否則無從判斷「新」
    CONSTRAINT api_monitor_newitems_chk CHECK (
        compare_mode <> 'NEW_ITEMS' OR (item_pointer IS NOT NULL AND item_key_pointer IS NOT NULL)
    )
);

-- 取件的唯一查詢路徑
CREATE INDEX idx_api_monitor_due ON api_monitor (next_run_at) WHERE enabled;
```

`extract_rules` 格式：`[{"name": "status", "pointer": "/data/0/status"}, ...]`
`name` 限 `[A-Za-z0-9_]{1,32}`，模板用 `{{value.status}}` 引用。

`last_state` 格式：`{"status": "OK", "count": "12"}` —— 取出的值一律轉字串存，
模板不做型別運算，避免數字精度與 locale 問題。

### 3.2 `api_monitor_run`

```sql
CREATE TABLE api_monitor_run (
    id              BIGSERIAL   PRIMARY KEY,
    monitor_id      BIGINT      NOT NULL REFERENCES api_monitor (id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ NOT NULL,
    duration_ms     INT,
    outcome         VARCHAR(16) NOT NULL,
    http_status     INT,
    error_message   TEXT,
    notification_id UUID,
    CONSTRAINT api_monitor_run_outcome_chk
        CHECK (outcome IN ('CHANGED', 'UNCHANGED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_api_monitor_run_recent ON api_monitor_run (monitor_id, started_at DESC);
```

`error_message` 不得寫入回應內容全文，只寫分類與長度。回應可能含目標 API 的
機敏資料，後台紀錄不是存它的地方。

### 3.3 `api_monitor_seen_item`（`NEW_ITEMS` 模式專用）

```sql
CREATE TABLE api_monitor_seen_item (
    monitor_id    BIGINT       NOT NULL REFERENCES api_monitor (id) ON DELETE CASCADE,
    item_key      VARCHAR(200) NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (monitor_id, item_key)
);

CREATE INDEX idx_api_monitor_seen_age ON api_monitor_seen_item (first_seen_at);
```

`item_key` 超過 200 字元時取 `SHA-256` 十六進位字串，不截斷 —— 截斷會讓兩個
不同項目撞成同一個 key，變成「新項目被當成看過的」而永遠不通知。

---

## 4. 套件結構

```
com.jason.notifyline.monitor
├── domain/
│   ├── ApiMonitor.java              @Entity
│   ├── ApiMonitorRepository.java     含 lockDue()
│   ├── ApiMonitorRun.java           @Entity
│   ├── ApiMonitorRunRepository.java
│   ├── SeenItem.java / SeenItemId.java / SeenItemRepository.java
│   ├── CompareMode.java             enum
│   ├── RunOutcome.java              enum
│   └── ExtractRule.java             record(name, pointer)
├── fetch/
│   ├── OutboundUrlGuard.java        ★ 安全關鍵
│   ├── ApiFetcher.java
│   └── FetchResult.java             sealed: Success | Failure
├── parse/
│   ├── JsonExtractor.java
│   ├── ChangeDetector.java
│   ├── ChangeResult.java            sealed: Changed | Unchanged
│   └── MessageTemplate.java
├── ApiMonitorStore.java             交易邊界：claim / recordSuccess / recordFailure
├── ApiMonitorRunner.java            編排：claim → fetch → parse → compare → notify
├── ApiMonitorScheduler.java         @Scheduled
├── ApiMonitorSweeper.java           保留期清理（run / seen_item）
└── MonitorProperties.java           @ConfigurationProperties("app.monitor")
```

JPA 慣例照既有：JSONB 用 `@JdbcTypeCode(SqlTypes.JSON)` + `String` 欄位
（見 `Notification.payload`），enum 用 `@Enumerated(EnumType.STRING)`。

---

## 5. SSRF 防護規格 ★ 安全關鍵

> 這個功能會把抓回來的內容渲染進 LINE 訊息並存進執行紀錄。
> 它不是盲 SSRF，是**附帶完整回傳通道的任意網址讀取器**。
> 「網址是管理員自己設的」描述的是今天的狀態，不是控制措施 ——
> 控制要在後台被攻破之後仍然成立。

### 5.1 強制（無開關，`OutboundUrlGuard`）

1. **只允許 `https`**。其他 scheme 一律拒絕。
2. **不跟隨 redirect**。`HttpClient.Redirect.NEVER`；3xx 視為失敗。
   跟隨 redirect 等於讓對方一跳就繞過所有主機檢查。
3. **解析 DNS，檢查每一個回傳的位址**。任何一個落在下列範圍就整個拒絕
   （round-robin DNS 可以只讓其中一筆指向內網）。
4. **IPv4-mapped / NAT64 位址必須先還原成 IPv4 再檢查**
   （`::ffff:10.0.0.1` 是內網位址）。

封鎖範圍：

| 版本 | 範圍 |
|---|---|
| IPv4 | `0.0.0.0/8`、`10.0.0.0/8`、`100.64.0.0/10`(CGNAT)、`127.0.0.0/8`、`169.254.0.0/16`、`172.16.0.0/12`、`192.0.0.0/24`、`192.0.2.0/24`、`192.168.0.0/16`、`198.18.0.0/15`、`198.51.100.0/24`、`203.0.113.0/24`、`224.0.0.0/4`、`240.0.0.0/4`、`255.255.255.255` |
| IPv6 | `::`、`::1`、`fc00::/7`(ULA)、`fe80::/10`(link-local)、`ff00::/8`(multicast)、`2001:db8::/32`、`::ffff:0:0/96`→還原後重驗、`64:ff9b::/96`→還原後重驗 |

`169.254.0.0/16` 是**最重要的一條** —— 雲端 metadata endpoint
（`169.254.169.254`）會吐出 instance 的 IAM 憑證。

> **不可只依賴** `InetAddress.isSiteLocalAddress()` 等內建方法：它們不涵蓋
> CGNAT `100.64/10`、`192.0.0/24`，也不處理 IPv4-mapped 還原。要顯式比對範圍。

5. **逾時**：connect 5s、read 10s（可設定）。
6. **回應大小上限 1 MB**，且必須**邊讀邊擋**，不可整包讀完再判斷。
7. **Content-Type 必須是 JSON**（`application/json` 或 `+json`）。

### 5.2 選配（預設關）

`app.monitor.allowed-hosts` —— 空值代表**允許任何公開網域**（仍受 §5.1 全部約束）。
非空時只有清單內的網域可打，比對規則沿用 `UriHostValidator.isAllowed()`
（完全相等或帶點號邊界，不可用 `endsWith`）。

### 5.3 已知殘留風險

**DNS rebinding**：檢查通過後、實際連線前 DNS 可能改指內網（TOCTOU）。
完整解需要 pin 住已驗證 IP 的 socket factory。v1 不做，理由是 §5.1 已擋掉
絕大多數實際可利用的路徑，而自訂 socket factory 會顯著增加複雜度。
**此處刻意留下，不是遺漏。**

---

## 6. 解析、比對、組內容

### 6.1 `JsonExtractor`

以 `tools.jackson.core.JsonPointer` 取值。取不到 → 該欄位值為 `null`。
取到容器（物件/陣列）→ 序列化成緊湊 JSON 字串。取到純量 → `asString()`。

### 6.2 `ChangeDetector`

| 模式 | 比對基準 | 指紋 |
|---|---|---|
| `WHOLE_BODY` | 整個回應 body | `SHA-256(body)` |
| `EXTRACTED` | 所有 `extract_rules` 取出的值 | `SHA-256(canonical(name=value 依 name 排序))` |
| `NEW_ITEMS` | 不用指紋，比對 `seen_item` 表 | — |

**首次執行只記錄不通知。** `last_fingerprint IS NULL` 時寫入基準、回 `UNCHANGED`。
否則建一個監控就先被通知一次，全部都是雜訊。

`NEW_ITEMS` 流程：
1. `item_pointer` 取出陣列（不是陣列 → 失敗）
2. 每個元素用 `item_key_pointer`（相對於元素）取鍵值
3. 與 `seen_item` 比對，找出沒看過的
4. **首次執行**：全部寫入 `seen_item`，不通知
5. 之後：新項目寫入並通知；一則訊息最多列 20 筆，其餘寫「還有 N 筆」

### 6.3 `MessageTemplate`

佔位符：

| 佔位符 | 內容 |
|---|---|
| `{{value.NAME}}` | 本次取出的值 |
| `{{old.NAME}}` | 上次的值（`last_state`） |
| `{{item.NAME}}` | `NEW_ITEMS` 模式，逐項渲染時該項目的欄位 |
| `{{monitor.name}}` | 監控名稱 |
| `{{now}}` | 現在時間（`Asia/Taipei`，`MM/dd HH:mm`） |

規則：
- 未知佔位符 → 替換成 `—`，並記一筆 WARN。**不可原樣輸出** `{{...}}`，
  那會讓使用者以為模板沒生效而反覆重設
- 值為 `null` → `—`
- 剝除控制字元（` `–``，保留 `\n`）
- 渲染後截到 **4500 字**（LINE 上限 5000，留餘裕給前後綴）

> **內容注入**：目標 API 回傳的內容會進入 LINE 訊息。走
> `NotificationService.submit()` 就會經過 `UriHostValidator`，
> 第三方 API 回傳的釣魚連結會被擋下。**因此絕不可繞過 `submit()` 直接發送。**

---

## 7. 執行流程與交易邊界

```
ApiMonitorScheduler (@Scheduled fixedDelay = app.monitor.poll-interval)
  └─ ApiMonitorRunner.runOnce()
       ├─ [交易 1] store.claim(limit)
       │     SELECT ... WHERE enabled AND next_run_at <= now()
       │     ORDER BY next_run_at FOR UPDATE SKIP LOCKED
       │     → next_run_at = now + lease（租約，防重複取件）
       │     → 回傳解密後的設定快照（DTO，不是 entity）
       │  ── 交易結束，連線歸還 ──
       │
       ├─ 對每個監控（無交易）：
       │     guard.check(url) → fetcher.fetch(...) → extractor → detector
       │
       └─ [交易 2] store.recordOutcome(...)
             更新 fingerprint / last_state / seen_item / 計數器
             next_run_at = now + interval（或退避）
             寫 api_monitor_run
             有變更且通過防洗版 → notificationService.submit(...)
```

**`@Scheduled` 方法必須吞掉所有例外** —— 拋出去會讓 Spring 停掉後續排程，
等於整個監控靜悄悄永久停擺。照 `DeliveryScheduler.poll()` 的寫法。

**獨立執行緒池**，不共用派送的。一個慢掉的第三方 API 不該拖慢 LINE 發送。

---

## 8. 防洗版（LINE 免費額度 200 則/月，這是硬需求）

| 機制 | 規則 |
|---|---|
| 間隔下限 | `interval_seconds >= 30`（DB 約束）且 `>= app.monitor.min-interval`（預設 60s，服務層檢查） |
| 冷卻 | `last_notified_at + cooldown_seconds > now` → 跳過通知，**且不更新 fingerprint**，冷卻結束後仍會通知。紀錄 `SKIPPED` |
| 每日上限 | `notified_day` 與今天不同 → 歸零 `notified_count`。達 `max_notifications_per_day` → `SKIPPED`（同樣不更新 fingerprint） |
| 失敗退避 | `next_run_at = now + interval × min(2^(failures-1), 16)` |
| 失敗只報一次 | `consecutive_failures >= failure-notify-threshold`（預設 3）且 `failure_notified = false` → 發一則，設 `failure_notified = true`。成功時歸零；若曾通知過失敗則補發一則恢復通知 |
| client 每日配額 | `submit()` 內既有的 `enforceDailyQuota` 自動生效，不用另寫 |

---

## 9. 設定（`application.yml`）

```yaml
app:
  monitor:
    enabled:                  ${APP_MONITOR_ENABLED:true}
    poll-interval:            ${APP_MONITOR_POLL_INTERVAL:PT10S}
    claim-limit:              ${APP_MONITOR_CLAIM_LIMIT:5}
    lease:                    ${APP_MONITOR_LEASE:PT2M}
    min-interval:             ${APP_MONITOR_MIN_INTERVAL:PT60S}
    connect-timeout:          ${APP_MONITOR_CONNECT_TIMEOUT:PT5S}
    read-timeout:             ${APP_MONITOR_READ_TIMEOUT:PT10S}
    max-body-bytes:           ${APP_MONITOR_MAX_BODY_BYTES:1048576}
    # 空 = 允許任何公開網域（仍受 IP 層防護）。填了就只有清單內可打。
    allowed-hosts:            ${APP_MONITOR_ALLOWED_HOSTS:}
    failure-notify-threshold: ${APP_MONITOR_FAILURE_NOTIFY_THRESHOLD:3}
    run-retention:            ${APP_MONITOR_RUN_RETENTION:P14D}
    seen-item-retention:      ${APP_MONITOR_SEEN_ITEM_RETENTION:P90D}
```

`app.monitor.enabled=false` 時 `ApiMonitorScheduler` 不註冊
（`@ConditionalOnProperty`，照 `DeliveryScheduler` 的寫法）。

---

## 10. 後台 API

全部掛在既有的 `/admin/api`（session 認證 + CSRF），加進 `AdminController`。

| 方法 | 路徑 | 用途 |
|---|---|---|
| GET | `/monitors` | 列表（含執行狀態摘要） |
| POST | `/monitors` | 建立 |
| PUT | `/monitors/{id}` | 更新 |
| DELETE | `/monitors/{id}` | 刪除 |
| POST | `/monitors/{id}/enabled` | 啟用／停用 |
| POST | `/monitors/test` | **試跑**：body 帶完整設定（未存檔也可），抓一次、回傳取到的值與渲染後訊息，**不發送、不寫狀態** |
| GET | `/monitors/{id}/runs` | 最近 50 筆執行紀錄 |

回傳的 DTO **絕不可包含** `headers_ciphertext` 或解密後的 header 值。
編輯時 header 欄位留空 = 不變更，有填 = 覆寫。

試跑一樣要過 `OutboundUrlGuard` —— 否則它就是一個繞過所有防護的 SSRF 入口。

---

## 11. 前端

`index.html` / `app.js` / `app.css` 加「監控」頁：

- nav 新增一項（照現有 `.nav a` + `.nav__ico` 結構，`data-view="monitors"`）
- 列表：名稱、目標 host、間隔、模式、狀態（正常／失敗 N 次／停用）、最後執行、操作
- 編輯抽屜（沿用現有 `<dialog>` 抽屜樣式）
- **「立即測試」是關鍵 UX**：顯示抓到的值與算出來的訊息，不發送。
  沒有這個，設 JsonPointer 等於盲猜
- 全部 DOM 寫入走 `textContent`，不用 `innerHTML`（照 `app.js` 既有慣例）

---

## 12. 測試矩陣

**Unit（必要）**

| 對象 | 案例 |
|---|---|
| `OutboundUrlGuard` | §5.1 每個封鎖範圍各一；IPv4-mapped `::ffff:10.0.0.1`；多筆 A 記錄其一為內網；http 拒絕；白名單 `evil-example.com` vs `example.com`；IDN 同形字 |
| `JsonExtractor` | 純量／物件／陣列／取不到／深層巢狀 |
| `ChangeDetector` | 三種模式 × (首次／無變更／有變更)；`EXTRACTED` 欄位順序不影響指紋 |
| `MessageTemplate` | 各佔位符；未知佔位符；null 值；控制字元；超長截斷 |
| 防洗版 | 冷卻中不更新 fingerprint；每日上限歸零；退避倍數；失敗只通知一次 |

**Integration（Testcontainers + MockWebServer）**

- 目標 API 打樁：正常 JSON、非 JSON content-type、超大 body、逾時、3xx、5xx
- `claim()` 併發：兩個工作者不會取到同一筆
- 端到端：值變更 → 產生 `notification` 列且 `api_monitor_run.outcome = CHANGED`
- 首次執行不通知
- 試跑端點不會寫入任何狀態

**覆蓋率** 維持 80%+（JaCoCo 既有門檻）。

---

## 13. 實作分工

| 波次 | 範圍 | 相依 |
|---|---|---|
| W1 | §3 migration、§4 domain、§9 設定 | — |
| W2a | §5 `OutboundUrlGuard` + `ApiFetcher` | W1 |
| W2b | §6 `JsonExtractor` / `ChangeDetector` / `MessageTemplate` | W1 |
| W3 | §7 `ApiMonitorStore` / `Runner` / `Scheduler` / `Sweeper`、§8 防洗版 | W1, W2a, W2b |
| W4 | §10 後台 API、§11 前端 | W3 |

W2a 與 W2b 檔案不重疊，可並行。

每一波結束都要 `mvn -q -DskipTests=false verify` 全綠才算完成。
