package com.jason.notifyline.monitor.secret;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MonitorSecretRepository extends JpaRepository<MonitorSecret, MonitorSecretId> {

    /**
     * 這個監控的全部 secret。複合主鍵只有一半（{@code monitorId}），不能用
     * {@code findById}，得用明確的 JPQL 依部分鍵查詢。
     */
    @Query("select s from MonitorSecret s where s.id.monitorId = :monitorId")
    List<MonitorSecret> findByMonitorId(@Param("monitorId") Long monitorId);
}
