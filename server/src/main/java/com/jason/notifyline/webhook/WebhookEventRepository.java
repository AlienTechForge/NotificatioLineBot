package com.jason.notifyline.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    /**
     * 原子地「若不存在才插入」。
     *
     * <p>與 {@code RequestNonceRepository.insertIfAbsent} 同一個理由：主鍵是外部
     * 指定的字串，{@code save()} 會走 merge 而不會違反約束，重送就會被靜默放行。
     *
     * @return 1 = 首次收到，應該處理；0 = 重送，直接略過
     */
    @Modifying
    @Query(value = """
            INSERT INTO webhook_event (webhook_event_id, event_type, line_user_id, received_at)
            VALUES (:eventId, :eventType, :lineUserId, :receivedAt)
            ON CONFLICT (webhook_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("eventType") String eventType,
                       @Param("lineUserId") String lineUserId,
                       @Param("receivedAt") Instant receivedAt);

    @Modifying
    @Query("delete from WebhookEvent e where e.receivedAt < :threshold")
    int deleteByReceivedAtBefore(@Param("threshold") Instant threshold);
}
