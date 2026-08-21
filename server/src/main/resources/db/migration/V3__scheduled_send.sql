-- ---------------------------------------------------------------------
--  排程發送
--
--  刻意不新增一條「排程派送」路徑。既有的 outbox 模型本來就是
--  「PENDING 且 next_attempt_at <= now() 才會被取件」，把首次嘗試的
--  next_attempt_at 直接設成排程時間，dispatcher 的取件邏輯完全不用改。
--
--  scheduled_at 只存在 notification（不在 notification_delivery），純粹是
--  給管理台查詢「有哪些排程還沒發」與「取消排程」用；真正驅動派送時機的
--  仍是 notification_delivery.next_attempt_at，兩者在建立時寫成同一個值，
--  之後不會再同步 —— 例如批次重試會改自己的 next_attempt_at，但那不代表
--  排程本身變了。
-- ---------------------------------------------------------------------

ALTER TABLE notification
    ADD COLUMN scheduled_at TIMESTAMPTZ;

COMMENT ON COLUMN notification.scheduled_at IS
    'null = 立即發送。非 null 代表要等到這個時間點才由派送器取件；'
    '建立時所有批次的 next_attempt_at 會設成同一個值。';

-- 管理台「排程」頁的唯一查詢路徑：
--   WHERE scheduled_at IS NOT NULL AND status = 'QUEUED'
--   ORDER BY scheduled_at
CREATE INDEX idx_notification_scheduled
    ON notification (scheduled_at)
    WHERE scheduled_at IS NOT NULL AND status = 'QUEUED';
