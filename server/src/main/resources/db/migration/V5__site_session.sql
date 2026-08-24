-- =====================================================================
--  V5 - 站台登入狀態（cookie jar）
--
--  設計依據：Docs/plan/12-API監控易用性升級.md §3
--
--  cookie jar 範圍：依 host 自動共用 —— 同網域所有監控共用一份登入狀態，
--  過期重貼一次全部復活。不存帳號密碼、不做自動登入（見該文件 §3.5）：
--  每個站台的登入流程都不一樣（表單、OAuth、2FA、驗證碼），做不完也維護不動，
--  貼 cookie + 自動續期（Set-Cookie 合併回寫）已經覆蓋實際需求。
-- =====================================================================


-- ---------------------------------------------------------------------
--  site_session - 一個 host 一份登入狀態。
--
--  host 就是主鍵，不另外配序號 —— 這張表天生就是「依 host 查一筆」，沒有
--  「同一個 host 存在多筆」的情境，序號主鍵只會多一層無意義的間接。
-- ---------------------------------------------------------------------
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

COMMENT ON COLUMN site_session.host IS
    '正規化後當主鍵：IDN.toASCII + Locale.ROOT 小寫，與 OutboundUrlGuard 的正規化方式一致。'
    '附加 cookie 前的比對規則見 HostMatcher —— 請求 host 與這裡的 host 完全相等，或以'
    '「.」+ 這裡的 host 結尾（子網域）。絕不可用 endsWith(host) 這種不帶點號邊界的比對，'
    '那會讓 evil-example.com 誤配到 example.com 的 cookie，把 session token 洩漏給第三方。';
COMMENT ON COLUMN site_session.jar_ciphertext IS
    'AES-256-GCM，AAD = site_session:{host}，與 client.secret_ciphertext / '
    'api_monitor.headers_ciphertext 同一套金鑰管理。host 是主鍵，加密前一定已經存在，'
    '沒有 api_monitor header 那種要先 insert 拿 id 的順序陷阱。'
    '明文格式：{"cookieName": "value", ...}。';
COMMENT ON COLUMN site_session.jar_iv IS
    '12 bytes，每次回寫都重新產生 —— GCM 在相同金鑰下重用 IV 會直接洩漏明文。';
COMMENT ON COLUMN site_session.cookie_names IS
    '明文存 cookie 名稱（逗號分隔），純粹給後台顯示「這個站存了哪些 cookie」用。'
    '值絕不明文落地，也絕不透過任何 API 回傳。';
COMMENT ON COLUMN site_session.last_refreshed_at IS
    '這份 jar 最後一次被更新的時間 —— 使用者重新貼上 cookie（匯入），或抓取回應的'
    'Set-Cookie 合併回寫，兩種來源都算。null 只會出現在理論上的中間狀態，正常情況下'
    '建立當下就會寫一次。';
