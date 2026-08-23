package com.jason.notifyline.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * {@code api_monitor_run} / {@code api_monitor_seen_item} 的保留期清理。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §9（{@code run-retention} 預設 14 天、
 * {@code seen-item-retention} 預設 90 天）。排程與交易寫法照
 * {@code WebhookEventGuard.purgeExpired} 的既定慣例。
 *
 * <h2>為什麼用 {@link JdbcTemplate} 而不是 repository 的 {@code deleteByXBefore}</h2>
 *
 * <p>這個專案清過期資料的既定慣例是在 repository 介面上加
 * {@code @Modifying @Query(...) int deleteByXBefore(Instant threshold)}
 * （見 {@code WebhookEventRepository} / {@code RequestNonceRepository} /
 * {@code NotificationRepository}）。本波次的協作邊界明確排除修改
 * {@code monitor/domain} 底下的既有檔案（含 {@code ApiMonitorRunRepository} /
 * {@code SeenItemRepository}），所以這裡改用 {@link JdbcTemplate} 直接下
 * bulk delete，不新增 repository 方法也能達到同樣效果。<strong>這是與既定慣例的
 * 刻意偏離</strong>——如果之後某一波次要修這兩個 repository，把這裡改回
 * {@code deleteByXBefore} 會更貼近專案風格。
 */
@Component
public class ApiMonitorSweeper {

    private static final Logger log = LoggerFactory.getLogger(ApiMonitorSweeper.class);

    private final JdbcTemplate jdbcTemplate;
    private final MonitorProperties properties;
    private final Clock clock;

    public ApiMonitorSweeper(JdbcTemplate jdbcTemplate, MonitorProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 多實例注意：這個排程在多實例下會重複執行，理由與取捨照
     * {@code WebhookEventGuard.purgeExpired} 的註解——重複刪除是冪等的，只是浪費
     * 資源，加第二個實例前需導入 ShedLock。
     */
    @Scheduled(cron = "0 20 3 * * *")
    public void sweep() {
        try {
            runOnce();
        } catch (Exception e) {
            // 排程方法拋例外會讓 Spring 停掉後續執行——保留期清理就會靜悄悄永久停擺，
            // 資料表只增不減。理由與寫法同 ApiMonitorScheduler.poll。
            log.error("監控保留期清理失敗，下一輪會再試", e);
        }
    }

    @Transactional
    public void runOnce() {
        int deletedRuns = deleteExpiredRuns(clock.instant().minus(properties.runRetention()));
        int deletedSeenItems = deleteExpiredSeenItems(clock.instant().minus(properties.seenItemRetention()));
        if (deletedRuns > 0 || deletedSeenItems > 0) {
            log.info("監控保留期清理完成：api_monitor_run={} 筆、api_monitor_seen_item={} 筆",
                    deletedRuns, deletedSeenItems);
        }
    }

    private int deleteExpiredRuns(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM api_monitor_run WHERE started_at < ?", Timestamp.from(cutoff));
    }

    private int deleteExpiredSeenItems(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM api_monitor_seen_item WHERE first_seen_at < ?", Timestamp.from(cutoff));
    }
}
