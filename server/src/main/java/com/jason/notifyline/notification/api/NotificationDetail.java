package com.jason.notifyline.notification.api;

import com.jason.notifyline.notification.domain.Notification;
import com.jason.notifyline.notification.domain.NotificationDelivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/notifications/{id}} 的回應。
 *
 * <p>刻意<strong>不</strong>回傳收件人清單 —— 那是其他使用者的 LINE User ID。
 */
public record NotificationDetail(
        UUID notificationId,
        String targetType,
        String status,
        int recipientCount,
        int successCount,
        int failureCount,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<Batch> batches) {

    public static NotificationDetail from(Notification n, List<NotificationDelivery> deliveries) {
        return new NotificationDetail(
                n.getId(),
                n.getTargetType().name(),
                n.getStatus().name(),
                n.getRecipientCount(),
                n.getSuccessCount(),
                n.getFailureCount(),
                n.getCreatedAt(),
                n.getStartedAt(),
                n.getFinishedAt(),
                deliveries.stream().map(Batch::from).toList());
    }

    /**
     * @param lineRequestId LINE 回應的 x-line-request-id，與 LINE 客服對帳用
     * @param errorCode     失敗時的分類碼，不含內部細節
     */
    public record Batch(
            int batchNo,
            int recipientCount,
            String status,
            int attemptCount,
            String lineRequestId,
            String errorCode,
            Instant sentAt) {

        static Batch from(NotificationDelivery d) {
            return new Batch(
                    d.getBatchNo(),
                    d.getRecipientCount(),
                    d.getStatus().name(),
                    d.getAttemptCount(),
                    d.getLineRequestId(),
                    d.getErrorCode(),
                    d.getSentAt());
        }
    }
}
