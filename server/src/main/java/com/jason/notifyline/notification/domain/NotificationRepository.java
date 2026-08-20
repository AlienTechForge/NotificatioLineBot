package com.jason.notifyline.notification.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** 冪等查詢。範圍是 (client, key)，不同 client 用相同 key 不衝突。 */
    Optional<Notification> findByClientIdAndIdempotencyKey(Long clientId, String idempotencyKey);

    /** 管理台的近期發送列表，最新在前。跨所有 client（管理者看得到全部）。 */
    List<Notification> findAllByOrderByCreatedAtDesc(Limit limit);

    /** 儀表板：指定時間之後的發送筆數。 */
    long countByCreatedAtGreaterThanEqual(Instant since);

    /** 儀表板：指定時間之後、某狀態的發送筆數。 */
    long countByStatusAndCreatedAtGreaterThanEqual(NotificationStatus status, Instant since);

    /**
     * 冪等寫入。<strong>不可以改用 {@code save()}。</strong>
     *
     * <p>{@code notification.id} 是應用程式自己產生的 UUID，所以 Spring Data 的
     * {@code isNew()} 會判定「不是新的」而走 {@code merge()} —— 那會先 SELECT 再
     * 決定 INSERT 或 UPDATE。兩個併發請求帶同一把 idempotency key 時，兩邊的 SELECT
     * 都查不到，於是兩邊都 INSERT，其中一邊撞上唯一索引拋
     * {@code DataIntegrityViolationException}，而該例外會把整個交易標成
     * rollback-only，連「回頭讀出既有那筆並回傳」都做不到。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 讓資料庫自己處理競賽：後到的那個請求
     * 拿到 0 rows，交易完好無損，可以直接回頭讀既有那筆。
     *
     * <p>{@code WHERE idempotency_key IS NOT NULL} 不可省 —— 唯一索引
     * {@code uq_notification_idem} 是 partial index，不附上相同的述詞
     * PostgreSQL 就推斷不出要用哪個索引，會直接報錯。
     *
     * @return 1 表示插入成功；0 表示該 (client, key) 已存在
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification (
                id, client_id, idempotency_key, request_id, target_type,
                payload, payload_hash, status,
                recipient_count, success_count, failure_count, created_at)
            VALUES (
                :id, :clientId, :idempotencyKey, :requestId, :targetType,
                CAST(:payload AS jsonb), :payloadHash, :status,
                :recipientCount, 0, 0, :createdAt)
            ON CONFLICT (client_id, idempotency_key) WHERE idempotency_key IS NOT NULL
            DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("clientId") Long clientId,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("requestId") String requestId,
                       @Param("targetType") String targetType,
                       @Param("payload") String payload,
                       @Param("payloadHash") byte[] payloadHash,
                       @Param("status") String status,
                       @Param("recipientCount") int recipientCount,
                       @Param("createdAt") Instant createdAt);

    /** 查詢 API 只能看自己的，否則回 404（不洩漏該 id 是否存在）。 */
    Optional<Notification> findByIdAndClientId(UUID id, Long clientId);

    /**
     * 卡在 SENDING 太久的通知 —— 派送中途程序被 kill 的訊號。
     * 對應的 delivery 仍是 PENDING，redriver 會撿回並最終回寫狀態。
     */
    List<Notification> findByStatusAndStartedAtBefore(NotificationStatus status, Instant threshold);

    /** 每日配額以「實際收件人數」計，不是請求數。 */
    @Query("""
            select coalesce(sum(n.recipientCount), 0) from Notification n
            where n.clientId = :clientId and n.createdAt >= :since
            """)
    long sumRecipientsSince(@Param("clientId") Long clientId, @Param("since") Instant since);

    @Modifying
    @Query("update Notification n set n.payload = null where n.createdAt < :threshold and n.payload is not null")
    int clearPayloadsBefore(@Param("threshold") Instant threshold);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") Instant threshold);
}
