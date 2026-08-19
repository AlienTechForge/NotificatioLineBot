package com.jason.notifyline.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一批投遞。<strong>同時是 outbox 工作項</strong> —— 見
 * {@code Docs/plan/adr/0007-非同步採-DB-outbox-而非訊息中介.md}。
 *
 * <p>一列 = 一批（最多 500 人），不是一列一個收件人。LINE multicast 本來就是批次語意，
 * 個別收件人的成敗 LINE 也不會告訴我們，逐人一列只會浪費空間卻換不到資訊。
 */
@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery {

    /** 退避間隔：1、2、4、8、16 秒，之後放棄。 */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "batch_no", nullable = false)
    private int batchNo;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "line_user_ids", nullable = false)
    private String[] lineUserIds;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    /**
     * LINE 的冪等鍵。建立時就固定，<strong>所有重試沿用同一把</strong> ——
     * 這讓 LINE 端能識別出是重試而非新訊息，避免使用者收到重複通知。
     */
    @Column(name = "retry_key", nullable = false)
    private UUID retryKey;

    /** 0 高 … 9 低。取件時 ORDER BY priority, next_attempt_at。 */
    @Column(name = "priority", nullable = false)
    private short priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** LINE 回應的 x-line-request-id —— 與 LINE 客服對帳的唯一憑據，事後補不到。 */
    @Column(name = "line_request_id", length = 64)
    private String lineRequestId;

    @Column(name = "error_code", length = 48)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationDelivery() {
        // JPA
    }

    public NotificationDelivery(UUID notificationId,
                                int batchNo,
                                List<String> lineUserIds,
                                UUID retryKey,
                                short priority,
                                Instant now) {
        this.notificationId = notificationId;
        this.batchNo = batchNo;
        this.lineUserIds = lineUserIds.toArray(String[]::new);
        this.recipientCount = lineUserIds.size();
        this.retryKey = retryKey;
        this.priority = priority;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    // ------------------------------------------------------------- 狀態轉換

    public void markSent(String lineRequestId, Instant now) {
        this.status = DeliveryStatus.SENT;
        this.lineRequestId = lineRequestId;
        this.attemptCount++;
        this.sentAt = now;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /** 終局失敗：額度耗盡、憑證問題、請求格式錯誤。重試無用。 */
    public void markFailed(String errorCode, String errorMessage, Instant now) {
        this.status = DeliveryStatus.FAILED;
        this.attemptCount++;
        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage);
        this.sentAt = now;
    }

    /**
     * 暫時性失敗：排入重試，或用盡次數後標為失敗。
     *
     * @param jitterRatio ±20% 的抖動，避免大量批次在同一時刻同時重試造成尖峰
     */
    public void scheduleRetry(String errorCode, String errorMessage, Instant now, double jitterRatio) {
        this.attemptCount++;
        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage);

        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = DeliveryStatus.FAILED;
            this.sentAt = now;
            return;
        }
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = now.plus(backoff(attemptCount, jitterRatio));
    }

    /**
     * 取件租約：把 {@code next_attempt_at} 推到未來，讓下一輪取件跳過這一批。
     *
     * <p>沒有租約時，若一次 LINE 呼叫比取件間隔還久（重試 + 逾時很容易），
     * 下一輪就會取到同一批再送一次。{@code X-Line-Retry-Key} 相同讓 LINE 端不會
     * 重複發送，但我方會白白多打一次請求、多消耗一次速率配額，而且兩個執行緒
     * 同時回寫同一列的結果是不確定的。
     */
    public void lease(Instant until) {
        this.nextAttemptAt = until;
    }

    /**
     * 斷路器開路：延後但<strong>不遞增 attempt_count</strong>。
     *
     * <p>被斷路器擋下的呼叫根本沒送出去，不該算一次嘗試。若遞增，一次 LINE 端的
     * 長時間故障就會讓所有批次在斷路期間耗盡重試次數，變成永久失敗 ——
     * 那正是斷路器要防止的事。
     */
    public void deferForCircuitBreaker(Instant retryAt) {
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = retryAt;
    }

    /** 1、2、4、8 秒 …… 加上 ±20% 抖動。 */
    static Duration backoff(int attempt, double jitterRatio) {
        long base = 1L << Math.min(attempt - 1, 10);
        long millis = (long) (base * 1000 * (1.0 + jitterRatio));
        return Duration.ofMillis(Math.max(500, millis));
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    // ---------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public int getBatchNo() {
        return batchNo;
    }

    public List<String> getLineUserIds() {
        return List.of(lineUserIds);
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public UUID getRetryKey() {
        return retryKey;
    }

    public short getPriority() {
        return priority;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLineRequestId() {
        return lineRequestId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 不輸出收件人清單 —— 那是其他使用者的 LINE User ID。 */
    @Override
    public String toString() {
        return "NotificationDelivery[" + notificationId + "#" + batchNo
                + " status=" + status + " recipients=" + recipientCount
                + " attempts=" + attemptCount + "]";
    }
}
