package com.jason.notifyline.notification.api;

import com.jason.notifyline.notification.domain.Notification;

import java.util.UUID;

/**
 * {@code POST /api/v1/notifications} 的 202 回應。
 *
 * <p><strong>202 代表「已受理並排入佇列」，不是「已送達」。</strong>
 * 實際結果用 {@code GET /api/v1/notifications/{id}} 查。
 */
public record NotificationAccepted(
        UUID notificationId,
        String status,
        int recipientCount,
        int batchCount) {

    public static NotificationAccepted from(Notification notification, int batchCount) {
        return new NotificationAccepted(
                notification.getId(),
                notification.getStatus().name(),
                notification.getRecipientCount(),
                batchCount);
    }
}
