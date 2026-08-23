package com.jason.notifyline.monitor;

import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.SeenItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * {@code api_monitor_run} / {@code api_monitor_seen_item} 的保留期清理。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §9（{@code run-retention} 預設 14 天、
 * {@code seen-item-retention} 預設 90 天）。排程與交易寫法照
 * {@code WebhookEventGuard.purgeExpired} 的既定慣例：bulk delete 走 repository 上的
 * {@code @Modifying @Query deleteByXBefore(Instant)}（見 {@code ApiMonitorRunRepository}
 * / {@code SeenItemRepository}），不逐筆撈出來刪。
 */
@Component
public class ApiMonitorSweeper {

    private static final Logger log = LoggerFactory.getLogger(ApiMonitorSweeper.class);

    private final ApiMonitorRunRepository runs;
    private final SeenItemRepository seenItems;
    private final MonitorProperties properties;
    private final Clock clock;

    public ApiMonitorSweeper(ApiMonitorRunRepository runs,
                             SeenItemRepository seenItems,
                             MonitorProperties properties,
                             Clock clock) {
        this.runs = runs;
        this.seenItems = seenItems;
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
        int deletedRuns = runs.deleteByStartedAtBefore(clock.instant().minus(properties.runRetention()));
        int deletedSeenItems = seenItems.deleteByFirstSeenAtBefore(clock.instant().minus(properties.seenItemRetention()));
        if (deletedRuns > 0 || deletedSeenItems > 0) {
            log.info("監控保留期清理完成：api_monitor_run={} 筆、api_monitor_seen_item={} 筆",
                    deletedRuns, deletedSeenItems);
        }
    }
}
