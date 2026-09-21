# 11 — API 監控輪詢設計

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

> 定時打使用者設定的 API，比對回應，**有變更才發通知**。
>
> 相關：[07-非同步與可靠性設計](07-非同步與可靠性設計.md)（outbox 與租約）、
> [03-權限與認證設計](03-權限與認證設計.md)（secret 加密）、
> [06-LINE整合設計](06-LINE整合設計.md)（月配額限制）、
> [12-API監控易用性升級](12-API監控易用性升級.md)（匯入、樣板、站台登入狀態）、
> [13-監控計算欄位設計](13-監控計算欄位設計.md)（`computed_fields` 計算欄位）。
>
> 本文描述的是**已實作**的行為；當初規劃但沒做的項目一律標 ⏳，
> 已知缺口集中在 §14。

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
| 解析語法 | Jackson 3 `tools.jackson.core.JsonPointer`（RFC 6901） | 零新依賴。完全委派 `JsonPointer.compile()`，**不自訂語法**；JSONPath 是留下的擴充點，⏳ 未實作 |
| SSRF 防護 | IP 層防護**強制**、host 白名單**選配** | 見 §5。IP 層零摩擦且擋掉致命的那幾條；白名單對單人情境多是自找麻煩 |
| 認證 header | `SecretCipher` 加密存（AES-256-GCM，AAD = `monitor:{id}`） | 與 client secret 同一套金鑰管理，不新增機制 |
| 併發 | claim 到的每筆各開一個 `CompletableFuture.runAsync`，跑在專屬 `monitorTaskExecutor` | 一筆卡住不拖累同一輪的其他筆；池有界（core 2 / max 4 / queue 50）＋ `CallerRunsPolicy` 形成背壓 |
| 請求樣板 | 同一 `RequestTemplate.Session` 先求值 `ComputedFieldEvaluator`，再替換 URL／header／body | DB 存原始樣板文字，簽章與時間戳要在送出當下才算得出來。細節見 [13-監控計算欄位設計](13-監控計算欄位設計.md) |
| 站台 cookie | 抓取前 `SiteSessionService.attachCookies()`、抓取後 `mergeSetCookies()` | 需要登入的目標不必每次手動重貼 cookie。細節見 [12-API監控易用性升級](12-API監控易用性升級.md) |

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

`last_state` 格式：`{"status": "OK", "count": "12"}`；WHOLE_BODY 的抽取值可含 null。有效的 EXTRACTED 基準只含非 null 字串，
模板不做型別運算，避免數字精度與 locale 問題。

`url` / `request_body` 與 header 值存的都是**原始樣板文字**（可以含 `{{ }}`）。
替換在送出前一刻才做，SSRF 檢查跑在替換**之後**（§5、§7）。因此 entity 不對 `url`
做任何驗證 —— 帶 `{{ }}` 的字串本來就過不了 `URI.create()`。

**V6 新增 `computed_fields`**（`V6__monitor_computed.sql`）：

```sql
ALTER TABLE api_monitor ADD COLUMN computed_fields JSONB NOT NULL DEFAULT '[]';
```

存「送出請求前依序求值的計算欄位」（HMAC 簽章、時間戳等），
由 `ComputedFieldEvaluator` 求值、`ComputedFieldValidator` 在存檔時驗證。
語法與求值規則不在這裡展開，見 [13-監控計算欄位設計](13-監控計算欄位設計.md)。

entity 端的驗證只有三條（`ApiMonitor.java`）：`method` 轉大寫後限 `GET`/`POST`、
`interval_seconds >= 30`、`NEW_ITEMS` 必須有 `item_pointer` 與 `item_key_pointer`。
`extract_rules` / `computed_fields` 的內容驗證都在 `AdminService` 那一層。

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
機敏資料，後台紀錄不是存它的地方。實作格式為 `classification` 或
`classification: detail`（例如 `TIMEOUT: request timed out`、`PARSE_ERROR: body length=8192`），
寫入前截到 **500 字**（`ApiMonitorStore.MAX_ERROR_MESSAGE_LENGTH`）。

`notification_id` 只有一欄，但一次成功執行最多可能發兩則通知（變更通知＋故障恢復通知）。
語意是：**變更通知優先**，只有沒發變更通知時才記恢復通知的 id。

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

`item_key` 超過 200 字元時取 `SHA-256` 十六進位字串（64 字元小寫 hex），不截斷 ——
截斷會讓兩個不同項目撞成同一個 key，變成「新項目被當成看過的」而永遠不通知。
實作在 `ChangeDetector.normalizeItemKey()`。

entity 是 `SeenItem` + `@EmbeddedId record SeenItemId(monitorId, itemKey)`
（Hibernate 6.2+ 原生支援 record embeddable）。保留期清理見 §9 的
`seen-item-retention`（預設 90 天），由 `ApiMonitorSweeper` bulk delete。

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
│   ├── ExtractRule.java             record(name, pointer)
│   └── ComputedField.java / ComputedStep.java / HashAlgorithm.java / HashEncoding.java
│                                    （V6 計算欄位，見 13 號文件）
├── fetch/
│   ├── OutboundUrlGuard.java        ★ 安全關鍵
│   ├── ApiFetcher.java
│   ├── DnsResolver.java / SystemDnsResolver.java   解析可換掉，測試才能注入假位址
│   └── FetchResult.java             sealed: Success | Failure
├── parse/
│   ├── JsonExtractor.java
│   ├── ChangeDetector.java
│   ├── ChangeResult.java            sealed: Changed | Unchanged
│   └── MessageTemplate.java
├── request/RequestTemplate.java     送出前替換 {{ }}（URL / header / body 共用同一瞬間）
├── compute/                         ComputedFieldEvaluator / ComputedFieldValidator
├── secret/                          MonitorSecret(+Id/Repository/Service) 每監控的加密 secret
├── session/                         SiteSessionService / CookieCodec / HostMatcher / SiteSession
├── importer/                        cURL / fetch(...) / JSON 匯入解析（見 12 號文件）
├── ApiMonitorStore.java             交易邊界：claim / recordSuccess / recordFailure
├── ApiMonitorRunner.java            編排：claim → fetch → parse → compare → notify
├── ApiMonitorTestRunner.java        試跑（§10 的 POST /monitors/test）：不發送、不寫狀態
├── ClaimedMonitor.java              claim 交易產出的不可變快照（不是 entity）
├── RunAttempt.java                  sealed: Success | Failure（無交易段的結果）
├── MonitorTestOutcome.java          試跑結果
├── ApiMonitorScheduler.java         @Scheduled
├── ApiMonitorSweeper.java           保留期清理（run / seen_item）
├── MonitorAsyncConfig.java          monitorTaskExecutor 執行緒池
└── MonitorProperties.java           @ConfigurationProperties("app.monitor")
```

JPA 慣例照既有：JSONB 用 `@JdbcTypeCode(SqlTypes.JSON)` + `String` 欄位
（見 `Notification.payload`），enum 用 `@Enumerated(EnumType.STRING)`。

`ApiMonitorRunner` 整個類別**沒有任何 `@Transactional`**；所有要交易的動作都放在
`ApiMonitorStore`（不同 bean，同 bean 內自呼叫會讓 Spring 代理失效）。

---

## 5. SSRF 防護規格 ★ 安全關鍵

> 這個功能會把抓回來的內容渲染進 LINE 訊息並存進執行紀錄。
> 它不是盲 SSRF，是**附帶完整回傳通道的任意網址讀取器**。
> 「網址是管理員自己設的」描述的是今天的狀態，不是控制措施 ——
> 控制要在後台被攻破之後仍然成立。

### 5.1 強制（無開關）

檢查分屬兩個類別：**1–4 在 `OutboundUrlGuard.check()`**（送出前，對替換完成的 URL 做），
**5–7 在 `ApiFetcher`**（送出中／收回應時）。兩邊都沒有開關可以關掉。

1. **只允許 `https`**（大小寫不拘）。其他 scheme（含 `http`）一律
   `BlockedException("Only https URLs are permitted.")`。
2. **host 必須解析得出**，否則拒絕（`"URL host could not be determined."`）；
   DNS 查不到或回空陣列同樣拒絕。
3. **解析 DNS，檢查每一個回傳的位址**（`InetAddress.getAllByName`，可換的
   `DnsResolver` 介面讓測試注入假位址）。任何一個落在下列範圍就整個拒絕
   （round-robin DNS 可以只讓其中一筆指向內網）。
4. **IPv4-mapped / NAT64 位址必須先還原成 IPv4 再檢查一次**
   （`::ffff:10.0.0.1` 是內網位址）。**未知位址家族一律封鎖**（fail closed）。

封鎖範圍：

| 版本 | 範圍 |
|---|---|
| IPv4（15 條） | `0.0.0.0/8`、`10.0.0.0/8`、`100.64.0.0/10`(CGNAT)、`127.0.0.0/8`、`169.254.0.0/16`、`172.16.0.0/12`、`192.0.0.0/24`、`192.0.2.0/24`、`192.168.0.0/16`、`198.18.0.0/15`、`198.51.100.0/24`、`203.0.113.0/24`、`224.0.0.0/4`、`240.0.0.0/4`、`255.255.255.255/32` |
| IPv6（6 條） | `::/128`、`::1/128`、`fc00::/7`(ULA)、`fe80::/10`(link-local)、`ff00::/8`(multicast)、`2001:db8::/32` |
| 還原規則（不是封鎖範圍） | `::ffff:0:0/96`（IPv4-mapped）與 `64:ff9b::/96`（NAT64）→ 取出內嵌的 IPv4 再比一次上面 15 條 |

對外的錯誤訊息刻意籠統，**不回顯解析到的內部位址**；host 與位址細節只寫伺服器端 log。

`169.254.0.0/16` 是**最重要的一條** —— 雲端 metadata endpoint
（`169.254.169.254`）會吐出 instance 的 IAM 憑證。

> **不可只依賴** `InetAddress.isSiteLocalAddress()` 等內建方法：它們不涵蓋
> CGNAT `100.64/10`、`192.0.0/24`，也不處理 IPv4-mapped 還原。要顯式比對範圍。

5. **不跟隨 redirect**：`HttpClient.Redirect.NEVER`；`300 <= status < 400` 直接回
   `REDIRECT_NOT_ALLOWED` 並關掉 body。跟隨 redirect 等於讓對方一跳就繞過 1–4。
6. **逾時**：connect 5s、read 10s（可設定）。JDK 的 `HttpClient` 沒有獨立的讀取逾時，
   `read-timeout` 是掛在 `HttpRequest.timeout()` 上、涵蓋**整個請求—回應週期**的值。
7. **回應大小上限 1 MB**，**邊讀邊擋**（每 8 KB 一段累加，超過立刻中斷並丟出不攜帶任何
   回應內容的內部例外 → `BODY_TOO_LARGE`），不是整包讀完再判斷。
8. **Content-Type 必須是 JSON**：取第一個 `Content-Type`、切 `;` 取 media type、trim、
   轉小寫後必須等於 `application/json` 或以 `+json` 結尾（如 `application/vnd.api+json`），
   否則 `NON_JSON_CONTENT_TYPE`。

失敗一律轉成 `FetchResult.Failure(reason, detail, httpStatus, setCookieHeaders)`，
`Reason` 共七個：`BLOCKED_URL`（保留給呼叫端正規化，`ApiFetcher` 自己不產生）、
`REDIRECT_NOT_ALLOWED`、`TIMEOUT`、`BODY_TOO_LARGE`、`NON_JSON_CONTENT_TYPE`、
`HTTP_ERROR`、`NETWORK_ERROR`。`detail` 一律不含回應內容全文
（`NETWORK_ERROR` 只放例外類別名稱，細節僅寫 `log.debug`）。

`ApiFetcher` 對 header **原樣送出、不正規化、不去重、不過濾**，解密與組裝是呼叫端的職責；
它也不碰 cookie —— 出站 cookie 由呼叫端事先塞進 header，回應的 `Set-Cookie`
不論狀態碼一律先收進 `FetchResult` 交給 `SiteSessionService` 合併。

### 5.2 選配（預設關）

`app.monitor.allowed-hosts` —— 逗號分隔，空值代表**允許任何公開網域**（仍受 §5.1 全部約束）。
非空時只有清單內的網域可打。比對在 `OutboundUrlGuard.checkAllowList()` 自己實作
（不是共用 `UriHostValidator`）：兩邊都先過 IDN 正規化並轉 `Locale.ROOT` 小寫，
接受**完全相等**或 `"." + entry` 結尾的子網域。**不可用裸 `endsWith`** ——
那會讓 `evil-example.com` 誤配到白名單裡的 `example.com`。

### 5.3 已知殘留風險

**DNS rebinding**：`OutboundUrlGuard.check()` 通過後、`ApiFetcher` 實際建立連線前，
DNS 可能改指內網（TOCTOU）。完整解需要 pin 住已驗證 IP 的 socket factory。
v1 不做，理由是 §5.1 已擋掉絕大多數實際可利用的路徑，而自訂 socket factory
會顯著增加複雜度。**此處刻意留下，不是遺漏** ——
`OutboundUrlGuard.java:69-75` 的類別註解自己寫明了這個取捨。

其餘殘留風險見 §14。

---

## 6. 解析、比對、組內容

### 6.1 `JsonExtractor`

以 Jackson 3 的 `tools.jackson.core.JsonPointer` 取值，語法即 RFC 6901，
完全委派 `JsonPointer.compile()` —— **沒有自訂語法**：沒有 JSONPath、沒有萬用字元、
沒有過濾器、沒有 `$` 前綴。

| 情況 | 結果 |
|---|---|
| 純量 | `asString()`（數字 `42` → `"42"`、布林 → `"true"`） |
| 物件／陣列 | 序列化成緊湊 JSON 字串 |
| 路徑不存在、中途缺節點、陣列索引越界、值是 JSON `null` | `null` |
| `""`（空字串） | 合法，指向整份文件 → 回整份 JSON 緊湊字串 |
| 沒有前導 `/`（例如 `status`） | 畸形 → `ApiException(VALIDATION_ERROR, "Invalid JsonPointer syntax: ...")` |
| body 不是合法 JSON | `ApiException(VALIDATION_ERROR, "Response body is not valid JSON: ...")` |

RFC 6901 的 `~0`（`~`）／`~1`（`/`）跳脫由 Jackson 原生支援，但 repo 內沒有測試覆蓋
→ **TODO：待確認**。

方法：`parse(json)` → `at(root, pointer)`（回原始 `JsonNode`，呼叫端要判型別時用）
→ `extract(root, pointer)`（回字串或 `null`）→ `extractAll(root, rules)`
（依規則順序的 `LinkedHashMap`，值可為 `null`，回傳唯讀）。

### 6.2 `ChangeDetector`

| 模式 | 比對基準 | 指紋 |
|---|---|---|
| `WHOLE_BODY` | 整個回應 body | `SHA-256(body)`（原始 bytes，不先解析） |
| `EXTRACTED` | 所有 `extract_rules` 取出的值 | 值放進 `TreeMap`（依 name 排序）→ JSON 序列化 → `SHA-256` |
| `NEW_ITEMS` | 不用指紋（恆 `null`），比對 `seen_item` 表 | — |

`EXTRACTED` 用 **JSON 序列化**避免名稱、分隔符與值產生歧義；空字串是有效值。
任一規則取不到值或是 JSON null，該輪為 PARSE_ERROR，保留上一個有效指紋／狀態，
不發內容變更通知；仍可能按 notifyOnFailure 的門檻發故障通知。舊基準若缺少目前規則的值，
第一個完整回應會安靜重建基準，不補發恢復變更。用 TreeMap 確保調整規則順序不觸發假變更。

`WHOLE_BODY` **只有在 `extract_rules` 非空時才解析 JSON** —— 沒填規則時，
目標回傳的即使不是合法 JSON（但 Content-Type 標了 JSON），指紋比對照樣能運作。

**首次執行只記錄不通知。** `last_fingerprint IS NULL` 時寫入基準、回 `UNCHANGED`。
否則建一個監控就先被通知一次，全部都是雜訊。
（`detectByFingerprint` 收到 `NEW_ITEMS` 會直接 `IllegalArgumentException` —— 兩條路徑不共用。）

`NEW_ITEMS` 流程：
1. `item_pointer` 取出陣列；**不是陣列 → `ApiException(VALIDATION_ERROR, "item_pointer does not resolve to a JSON array: ...")`**。空陣列仍是陣列，正常回「沒有新項目」
2. 每個元素用 `item_key_pointer`（相對於元素）取鍵值；**取不到鍵的元素直接略過**，不讓一個壞元素毀掉整輪
3. 與 `seen_item` 比對，找出沒看過的；新項目的欄位用 `extract_rules` 對**該元素**再取一次（渲染 `{{item.NAME}}`）
4. **首次執行**：全部寫入 `seen_item`，不通知
5. 之後：新項目寫入並通知；一則訊息最多列 20 筆，其餘寫「還有 N 筆」，最後保底截到 5000 字

> **`firstRun` 不可用 `seenKeys.isEmpty()` 代替** —— 目標若一直回空陣列，
> `seenKeys` 也會一直是空的，那樣會永遠當成首次執行而永不通知。
> 實際判斷用 `monitor.getLastRunAt() == null`，且必須在 `lease()` **之前**讀（§7）。

> **一個欄位、兩種解讀**：`extract_rules` 在 `NEW_ITEMS` 下相對於**每個陣列元素**解讀，
> 其他模式相對於**整個 body**。`ChangeDetector` 的參數名叫 `item_field_rules`，
> 但 `api_monitor` 表**沒有這個欄位**，重用的就是 `extract_rules`。

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
- 佔位符內容允許前後空白：`{{ value.status }}` 與 `{{value.status}}` 等價
- 未知佔位符 → 替換成 `—`，並記一筆 WARN。**不可原樣輸出** `{{...}}`，
  那會讓使用者以為模板沒生效而反覆重設
- 值為 `null` → `—`（**不記 WARN**，那是資料本身的狀態，不是模板寫錯）
- 剝除控制字元（`U+0000`–`U+001F`，保留 `\n`）
- 渲染後截到 **4500 字**（LINE 上限 5000，留 500 字餘裕給呼叫端加前後綴，
  例如 `NEW_ITEMS` 的「還有 N 筆」）

兩個容易踩到的語意：

- **模式決定哪些佔位符有值**：`NEW_ITEMS` 模式下 `{{value.NAME}}` / `{{old.NAME}}` 恆為
  `—`（那兩個 map 恆空），只能用 `{{item.NAME}}`；反之其他兩種模式的 `{{item.NAME}}` 恆為 `—`。
- **訊息模板與請求模板的佔位符不是同一套，行為還相反**：訊息模板只認 `{{now}}`（無子指令）、
  未知佔位符靜靜變 `—`；請求模板（`RequestTemplate`，見 13 號文件）認 `{{now.*}}` /
  `{{uuid}}` / `{{computed.*}}`，未知佔位符直接回 400。在訊息模板裡寫
  `{{now.epochSeconds}}` 只會得到 `—`。

> **內容注入**：目標 API 回傳的內容會進入 LINE 訊息。走
> `NotificationService.submit()` 就會經過 `UriHostValidator`，
> 第三方 API 回傳的釣魚連結會被擋下。**因此絕不可繞過 `submit()` 直接發送。**

---

## 7. 執行流程與交易邊界

```
ApiMonitorScheduler.poll()  @Scheduled(fixedDelayString = app.monitor.poll-interval)
  └─ ApiMonitorRunner.runOnce()
       ├─ [交易 1] store.claim(claimLimit)
       │     ApiMonitorRepository.lockDue(now, Limit)
       │       select m from ApiMonitor m where m.enabled = true and m.nextRunAt <= :now
       │       order by m.nextRunAt
       │       @Lock(PESSIMISTIC_WRITE) + lock.timeout = -2
       │       → Postgres 產生 FOR UPDATE SKIP LOCKED
       │     → firstRun = (lastRunAt == null)  ← 必須在 lease() 之前判
       │     → next_run_at = now + lease（租約，防同一輪還沒回寫又被取走）
       │     → 組 ClaimedMonitor 不可變快照：解密 header／解析 extract_rules／
       │       parse last_state／載入 seenKeys／解密 secrets／解析 computed_fields
       │  ── 交易結束，連線歸還 ──
       │
       ├─ 每筆一個 CompletableFuture.runAsync(..., monitorTaskExecutor)（完全無交易）：
       │     RequestTemplate.newSession()（凍結同一個 Instant + UUID）
       │       → ComputedFieldEvaluator.evaluate(...)
       │       → RequestTemplate.render(url / headers / body)  失敗 → TEMPLATE_ERROR
       │       → OutboundUrlGuard.check(uri)                   失敗 → BLOCKED_URL
       │       → SiteSessionService.attachCookies(host, headers)（失敗只 WARN）
       │       → ApiFetcher.fetch(...)
       │       → SiteSessionService.mergeSetCookies(...)（成功失敗都做）
       │       → ChangeDetector（NEW_ITEMS / 指紋兩條路）    失敗 → PARSE_ERROR
       │       → Changed 才 MessageTemplate.render(...) 組訊息
       │     結果一律收斂成 RunAttempt.Success / RunAttempt.Failure
       │     awaitAll 逾時 = (connect + read) × 取件筆數 + 10s；逾時不拋、只 log.warn
       │
       └─ [交易 2] store.recordSuccess(...) / store.recordFailure(...)
             更新 fingerprint / last_state / seen_item / 計數器
             next_run_at = now + effectiveInterval（失敗則退避，§8）
             寫 api_monitor_run
             有變更且通過防洗版 → notificationService.submit(...)
```

**`@Scheduled` 方法必須吞掉所有例外** —— 拋出去會讓 Spring 停掉後續排程，
等於整個監控靜悄悄永久停擺。照 `DeliveryScheduler.poll()` 的寫法。
`ApiMonitorRunner.process()` 也 catch 所有 `Exception` 只記 log，靠租約到期後
下一輪重新取件（outbox 式，不掉單）；**沒有 in-process 重試**，一次失敗就記一列
`FAILED` 並退避。

**`SKIP LOCKED` 與租約解決的是兩個不同問題，缺一不可**：前者管「兩個工作者同時取件」，
後者管「這一輪還沒回寫、下一輪又取到同一筆」。

**`ClaimedMonitor` 是不可變快照、不是 JPA entity**，避免在無交易區段觸發 lazy loading
偷開連線。它**刻意不帶** `cooldownSeconds`、`maxNotificationsPerDay`、
`consecutiveFailures`、`failureNotified`、`lastNotifiedAt`、`notifiedCount`、`notifiedDay`
—— 這些只在回寫交易內用當下最新的 managed entity 讀。
`url` 也刻意保留**原始樣板文字**、不在 claim 交易內做替換或 `URI.create()`：
單筆樣板寫壞不該讓整個 claim 交易失敗。

**獨立執行緒池**（`MonitorAsyncConfig` 的 `monitorTaskExecutor`：core 2 / max 4 /
queue 50 有界 / `CallerRunsPolicy` / `threadNamePrefix="monitor-"` / 關機時等 30 秒 /
`TaskDecorator` 複製 MDC），不共用派送的 `notifyTaskExecutor`，也不佔用只有 3 條執行緒的
`spring.task.scheduling` 池。一個慢掉的第三方 API 不該拖慢 LINE 發送。

**輪詢是 `ApiMonitorRunner` 唯一的觸發來源**（沒有即時 kicker）：監控自己的
`interval_seconds` 到期後，最多還要再等一個 `poll-interval`（預設 10s）才會被取件。
`fixedDelay` 不是 `fixedRate` —— 間隔從上一輪**結束**起算，第三方變慢時不會堆積工作。

---

## 8. 防洗版（LINE 免費額度 200 則/月，這是硬需求）

| 機制 | 規則 |
|---|---|
| 間隔下限 | `interval_seconds >= 30`（DB 約束＋entity 驗證）。**執行時另外夾在 `app.monitor.min-interval`**（預設 60s）：`effectiveIntervalSeconds() = max(interval_seconds, min-interval 的秒數)`，管理員調高全域下限後立即生效，不必重編每一筆監控 |
| 冷卻 | `last_notified_at + cooldown_seconds > now` → 跳過通知，**且不更新 fingerprint**，冷卻結束後仍會通知。紀錄 `SKIPPED` |
| 每日上限 | `notified_day` 與今天（`Asia/Taipei`）不同 → 歸零 `notified_count`。達 `max_notifications_per_day` → `SKIPPED`（同樣不更新 fingerprint）。`null` = 不限 |
| 失敗退避 | `next_run_at = now + effectiveInterval × min(2^(failures-1), 16)`；指數先夾在 `0..4` 再取冪（避免長期故障時整數溢位），乘數序列 `1, 2, 4, 8, 16, 16, ...` |
| 失敗只報一次 | `notify_on_failure` 為真、`consecutive_failures >= failure-notify-threshold`（預設 3）且 `failure_notified = false` → 發一則，送出成功才設 `failure_notified = true`。HTTP 401/403 改用「登入過期」文案。成功時歸零；若曾通知過失敗則補發一則恢復通知 |
| client 每日配額 | `submit()` 內既有的 `enforceDailyQuota` 自動生效，不用另寫 |

`ApiMonitorStore.tryNotifyChange()` 的判斷順序（順序本身是規格）：

1. `rolloverNotifiedDayIfNeeded(今天 @ Asia/Taipei)` —— **必須在冷卻與上限檢查之前**，
   否則跨日的第一則會被昨天的計數擋掉。
2. 冷卻中 → `log.info` + 不送。
3. 達每日上限 → `log.info` + 不送。
4. client 不存在或 `status != ACTIVE` → `log.warn` + 不送。
5. `NotificationService.submit()`；丟 `ApiException`（例如 client 每日配額用罄）→
   `log.warn` + **視同被擋下**（指紋不更新，額度恢復後會補發）。
6. 只有真的送出去才 `markNotified(now)`。

**兩條鐵律**

- **鐵律一**：因冷卻或每日上限而跳過通知時，`last_fingerprint` / `last_state` /
  `seen_item` **一律不更新**（`outcome = SKIPPED`）—— 否則等於把這次變更悄悄吃掉，
  之後再也不會通知。
- **鐵律二**：`notified_count` / `notified_day` / `last_notified_at`
  **只在變更通知成功送出時更新**。失敗通知與恢復通知走 `failure_notified` 這條獨立的
  「每次故障最多一則」規則，不佔用、也不受每日上限影響。

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

綁在 `MonitorProperties`（`@ConfigurationProperties("app.monitor")`，`record`）。
compact constructor 對每個 `null` 或非正值套用預設值，所以 yml 少寫一鍵不會炸，
只是靜靜回到預設：`poll-interval PT10S`、`claim-limit 5`、`lease PT2M`、
`min-interval PT60S`、`connect-timeout PT5S`、`read-timeout PT10S`、
`max-body-bytes 1048576`、`failure-notify-threshold 3`、`run-retention P14D`、
`seen-item-retention P90D`、`allowed-hosts ""`。

`app.monitor.enabled=false` 時 `ApiMonitorScheduler` 不註冊
（`@ConditionalOnProperty(matchIfMissing = true)`，照 `DeliveryScheduler` 的寫法）。
這個開關是給整合測試用的，**正式環境不要關**。

`run-retention` / `seen-item-retention` 由 `ApiMonitorSweeper` 使用：
每天 **03:20**（`@Scheduled(cron = "0 20 3 * * *")`，伺服器時區）bulk delete
`api_monitor_run` 與 `api_monitor_seen_item` 的過期資料，同樣 `try/catch` 全吞。
它**不掃** `site_session`、`monitor_secret`，也不掃 `api_monitor` 本身。

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
| POST | `/monitors/test` | **試跑**：body 帶完整設定（未存檔也可），抓一次、回傳取到的值與渲染後訊息，**不發送、不寫狀態**。回應帶 `Cache-Control: no-store` |
| GET | `/monitors/{id}/runs` | 最近 50 筆執行紀錄 |
| GET | `/monitors/{id}/headers` | 目前設定的 header 明文（`name -> value`），供編輯抽屜的「顯示目前值」。**不列在列表摘要裡**，值只在明確呼叫這個端點時才離開伺服器；`Cache-Control: no-store`；沒設 header 回空 map（200，不是錯誤） |
| DELETE | `/monitors/{id}/secrets/{name}` | 刪除單一監控 secret（不可回復）。刻意獨立端點，不透過 `PUT /monitors/{id}` |
| POST | `/monitors/import` | 貼上的 cURL / `fetch(...)` / 自訂 JSON 解析成請求設定，**不存檔**；`Cache-Control: no-store`（貼上的內容含 cookie 與 token）。細節見 [12-API監控易用性升級](12-API監控易用性升級.md) §2.6 |

回傳的 DTO **絕不可包含** `headers_ciphertext`，列表摘要也不帶解密後的 header 值
（要看值必須另外呼叫 `GET /monitors/{id}/headers`）。
編輯時 header 欄位留空 = 不變更，有填 = 覆寫。

試跑（`ApiMonitorTestRunner`）走的是**跟排程完全相同**的 `OutboundUrlGuard` 檢查 ——
否則它就是一個繞過所有防護的 SSRF 入口。試跑成功時回應會帶目標 API 的原始回應內容
（給後台畫成可展開的欄位選取樹），這正是它必須 `no-store` 的原因。

站台登入狀態（cookie jar）另有 `/sessions` 系列端點，見 12 號文件。

---

## 11. 前端

`server/src/main/resources/static/admin/` 的 `index.html` /
`assets/app.js` / `assets/app.css` 加「監控」頁：

- nav 新增一項（照現有 `.nav a` + `.nav__ico` 結構，`data-view="monitors"`）；
  站台登入狀態另有 `data-view="sessions"`（見 12 號文件）
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
| `OutboundUrlGuard` | §5.1 每個封鎖範圍各一；IPv4-mapped `::ffff:10.0.0.1`；多筆 A 記錄其一為內網；http 拒絕；白名單 `evil-example.com` vs `example.com`；IDN 同形字。位址用假的 `DnsResolver` 注入，不打真 DNS |
| `JsonExtractor` | 純量／物件／陣列／取不到／深層巢狀／索引越界／JSON `null`／空 pointer／無前導 `/` 的畸形寫法（`~0`／`~1` 跳脫 ⏳ 尚無測試） |
| `ChangeDetector` | 三種模式 × (首次／無變更／有變更)；`EXTRACTED` 欄位順序不影響指紋；`NEW_ITEMS` 的 `item_pointer` 不是陣列、元素取不到 key、>200 字元 key 取 SHA-256 |
| `MessageTemplate` | 各佔位符；未知佔位符；null 值；控制字元；超長截斷 |
| 防洗版 | 冷卻中不更新 fingerprint；每日上限歸零；退避倍數；失敗只通知一次 |

**Integration（Testcontainers + MockWebServer）**

- 目標 API 打樁：正常 JSON、非 JSON content-type、超大 body、逾時、3xx、5xx
- `claim()` 併發：兩個工作者不會取到同一筆
- 端到端：值變更 → 產生 `notification` 列且 `api_monitor_run.outcome = CHANGED`
- 首次執行不通知
- 試跑不寫監控基準／run／seen-item 或通知；但會連線目標，站台登入可寫 token 快取及失敗停用狀態

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

W1–W4 已完成。之後另有兩個波次不在本文範圍：
易用性升級（匯入、`monitor_secret`、`site_session`、請求樣板）見
[12-API監控易用性升級](12-API監控易用性升級.md)；
計算欄位（`computed_fields`、`V6__monitor_computed.sql`）見
[13-監控計算欄位設計](13-監控計算欄位設計.md)。

---

## 14. 已知缺口

誠實清單。這裡列的都是**目前實際存在**的問題，不是待辦願望。

### 14.1 效能與擴展性

- **`ApiMonitorStore.loadSeenKeys()` 對 `api_monitor_seen_item` 做全表 `findAll()`
  再在記憶體 filter `monitorId`**（`ApiMonitorStore.java:202-214`）。
  程式碼註解說明這是「W3 不可修改 domain repository」的波次限制加上規模假設，
  但實際後果是：**每次 claim、每一筆 `NEW_ITEMS` 監控都會做一次全表掃描**。
  監控數或項目數長大以後這是第一個會痛的地方。修法是加一個
  `findByIdMonitorId(...)`（或只查 `item_key` 的 projection）取代 `findAll()`。
- `SiteSessionService.findJarForHost()` 同樣是全表 `findAll()`，而且每次抓取前後
  各跑一次（`attachCookies` + `mergeSetCookies`）。
- **`ApiMonitorSweeper` 在多實例下會重複執行**（`ApiMonitorSweeper.java:42-46`）。
  刪除是冪等的，所以不會壞資料，但兩個實例會各刪一次、白白吃資源。
  註解明寫「加第二個實例前需導入 ShedLock」，**目前尚未導入** ⏳。

### 14.2 安全上刻意留下的風險

- **DNS rebinding / TOCTOU 不處理**（§5.3，`OutboundUrlGuard.java:69-75` 自述）。
- **`CookieCodec` 忽略 `Path` / `Secure` / `SameSite` / `Expires` / `Max-Age`**：
  jar 裡的 cookie **永不過期、也永不被清理**（sweeper 不掃 `site_session`）。
  失效的 cookie 只能靠使用者手動刪掉或重貼。
- `monitor_secret` 沒有輪替或保留期機制；`api_monitor` 本身也不在 sweeper 範圍內。

### 14.3 例外處理縫隙

- **`ApiFetcher.fetch()` 的 `buildRequest(request)` 寫在 `try` 區塊外**
  （`ApiFetcher.java:67-68`）。JDK 的 `HttpRequest.Builder.header()` 遇到受限的
  header 名稱會丟 `IllegalArgumentException`，這個例外**不會**被轉成
  `FetchResult.Failure`，會一路穿過 `ApiMonitorRunner.execute()`（沒有對應 catch）
  掉進 `process()` 的 catch-all。後果是：
  **不寫 `api_monitor_run` 紀錄、不累加 `consecutive_failures`、不退避**，
  只能等租約到期重試 —— 於是每一輪都失敗，而**後台完全看不到原因**。
  修法是把 `buildRequest` 移進 `try`，比照其他失敗轉成
  `FetchResult.Failure`（例如 `NETWORK_ERROR`）。
  （JDK 受限 header 的確切清單不在本 repo 內 → **TODO：待確認**。）
- `ApiMonitorTestRunner.buildNewItemsMessage()` 少了 `ApiMonitorRunner` 有的
  5000 字保底截斷 —— 試跑預覽的訊息可能比實際能送出的長，預覽與實際不一致。
- `ComputedFieldValidator.validatePlaceholders()` 用 `isEmpty()` 而不是 `isBlank()`，
  純空白的 `input` 會通過驗證，但錯誤訊息寫的是 `must have a non-empty input`。

### 14.4 規劃了但沒做 ⏳

- **JSONPath**：§2 說「留擴充點，日後可加」，目前只有 RFC 6901 JsonPointer。
- **多實例排程協調（ShedLock）**：見 §14.1。
- **DNS pinning 的 socket factory**：見 §5.3。

### 14.5 註解與實作已經對不上（doc drift）

以下是程式碼裡的過時註解，讀 code 時別被誤導：

- `ImportedRequest.java:22-24` 仍寫「cookie 目前當成一般 header 處理，W6 才會移出去」，
  但 `AdminService.importMonitorRequest` **已經**會把 cookie 抽出寫進 `SiteSessionService`
  並從回傳的 header 移除。
- `SeenItem.java:13-15` 寫「>200 字元取雜湊這條規則本波次不強制驗證」，
  但 `ChangeDetector.normalizeItemKey()` **已經**實作了。
- `ChangeResult.NewItem` 與 `ChangeDetector.detectNewItems` 的參數名提到
  `item_field_rules`，但表上**沒有這個欄位**，重用的是 `extract_rules`（§6.2）。
- `ExtractRule.name` 的 `[A-Za-z0-9_]{1,32}` 只寫在 javadoc 與 migration 註解裡，
  record 本身完全不驗證；實際把關在 `AdminService.validateExtractRules`
  （完整規則 → **TODO：待確認**）。
- `RequestTemplate.render(String)` / `renderHeaders(Map)` 的 ad-hoc session 版本
  **只能用於語法驗證**，用在真要送出的請求上會讓簽章與時間戳對不上；
  類別註解有警告，但型別上沒有任何阻擋。

## 15. V7 與 main 的後續修正

- `api_monitor.login_id` 引用 `monitor_login`，刪除登入時設 null，不刪監控。
- 正式輪詢與立即測試都支援 loginId：請求模板渲染後注入 token，大小寫不敏感地取代同名 header，避免疊兩份 Authorization。
- 登入後遇目標 401 可使快取失效並重新登入、重試一次；登入失敗分類與停用見 [15](15-監控站台登入設計.md)。
- 編輯監控會把 next_run_at 移到目前時間，重設失敗計数、故障通知旗標與比對基準，最早由下一輪排程取走；不會清除當日通知計數或冷卻紀錄。
- ClaimedMonitor／MonitorTestOutcome／ApiMonitor 的狀態快照能保留 null 且不可變；EXTRACTED 的非 null 驗證發生在比對層，不應以 Map.copyOf 把可表示的資料變成例外。

以上由現有監控與登入的單元／整合測試涵蓋，不代表此工作已連線真實第三方站台驗證。
