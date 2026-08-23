package com.jason.notifyline.monitor.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ApiMonitorRunRepository extends JpaRepository<ApiMonitorRun, Long> {

    /** 管理台「最近執行紀錄」查詢路徑（{@code idx_api_monitor_run_recent}），最新在前。 */
    List<ApiMonitorRun> findByMonitorIdOrderByStartedAtDesc(Long monitorId, Limit limit);

    /**
     * 保留期清理（{@code ApiMonitorSweeper}）。
     *
     * <p>照專案既定慣例（見 {@code WebhookEventRepository} / {@code RequestNonceRepository}）：
     * bulk delete 用 {@code @Modifying @Query} 而不是先查出來再逐筆刪，避免把整批要刪的資料
     * 都撈進記憶體。
     */
    @Modifying
    @Query("delete from ApiMonitorRun r where r.startedAt < :threshold")
    int deleteByStartedAtBefore(@Param("threshold") Instant threshold);
}
