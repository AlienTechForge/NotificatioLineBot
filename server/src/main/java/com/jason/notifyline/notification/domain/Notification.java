package com.jason.notifyline.notification.domain;

import com.jason.notifyline.common.TargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次發送請求。schema 見 {@code Docs/plan/04-資料模型.md} §5。
 *
 * <p>主鍵用 UUID 而非序號 —— {@code notificationId} 會回傳給呼叫端，
 * 序號會洩漏系統總量與成長速率。
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    /** correlation id，串起同步段與非同步段的日誌。 */
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private TargetType targetType;

    /**
     * 送出的 LINE message objects。
     *
     * <p>可為 null —— 呼叫端指定 {@code persistPayload=false}，或已過 90 天保留期被清空。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    /** payload 清空後仍保留，供冪等比對與日後的去重功能。 */
    @Column(name = "payload_hash", nullable = false)
    private byte[] payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status;

    /**
     * 以下三個計數是<strong>去正規化</strong>的。真相在 {@code notification_delivery}，
     * 這裡冗餘是為了讓明細過了 90 天保留期被刪除後，統計仍然查得到。
     */
    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Notification() {
        // JPA
    }

    public Notification(UUID id,
                        Long clientId,
                        String idempotencyKey,
                        String requestId,
                        TargetType targetType,
                        String payload,
                        byte[] payloadHash,
                        int recipientCount,
                        Instant now) {
        this.id = id;
        this.clientId = clientId;
        this.idempotencyKey = idempotencyKey;
        this.requestId = requestId;
        this.targetType = targetType;
        this.payload = payload;
        this.payloadHash = payloadHash.clone();
        this.recipientCount = recipientCount;
        this.status = NotificationStatus.QUEUED;
        this.createdAt = now;
    }

    // ------------------------------------------------------------- 狀態轉換

    public void markSending(Instant now) {
        if (status == NotificationStatus.QUEUED) {
            this.status = NotificationStatus.SENDING;
            this.startedAt = now;
        }
    }

    /**
     * 依各批次的結果決定最終狀態。
     *
     * <p>計數以<strong>收件人數</strong>加總，不是批次數 —— 呼叫端關心的是幾個人收到。
     */
    public void complete(int success, int failure, Instant now) {
        this.successCount = success;
        this.failureCount = failure;
        this.status = failure == 0
                ? NotificationStatus.SUCCEEDED
                : (success == 0 ? NotificationStatus.FAILED : NotificationStatus.PARTIAL);
        this.finishedAt = now;
    }

    /** 過了保留期後清空內容，只留 metadata 與 hash。 */
    public void clearPayload() {
        this.payload = null;
    }

    // ---------------------------------------------------------------- getters

    public UUID getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestId() {
        return requestId;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public String getPayload() {
        return payload;
    }

    public byte[] getPayloadHash() {
        return payloadHash.clone();
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** 不輸出 payload —— 內容可能含呼叫端誤送的機密。 */
    @Override
    public String toString() {
        return "Notification[" + id + " target=" + targetType + " status=" + status
                + " recipients=" + recipientCount + "]";
    }
}
