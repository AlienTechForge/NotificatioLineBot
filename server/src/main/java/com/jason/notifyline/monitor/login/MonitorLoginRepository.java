package com.jason.notifyline.monitor.login;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MonitorLoginRepository extends JpaRepository<MonitorLogin, Long> {

    /**
     * 取用前先鎖住這一列（{@code SELECT ... FOR UPDATE}）。
     *
     * <p><strong>這不是可省略的謹慎</strong>：多個監控可能同時到期，沒有鎖就會有 N 個
     * 執行緒同時對同一組帳號發起登入。浪費之外，更實際的風險是對方把這看成暴力嘗試
     * 而鎖帳號。見 {@code Docs/plan/15-監控站台登入設計.md} §5。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from MonitorLogin l where l.id = :id")
    Optional<MonitorLogin> findByIdForUpdate(@Param("id") Long id);

    Optional<MonitorLogin> findByName(String name);

    List<MonitorLogin> findAllByOrderByNameAsc();
}
