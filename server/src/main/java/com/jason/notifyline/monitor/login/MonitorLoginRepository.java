package com.jason.notifyline.monitor.login;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * <p>刻意<strong>沒有</strong>悲觀鎖的查詢方法。第一版有一個
 * {@code findByIdForUpdate}，用來序列化同時到期的多個監控；但那需要交易橫跨整個
 * Cognito 呼叫，而那正是 ADR-0007 禁止的事（見 {@link MonitorLoginStore} 類別註解
 * 記錄的兩個實際後果）。序列化改由 {@link SiteLoginService} 的行程內鎖負責。
 */
public interface MonitorLoginRepository extends JpaRepository<MonitorLogin, Long> {

    Optional<MonitorLogin> findByName(String name);

    List<MonitorLogin> findAllByOrderByNameAsc();
}
