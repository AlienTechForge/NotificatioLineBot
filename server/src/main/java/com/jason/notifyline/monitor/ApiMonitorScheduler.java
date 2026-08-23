package com.jason.notifyline.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 週期性把到期的監控撿出來跑一輪。見 {@code Docs/plan/11-API監控輪詢設計.md} §7、§9。
 *
 * <p>這支排程是{@link ApiMonitorRunner}<strong>唯一</strong>的觸發來源——不像
 * {@code DeliveryScheduler} 還有 {@code DispatchKicker} 的即時觸發當主要路徑、
 * 排程只是保底。監控輪詢沒有「受理當下」這個時間點可以即時觸發，所以
 * {@code app.monitor.poll-interval}（預設 10 秒）就是偵測到變更後最多會延遲通知的
 * 時間，不是最壞情況。
 */
@Component
@ConditionalOnProperty(name = "app.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class ApiMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApiMonitorScheduler.class);

    private final ApiMonitorRunner runner;

    public ApiMonitorScheduler(ApiMonitorRunner runner) {
        this.runner = runner;
    }

    /**
     * {@code fixedDelay} 而非 {@code fixedRate}：間隔從「上一輪結束」起算，理由同
     * {@code DeliveryScheduler.poll}——單輪耗時超過間隔時不該讓工作堆積，而監控
     * 輪詢變慢的原因往往正是某個第三方目標變慢，那是最不該再加壓的時候。
     */
    @Scheduled(fixedDelayString = "${app.monitor.poll-interval:PT10S}")
    public void poll() {
        try {
            runner.runOnce();
        } catch (Exception e) {
            // 排程方法拋例外會讓 Spring 停掉這個排程的後續執行——
            // 那等於整個監控功能靜悄悄地永久停擺。一定要吞掉。
            // 抄 DeliveryScheduler.poll() 的寫法（notification/dispatch/DeliveryScheduler.java）。
            log.error("監控輪詢失敗，下一輪會再試", e);
        }
    }
}
