package com.jason.notifyline.monitor.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ApiMonitorRepository extends JpaRepository<ApiMonitor, Long> {

    /**
     * 排程器取件。抄 {@code NotificationDeliveryRepository.lockPending} 的做法。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}（{@code lock.timeout = -2}）讓多個工作者可以
     * 完全並行取件而不互相阻塞——每個工作者跳過已被別人鎖住的列。重啟不掉單、
     * 多實例不重複打同一個目標 API。
     *
     * <p>對應 {@code idx_api_monitor_due}（{@code next_run_at WHERE enabled}）。
     * 呼叫端必須在同一個交易裡把 {@code next_run_at} 推到 {@code now + lease}
     * 當租約，否則下一輪取件會撿到同一筆再打一次。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select m from ApiMonitor m
            where m.enabled = true
              and m.nextRunAt <= :now
            order by m.nextRunAt
            """)
    List<ApiMonitor> lockDue(@Param("now") Instant now, Limit limit);
}
