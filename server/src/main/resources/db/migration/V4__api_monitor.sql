-- =====================================================================
--  V4 - API 監控輪詢
--
--  設計依據：Docs/plan/11-API監控輪詢設計.md
--
--  不開第二條發送路徑：偵測到變更後一律呼叫既有的
--  NotificationService.submit()，免費拿到 scope 檢查、預設對象、
--  每日配額、連結白名單、切批、重試。這裡只負責「什麼時候該發」，
--  不負責「怎麼發」。
-- =====================================================================


-- ---------------------------------------------------------------------
--  api_monitor - 監控項目設定 + 執行狀態。
--
--  設定與執行狀態刻意放同一張表（不拆成 config / state 兩張）：
--  取件（claim）本來就要把兩者一起讀出來，拆表只會多一次 join，
--  換不到任何好處 —— 這張表從來不會被高併發寫入其他欄位。
-- ---------------------------------------------------------------------
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

COMMENT ON COLUMN api_monitor.headers_ciphertext IS
    'AES-256-GCM，AAD = monitor:{id}，與 client.secret_ciphertext 同一套金鑰管理。null = 無自訂 header。';
COMMENT ON COLUMN api_monitor.extract_rules IS
    '格式：[{"name": "status", "pointer": "/data/0/status"}, ...]。'
    'name 限 [A-Za-z0-9_]{1,32}，模板用 {{value.status}} 引用。';
COMMENT ON COLUMN api_monitor.last_fingerprint IS
    'WHOLE_BODY / EXTRACTED 模式的比對基準：SHA-256。NEW_ITEMS 模式不使用，改比對 api_monitor_seen_item。';
COMMENT ON COLUMN api_monitor.last_state IS
    '格式：{"status": "OK", "count": "12"}。取出的值一律轉字串存，模板不做型別運算，'
    '避免數字精度與 locale 問題。null = 尚未執行過第一次。';
COMMENT ON COLUMN api_monitor.consecutive_failures IS
    '成功時歸零。達 failure-notify-threshold 且 failure_notified=false 才發一則失敗通知，避免每次失敗都洗版。';
COMMENT ON COLUMN api_monitor.failure_notified IS
    '避免連續失敗期間重複通知。成功時歸零；若曾通知過失敗，成功時會補發一則恢復通知。';
COMMENT ON COLUMN api_monitor.notified_count IS
    '搭配 notified_day 做每日上限：notified_day 與今天不同時歸零，用於防洗版（LINE 免費額度 200 則/月）。';
COMMENT ON COLUMN api_monitor.next_run_at IS
    '取件（claim）唯一依據。取件時推到 now + lease 當租約；成功/失敗後改推到 now + interval（或退避）。';

-- 取件的唯一查詢路徑：WHERE enabled AND next_run_at <= now() ORDER BY next_run_at
-- FOR UPDATE SKIP LOCKED（抄 idx_delivery_pickup 的做法）
CREATE INDEX idx_api_monitor_due ON api_monitor (next_run_at) WHERE enabled;


-- ---------------------------------------------------------------------
--  api_monitor_run - 每次輪詢的執行紀錄。
--
--  error_message 不得寫入回應內容全文，只寫分類與長度 —— 回應可能含
--  目標 API 的機敏資料，後台紀錄不是存它的地方。
-- ---------------------------------------------------------------------
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

COMMENT ON COLUMN api_monitor_run.error_message IS
    '只寫分類與長度，不得含目標 API 回應內容全文（可能含機敏資料）。';
COMMENT ON COLUMN api_monitor_run.notification_id IS
    '僅 outcome=CHANGED 且通過防洗版時有值。指向 notification.id，不設外鍵 —— '
    'notification 90 天後可能被清理，此處是歷史紀錄不該因此被連動刪除。';

-- 管理台「最近執行紀錄」查詢路徑
CREATE INDEX idx_api_monitor_run_recent ON api_monitor_run (monitor_id, started_at DESC);


-- ---------------------------------------------------------------------
--  api_monitor_seen_item - NEW_ITEMS 模式已看過的項目鍵。
--
--  item_key 超過 200 字元時取 SHA-256 十六進位字串，不截斷 —— 截斷會讓
--  兩個不同項目撞成同一個 key，變成「新項目被當成看過的」而永遠不通知。
-- ---------------------------------------------------------------------
CREATE TABLE api_monitor_seen_item (
    monitor_id    BIGINT       NOT NULL REFERENCES api_monitor (id) ON DELETE CASCADE,
    item_key      VARCHAR(200) NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (monitor_id, item_key)
);

COMMENT ON COLUMN api_monitor_seen_item.item_key IS
    '超過 200 字元時呼叫端取 SHA-256 十六進位字串代入，絕不截斷。';

-- 保留期清理（ApiMonitorSweeper）的查詢路徑
CREATE INDEX idx_api_monitor_seen_age ON api_monitor_seen_item (first_seen_at);
