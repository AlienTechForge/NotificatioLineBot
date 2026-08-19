-- =====================================================================
--  V1 - 初始 schema
--
--  設計依據：Docs/plan/04-資料模型.md
--
--  注意：初始 owner 的標記「不」放在這裡。migration 應該與環境無關，
--  而 APP_OWNER_LINE_USER_ID 是環境設定；放進 migration 會讓它只在
--  第一次建置時生效，日後改環境變數不會反映。改由應用啟動時 upsert，
--  在 T5（LINE User 領域）實作。
-- =====================================================================


-- ---------------------------------------------------------------------
--  line_user - Bot 好友。所有發送的最終依據。
-- ---------------------------------------------------------------------
CREATE TABLE line_user (
    line_user_id       VARCHAR(64)  PRIMARY KEY,               -- U[0-9a-f]{32}
    display_name       VARCHAR(200),
    picture_url        TEXT,
    status_message     TEXT,
    language           VARCHAR(16),
    is_owner           BOOLEAN      NOT NULL DEFAULT false,
    status             VARCHAR(16)  NOT NULL,
    followed_at        TIMESTAMPTZ,
    unfollowed_at      TIMESTAMPTZ,
    profile_synced_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT line_user_status_chk CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

COMMENT ON TABLE  line_user IS 'LINE Bot 好友。profile 欄位可為 null，由 Profile API 非同步補齊。';
COMMENT ON COLUMN line_user.status IS 'ACTIVE = 目前是好友；BLOCKED = 已封鎖或已刪帳號。';

-- target=ALL 的主查詢：撈所有 ACTIVE
CREATE INDEX idx_line_user_active ON line_user (line_user_id) WHERE status = 'ACTIVE';
-- target=OWNER 的主查詢
CREATE INDEX idx_line_user_owner  ON line_user (line_user_id) WHERE is_owner AND status = 'ACTIVE';


-- ---------------------------------------------------------------------
--  client - 呼叫端憑證。
--
--  secret 是「加密」不是雜湊：HMAC 驗簽需要伺服器重算簽章，
--  必須拿得回明文。見 ADR-0002。
-- ---------------------------------------------------------------------
CREATE TABLE client (
    id                    BIGSERIAL    PRIMARY KEY,
    client_id             VARCHAR(64)  NOT NULL UNIQUE,        -- cli_xxxxxxxxxxxxxxxxxxxx
    name                  VARCHAR(100) NOT NULL,
    secret_ciphertext     BYTEA        NOT NULL,               -- AES-256-GCM 密文 + tag
    secret_iv             BYTEA        NOT NULL,               -- 12 bytes，每筆獨立
    secret_key_version    INT          NOT NULL DEFAULT 1,
    bound_line_user_id    VARCHAR(64)  REFERENCES line_user (line_user_id),
    status                VARCHAR(16)  NOT NULL,
    rate_limit_per_min    INT,                                 -- null = 用系統預設
    daily_message_quota   INT,                                 -- null = 不限
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at          TIMESTAMPTZ,
    CONSTRAINT client_status_chk CHECK (status IN ('ACTIVE', 'DISABLED', 'REVOKED'))
);

COMMENT ON COLUMN client.secret_ciphertext  IS 'AES-256-GCM。key 來自 APP_SECRET_ENC_KEY，AAD = client_id。';
COMMENT ON COLUMN client.secret_key_version IS '支援不停機 key 輪替。見 09-CICD與維運 7.1。';
COMMENT ON COLUMN client.status             IS 'DISABLED = 可回復的暫停；REVOKED = 不可回復的作廢。';
COMMENT ON COLUMN client.bound_line_user_id IS 'null = SERVICE client，無綁定使用者。';

-- 一個 LINE user 同時只能有一組有效金鑰 - 由資料庫保證，不靠應用程式記得檢查。
-- REVOKED 的舊金鑰仍留在表中供稽核，但不佔用唯一性。
CREATE UNIQUE INDEX uq_client_bound_active
    ON client (bound_line_user_id)
    WHERE status = 'ACTIVE' AND bound_line_user_id IS NOT NULL;

CREATE INDEX idx_client_lookup ON client (client_id) WHERE status = 'ACTIVE';


-- ---------------------------------------------------------------------
--  client_scope - 權限。見 ADR-0004。
-- ---------------------------------------------------------------------
CREATE TABLE client_scope (
    client_id   BIGINT      NOT NULL REFERENCES client (id) ON DELETE CASCADE,
    scope       VARCHAR(32) NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, scope),
    CONSTRAINT client_scope_chk CHECK (
        scope IN ('notify:self', 'notify:owner', 'notify:user', 'notify:all', 'notify:raw')
    )
);

COMMENT ON COLUMN client_scope.scope IS
    'notify:raw 預設不給任何 client - 原始 message object 可帶 uri action，等於釣魚發送器。見缺口 G5。';

-- 供「哪些 client 有 notify:all」這類反查
CREATE INDEX idx_client_scope_by_scope ON client_scope (scope);


-- ---------------------------------------------------------------------
--  enrollment_token - 自助申請金鑰的一次性 token。
--
--  只存 SHA-256(token)，資料庫外洩也無法還原出可用的連結。
-- ---------------------------------------------------------------------
CREATE TABLE enrollment_token (
    id            BIGSERIAL   PRIMARY KEY,
    token_hash    BYTEA       NOT NULL UNIQUE,                 -- SHA-256，32 bytes
    line_user_id  VARCHAR(64) NOT NULL REFERENCES line_user (line_user_id),
    purpose       VARCHAR(16) NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    consumed_ip   INET,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT enrollment_purpose_chk CHECK (purpose IN ('ISSUE', 'RESET'))
);

COMMENT ON COLUMN enrollment_token.consumed_at IS
    '不是 boolean - 需要知道「何時」被消費，這是安全事件。';

CREATE INDEX idx_enrollment_cleanup ON enrollment_token (expires_at);
-- 支援「每個 user 每小時最多 3 個」的濫用防護
CREATE INDEX idx_enrollment_user    ON enrollment_token (line_user_id, created_at DESC);


-- ---------------------------------------------------------------------
--  notification - 一次發送請求。
-- ---------------------------------------------------------------------
CREATE TABLE notification (
    id                UUID         PRIMARY KEY,
    client_id         BIGINT       NOT NULL REFERENCES client (id),
    idempotency_key   VARCHAR(128),
    request_id        VARCHAR(64),                             -- correlation id，串 log 用
    target_type       VARCHAR(16)  NOT NULL,
    payload           JSONB,                                   -- 90 天後清空，保留 metadata
    payload_hash      BYTEA        NOT NULL,                   -- payload 清空後仍可比對
    status            VARCHAR(16)  NOT NULL,
    recipient_count   INT          NOT NULL DEFAULT 0,
    success_count     INT          NOT NULL DEFAULT 0,
    failure_count     INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    CONSTRAINT notification_target_chk CHECK (target_type IN ('SELF', 'OWNER', 'USER', 'ALL')),
    CONSTRAINT notification_status_chk CHECK (
        status IN ('QUEUED', 'SENDING', 'SUCCEEDED', 'PARTIAL', 'FAILED')
    )
);

COMMENT ON TABLE  notification IS
    '主鍵用 UUID 而非序號：notificationId 會回傳給呼叫端，序號會洩漏系統總量與成長速率。';
COMMENT ON COLUMN notification.payload         IS
    'null 代表呼叫端指定 persistPayload=false，或已過保留期被清空。';
COMMENT ON COLUMN notification.payload_hash    IS '冪等比對與去重用。永遠保留。';
COMMENT ON COLUMN notification.recipient_count IS
    '去正規化。真相在 notification_delivery，此處冗餘讓明細清理後統計仍在。';

-- 冪等：範圍是 (client, key)，不同 client 用相同 key 不衝突
CREATE UNIQUE INDEX uq_notification_idem
    ON notification (client_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_notification_client_time ON notification (client_id, created_at DESC);
CREATE INDEX idx_notification_cleanup     ON notification (created_at);
-- 偵測卡在 SENDING 的通知
CREATE INDEX idx_notification_stuck       ON notification (started_at) WHERE status = 'SENDING';


-- ---------------------------------------------------------------------
--  notification_delivery - 投遞明細，同時是 outbox 工作佇列。見 ADR-0007。
--
--  一列 = 一批（最多 500 人），不是一列一個收件人。LINE multicast
--  本來就是批次語意，個別收件人的成敗 LINE 也不會告訴我們。
-- ---------------------------------------------------------------------
CREATE TABLE notification_delivery (
    id               BIGSERIAL   PRIMARY KEY,
    notification_id  UUID        NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    batch_no         INT         NOT NULL,
    line_user_ids    TEXT[]      NOT NULL,                     -- 一批最多 500
    recipient_count  INT         NOT NULL,
    retry_key        UUID        NOT NULL,                     -- X-Line-Retry-Key，重試時不變
    priority         SMALLINT    NOT NULL DEFAULT 5,           -- 0 高 ... 9 低
    status           VARCHAR(16) NOT NULL,
    attempt_count    INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    line_request_id  VARCHAR(64),                              -- x-line-request-id，對帳憑據
    error_code       VARCHAR(48),
    error_message    TEXT,
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (notification_id, batch_no),
    CONSTRAINT delivery_status_chk     CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT delivery_priority_chk   CHECK (priority BETWEEN 0 AND 9),
    CONSTRAINT delivery_batch_size_chk CHECK (recipient_count BETWEEN 1 AND 500)
);

COMMENT ON COLUMN notification_delivery.retry_key IS
    '建立時就固定，所有重試沿用同一把。不同批用不同把，它們是不同的訊息。';
COMMENT ON COLUMN notification_delivery.priority IS
    'T8 自動指派：OWNER=0、SELF/USER=5、ALL=9。Phase 2 再開放 API 指定。';
COMMENT ON COLUMN notification_delivery.attempt_count IS
    '斷路器 OPEN 期間「不」遞增 - 被擋下的呼叫根本沒送出去，不該算一次嘗試。';

-- 派送器取件的唯一查詢路徑：
--   WHERE status='PENDING' AND next_attempt_at <= now()
--   ORDER BY priority, next_attempt_at LIMIT n FOR UPDATE SKIP LOCKED
CREATE INDEX idx_delivery_pickup
    ON notification_delivery (priority, next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_delivery_cleanup ON notification_delivery (created_at);


-- ---------------------------------------------------------------------
--  request_nonce - HMAC 重放防護。
--
--  靠主鍵衝突判定重放，不先查再插 - 避免併發下兩個請求都查到
--  「不存在」然後都插入成功的競態。
-- ---------------------------------------------------------------------
CREATE TABLE request_nonce (
    nonce      VARCHAR(64) PRIMARY KEY,
    client_id  BIGINT      NOT NULL,
    seen_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_nonce_cleanup ON request_nonce (seen_at);


-- ---------------------------------------------------------------------
--  webhook_event - Webhook 冪等閘門。見缺口 G1。
--
--  LINE 在 webhook 回應逾時或非 200 時會重送同一事件。有些操作天生
--  冪等（upsert user），有些不是（發歡迎訊息、發 enrollment token）。
--  處理器進入時先嘗試 INSERT，違反主鍵即代表已處理過。
-- ---------------------------------------------------------------------
CREATE TABLE webhook_event (
    webhook_event_id  VARCHAR(64) PRIMARY KEY,                 -- LINE 提供
    event_type        VARCHAR(32) NOT NULL,
    line_user_id      VARCHAR(64),
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_event_cleanup ON webhook_event (received_at);


-- ---------------------------------------------------------------------
--  audit_log - 稽核。見缺口 G8。
--
--  寫入失敗不得阻擋主流程。保留 2 年，不自動刪除。
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id           BIGSERIAL   PRIMARY KEY,
    actor_type   VARCHAR(16) NOT NULL,
    actor_id     VARCHAR(64),
    action       VARCHAR(48) NOT NULL,
    target_type  VARCHAR(32),
    target_id    VARCHAR(64),
    detail       JSONB,
    ip           INET,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_actor_type_chk CHECK (actor_type IN ('CLIENT', 'LINE_USER', 'SYSTEM'))
);

COMMENT ON COLUMN audit_log.detail IS
    '不同 action 要記的欄位差異大，強行正規化會產生大量 null 欄位。';

CREATE INDEX idx_audit_time   ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_target ON audit_log (target_type, target_id, occurred_at DESC);
CREATE INDEX idx_audit_action ON audit_log (action, occurred_at DESC);
