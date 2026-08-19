package com.jason.notifyline.notification.dispatch;

import java.util.List;
import java.util.UUID;

/**
 * 已取件的一批工作。
 *
 * <p>刻意是<strong>不可變的快照</strong>而不是 JPA entity：送出動作發生在交易之外，
 * 手上若拿著 entity，任何一次 getter 都可能觸發 lazy loading 而炸出
 * {@code LazyInitializationException}，或者更糟 —— 在沒有交易的情況下開一條新連線。
 *
 * @param payload 完整的 LINE 請求範本，缺 {@code to}
 */
public record ClaimedBatch(
        long deliveryId,
        UUID notificationId,
        int batchNo,
        UUID retryKey,
        List<String> recipients,
        String payload) {

    public ClaimedBatch {
        recipients = List.copyOf(recipients);
    }
}
