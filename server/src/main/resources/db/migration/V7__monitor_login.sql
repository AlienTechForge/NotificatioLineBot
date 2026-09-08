-- =====================================================================
--  V7 - 監控站台登入
--
--  設計依據：Docs/plan/15-監控站台登入設計.md
--
--  現況的問題：monitor.session 的 cookie jar 只接受使用者主動貼上的 cookie，
--  而真實遇到的站台用的是 `Authorization: <token>`，token 來自 AWS Cognito
--  且只活一小時。cookie jar 幫不上忙，監控最多撐一小時就全部 401。
--
--  這張表讓監控可以「自己去登入」：存帳密，輪詢前換到有效 token 再注入 header。
-- =====================================================================


-- ---------------------------------------------------------------------
--  monitor_login - 一組站台登入憑證 + token 快取。
--
--  獨立成一張表而不是 api_monitor 的欄位，有兩個理由：
--
--    1. 一組帳號會服務多個監控（同一個學生可能同時監控狀態、文件、訊息）。
--       共用一份 token 快取代表登入一次、多個監控共用，而不是每個監控各登
--       一次 —— 對方會看到 N 倍登入次數，也更容易觸發帳號鎖定。
--
--    2. token 快取是「系統寫回的狀態」，帳密是「使用者填的設定」。塞進
--       api_monitor.headers_ciphertext 會讓兩者混在同一個欄位，後台編輯
--       一存檔就把快取蓋掉。
-- ---------------------------------------------------------------------
CREATE TABLE monitor_login (
    id                    BIGSERIAL    PRIMARY KEY,
    name                  VARCHAR(100) NOT NULL UNIQUE,
    type                  VARCHAR(32)  NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,

    config                JSONB        NOT NULL,
    username              VARCHAR(320) NOT NULL,

    -- 刻意可為 NULL，理由與 api_monitor.headers_ciphertext 完全相同：AAD 含 id，
    -- 而 id 是 BIGSERIAL —— 必須先 insert 拿到 id 才能加密。NOT NULL 會讓那第一次
    -- insert 直接違反約束，兩段式儲存根本走不完。
    --
    -- 代價是「有一列但沒有密碼」在資料庫層是可表示的狀態。這由應用層收斂：
    -- MonitorLoginAdminService.create 在同一個交易內完成兩次寫入，
    -- SiteLoginService 解密前會檢查並拋出明確的設定錯誤。
    password_ciphertext   BYTEA,
    password_iv           BYTEA,
    password_key_version  INT,

    refresh_ciphertext    BYTEA,
    refresh_iv            BYTEA,
    refresh_key_version   INT,

    token_ciphertext      BYTEA,
    token_iv              BYTEA,
    token_key_version     INT,
    token_expires_at      TIMESTAMPTZ,

    header_name           VARCHAR(64)  NOT NULL DEFAULT 'Authorization',
    header_value_template VARCHAR(200) NOT NULL DEFAULT '{token}',

    last_login_at         TIMESTAMPTZ,
    last_error            VARCHAR(200),
    consecutive_failures  INT          NOT NULL DEFAULT 0,

    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT monitor_login_type_chk
        CHECK (type IN ('COGNITO_SRP')),

    -- RFC 7230 的 header 名稱其實允許更多字元，這裡刻意收得更緊：我們只需要
    -- 支援真實會用到的 header（Authorization、X-Auth-Token 之類），放寬只會
    -- 讓「使用者填了什麼」變成需要另外驗證的東西。
    CONSTRAINT monitor_login_header_chk
        CHECK (header_name ~ '^[A-Za-z0-9-]{1,64}$'),

    -- 模板一定要真的用到 token，否則這筆登入設定不會有任何效果，而使用者會
    -- 以為它生效了 —— 讓這種狀態在資料庫層就無法存在。
    CONSTRAINT monitor_login_template_chk
        CHECK (header_value_template LIKE '%{token}%')
);

COMMENT ON TABLE monitor_login IS
    '站台登入憑證與 token 快取。見 Docs/plan/15-監控站台登入設計.md。';

COMMENT ON COLUMN monitor_login.type IS
    'provider 種類。目前只有 COGNITO_SRP（AWS Cognito user pool 的 SRP 流程）。'
    '刻意用字串而非 boolean：之後要加別的站台登入方式時不必改 schema。';

COMMENT ON COLUMN monitor_login.config IS
    'provider 專屬的非機密設定。COGNITO_SRP：{"region":..., "userPoolId":..., "clientId":...}。'
    '機密（密碼、token）一律在下方的加密欄位，絕不放這裡。';

COMMENT ON COLUMN monitor_login.password_ciphertext IS
    'AES-256-GCM，AAD = "monitor_login:" + id + ":password"。'
    '值絕不回傳到任何 DTO、log、api_monitor_run 或 LINE 訊息，後台只顯示「已設定」。'
    'AAD 陷阱：id 是 BIGSERIAL，第一次 save() 之後才存在，呼叫端必須先 insert '
    '拿到 id、用該 id 加密、再回寫 —— 與 ApiMonitor.applyHeaders 相同的順序要求。';

COMMENT ON COLUMN monitor_login.refresh_ciphertext IS
    'AES-256-GCM，AAD 尾綴 ":refresh"。快取用：有它就不必每次都跑完整 SRP。'
    'Cognito 的 refresh token 預設 30 天，過期後自動退回完整登入，使用者無感。';

COMMENT ON COLUMN monitor_login.token_ciphertext IS
    'AES-256-GCM，AAD 尾綴 ":token"。實際注入 header 的短效 token。';

COMMENT ON COLUMN monitor_login.token_expires_at IS
    '從 JWT 的 exp claim 解析而來（不驗章 —— 這裡只決定快取何時失效，'
    '簽章由目標 API 自己驗）。解析失敗時退化為「現在 + 50 分鐘」，寧可早一點重登。';

COMMENT ON COLUMN monitor_login.header_value_template IS
    '注入格式，{token} 會被換成實際 token。存在的理由：有些站台送裸 token '
    '（無 Bearer 前綴），有些要 "Bearer {token}"。寫死任一種都會對另一種是錯的。';

COMMENT ON COLUMN monitor_login.enabled IS
    '密碼錯誤時會被自動設為 false —— Cognito 對連續失敗會鎖帳號，退避重試只會'
    '把使用者自己的帳號鎖死。見設計文件 §5.1。';

COMMENT ON COLUMN monitor_login.last_error IS
    '最近一次登入失敗的原因，給後台顯示。只存錯誤類型與簡短說明，'
    '絕不含帳密或 token 片段。';


-- ---------------------------------------------------------------------
--  api_monitor.login_id - 這個監控要用哪組登入（NULL = 不需要登入）。
--
--  ON DELETE SET NULL 而非 CASCADE：刪掉登入設定不該連帶刪掉監控本身 ——
--  使用者想做的是「這個監控改成不需要登入」或「換一組帳號」，不是「把監控丟掉」。
-- ---------------------------------------------------------------------
ALTER TABLE api_monitor
    ADD COLUMN login_id BIGINT REFERENCES monitor_login (id) ON DELETE SET NULL;

COMMENT ON COLUMN api_monitor.login_id IS
    '引用的 monitor_login。非 NULL 時，ApiMonitorRunner 會在 renderHeaders() 之後、'
    'ApiFetcher 之前把 token 寫進指定 header —— 刻意不經過 RequestTemplate，'
    '理由見該類別註解「封閉集合是安全邊界」。';

CREATE INDEX idx_api_monitor_login ON api_monitor (login_id) WHERE login_id IS NOT NULL;
