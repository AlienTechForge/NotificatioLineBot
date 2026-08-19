package com.jason.notifyline.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 已處理過的 webhook 事件。見缺口 G1。
 *
 * <p>LINE 在 webhook 回應逾時或非 200 時會重送同一事件，帶相同的
 * {@code webhookEventId}。
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent {

    @Id
    @Column(name = "webhook_event_id", length = 64)
    private String webhookEventId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "line_user_id", length = 64)
    private String lineUserId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebhookEvent() {
        // JPA
    }

    public WebhookEvent(String webhookEventId, String eventType, String lineUserId, Instant receivedAt) {
        this.webhookEventId = webhookEventId;
        this.eventType = eventType;
        this.lineUserId = lineUserId;
        this.receivedAt = receivedAt;
    }

    public String getWebhookEventId() {
        return webhookEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getLineUserId() {
        return lineUserId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
