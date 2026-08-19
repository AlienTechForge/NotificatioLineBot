package com.jason.notifyline.notification.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findByNotificationIdOrderByBatchNo(UUID notificationId);

    /**
     * 派送器取件。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 讓多個工作者可以<strong>完全並行</strong>取件而不
     * 互相阻塞 —— 每個工作者跳過已被別人鎖住的列。這意味著現在寫的邏輯在未來加開
     * 第二個實例時不需要任何改動。
     *
     * <p>{@code ORDER BY priority} 讓緊急告警不會被 1200 人的公告卡住（缺口 G10）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select d from NotificationDelivery d
            where d.status = com.jason.notifyline.notification.domain.DeliveryStatus.PENDING
              and d.nextAttemptAt <= :now
            order by d.priority, d.nextAttemptAt
            """)
    List<NotificationDelivery> lockPending(@Param("now") Instant now, Limit limit);

    @Query("""
            select count(d) from NotificationDelivery d
            where d.status = com.jason.notifyline.notification.domain.DeliveryStatus.PENDING
            """)
    long countPending();

    /**
     * 最舊的待送批次已經等了多久。
     *
     * <p>比「佇列深度」更能反映問題 —— 深度 100 但都是剛進來的沒事；
     * 深度 3 但最舊的等了 20 分鐘，代表卡住了。
     */
    @Query("""
            select min(d.nextAttemptAt) from NotificationDelivery d
            where d.status = com.jason.notifyline.notification.domain.DeliveryStatus.PENDING
            """)
    Instant findOldestPendingAt();

    /**
     * 一次 SQL 算出某個通知的完整結果。
     *
     * <p><strong>不可以改成在記憶體裡累加。</strong> 累加需要「發送全程一直持有
     * 正確的總數」，但派送是多執行緒的、會重試的、程序可能中途重啟 —— 任何累加器
     * 都會在某條路徑上算錯，而算錯的數字會直接寫進 {@code notification.success_count}
     * 成為對外的答案。
     *
     * <p>從明細重算則是冪等的：不管被呼叫幾次、由誰呼叫，答案都一樣。
     */
    @Query(value = """
            SELECT
                count(*) FILTER (WHERE status = 'PENDING')                          AS pending,
                coalesce(sum(recipient_count) FILTER (WHERE status = 'SENT'), 0)    AS success,
                coalesce(sum(recipient_count) FILTER (WHERE status = 'FAILED'), 0)  AS failure
            FROM notification_delivery
            WHERE notification_id = :notificationId
            """, nativeQuery = true)
    Tally tally(@Param("notificationId") UUID notificationId);

    /** {@link #tally} 的投影。 */
    interface Tally {
        long getPending();

        long getSuccess();

        long getFailure();
    }

    @Modifying
    @Query("delete from NotificationDelivery d where d.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") Instant threshold);
}
