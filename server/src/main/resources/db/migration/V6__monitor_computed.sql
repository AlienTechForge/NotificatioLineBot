-- =====================================================================
--  V6 - 監控計算欄位（簽章支援）
--
--  設計依據：Docs/plan/13-監控計算欄位設計.md
--
--  讓每個監控自己定義「怎麼算出簽章」，不把任何站台的演算法寫死在程式碼裡。
--  規格是從一個真實站台反推出來的：
--    sign = MD5( MD5( SECRET + timestamp + DEVICE_ID ).toUpperCase() ).toUpperCase()
--  兩個發現決定了下面的資料形狀：
--    1. 雙重 MD5 ── 雜湊必須能串接（steps 是陣列），不能只做一次。
--    2. 中間那次也要轉大寫 ── 大寫發生在第一次雜湊之後、第二次之前，會改變
--       第二次的輸入。所以 encoding 是每一段（step）各自的屬性，不是整體
--       最後才套用一次。
-- =====================================================================


-- ---------------------------------------------------------------------
--  monitor_secret - 監控專屬的機敏常數（appsecret、device id 之類）。
--
--  獨立成一張表，不塞進 api_monitor.headers_ciphertext：headers 是「要送出去
--  的東西」，secret 是「用來算出要送出去的東西的原料」——同一個 secret 可能
--  同時被多個 computed_fields 引用，也可能完全不出現在最終送出的 header／URL
--  裡（例如只當 HMAC 的金鑰）。
-- ---------------------------------------------------------------------
CREATE TABLE monitor_secret (
    monitor_id   BIGINT       NOT NULL REFERENCES api_monitor (id) ON DELETE CASCADE,
    name         VARCHAR(32)  NOT NULL,
    ciphertext   BYTEA        NOT NULL,
    iv           BYTEA        NOT NULL,
    key_version  INT          NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (monitor_id, name),
    CONSTRAINT monitor_secret_name_chk CHECK (name ~ '^[A-Za-z0-9_]{1,32}$')
);

COMMENT ON COLUMN monitor_secret.ciphertext IS
    'AES-256-GCM，AAD = "monitor_secret:" + monitor_id + ":" + name。值絕不回傳到任何 '
    'DTO、log、api_monitor_run 或 LINE 訊息，後台只顯示名稱與「已設定」。';
COMMENT ON COLUMN monitor_secret.name IS
    '模板用 {{secret.NAME}} 引用，或當 HMAC 步驟的 keySecret。限 [A-Za-z0-9_]{1,32}，'
    '與 api_monitor.extract_rules[].name 同一套字元限制。';


-- ---------------------------------------------------------------------
--  api_monitor.computed_fields - 依序求值的計算欄位（JSONB，新增欄位）。
--
--  格式：
--  [
--    {
--      "name": "sign",
--      "input": "{{secret.appsecret}}{{now.epochSeconds}}{{secret.deviceid}}",
--      "steps": [
--        { "algorithm": "MD5", "encoding": "HEX_UPPER" },
--        { "algorithm": "MD5", "encoding": "HEX_UPPER" }
--      ]
--    }
--  ]
--
--  steps 依序套用，前一段的輸出字串就是後一段的輸入——這是雙重雜湊的關鍵，
--  也是為什麼 encoding 掛在每一段而不是整體（見本檔案開頭的說明）。
--
--  演算法：MD5 / SHA1 / SHA256 / SHA512 / HMAC_SHA1 / HMAC_SHA256
--  （HMAC 另帶 keySecret 指定用哪個 monitor_secret 當金鑰）。
--  編碼：HEX_UPPER / HEX_LOWER / BASE64。
--
--  求值可引用 {{secret.*}}、{{now.*}}、{{uuid}}，以及陣列中前面已定義的
--  {{computed.*}}——不可前向引用、不可自我引用，存檔時就要偵測並回 400，
--  不能等到輪詢才發現。
-- ---------------------------------------------------------------------
ALTER TABLE api_monitor ADD COLUMN computed_fields JSONB NOT NULL DEFAULT '[]';

COMMENT ON COLUMN api_monitor.computed_fields IS
    '依序求值的計算欄位，格式見本檔案上方註解。求值順序：凍結 Instant（與 URL/headers/'
    'body 共用同一瞬間）→ 依序求值 computed_fields → 套用到 URL/headers/body → 替換後'
    '的 URL 再過一次 OutboundUrlGuard。前向引用／自我引用在存檔時就拒絕（400）。';
