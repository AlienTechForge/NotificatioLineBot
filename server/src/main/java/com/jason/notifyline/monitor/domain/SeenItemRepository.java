package com.jason.notifyline.monitor.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SeenItemRepository extends JpaRepository<SeenItem, SeenItemId> {

    /**
     * 保留期清理（{@code ApiMonitorSweeper}）。理由同
     * {@code ApiMonitorRunRepository.deleteByStartedAtBefore}。
     */
    @Modifying
    @Query("delete from SeenItem s where s.firstSeenAt < :threshold")
    int deleteByFirstSeenAtBefore(@Param("threshold") Instant threshold);
}
