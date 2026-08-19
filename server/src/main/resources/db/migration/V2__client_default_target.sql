-- ---------------------------------------------------------------------
--  Client 預設通知對象
--
--  問題：一個 service 若只想「通知擁有者」，現在每次請求都要自己帶 target。
--  更麻煩的是想指定特定收件人時，唯一的辦法是 target.type=USER，而那需要
--  notify:user scope —— 那個 scope 等於「能發給任何人」。於是只有兩種極端：
--  只能發給 owner，或能發給所有人，中間沒有東西。
--
--  解法：把「誰指定收件人」當成第一級概念。
--
--    管理者在這裡設定的預設對象 → 不需要 scope，授權行為本身就是管理者做的
--    呼叫端在請求裡自己指定的   → 需要對應 scope，維持原本的規則
--
--  這讓一個只被信任「發給預設對象」的 service，可以被管理者指向任意收件人
--  組合，卻拿不到「發給任何人」的能力。
-- ---------------------------------------------------------------------

ALTER TABLE client
    ADD COLUMN default_target_type     VARCHAR(16),
    ADD COLUMN default_target_user_ids TEXT[];

COMMENT ON COLUMN client.default_target_type IS
    '請求未帶 target 時使用。null = 沒設定，該 client 每次都必須自己指定 target。';
COMMENT ON COLUMN client.default_target_user_ids IS
    '僅 default_target_type = USER 時有值。由管理者指定，因此不需要 notify:user scope。';

ALTER TABLE client ADD CONSTRAINT client_default_target_type_chk CHECK (
    default_target_type IS NULL
    OR default_target_type IN ('SELF', 'OWNER', 'USER', 'ALL')
);

-- USER 一定要有名單，其餘型別一定不能有 —— 讓「型別與名單不一致」這種狀態
-- 在資料庫層就無法存在，而不是靠每個讀取端各自記得檢查。
ALTER TABLE client ADD CONSTRAINT client_default_target_users_chk CHECK (
    (
        default_target_type = 'USER'
        AND default_target_user_ids IS NOT NULL
        AND array_length(default_target_user_ids, 1) BETWEEN 1 AND 500
    )
    OR (
        default_target_type IS DISTINCT FROM 'USER'
        AND (
            default_target_user_ids IS NULL
            OR array_length(default_target_user_ids, 1) IS NULL
        )
    )
);

-- 回填：已經能發給 owner 的 client，預設就設成 OWNER。
-- 這是絕大多數 service 的實際用途，回填後它們可以立刻省略 target。
UPDATE client c
   SET default_target_type = 'OWNER',
       updated_at = now()
 WHERE default_target_type IS NULL
   AND EXISTS (
       SELECT 1 FROM client_scope s
        WHERE s.client_id = c.id
          AND s.scope = 'notify:owner'
   );
