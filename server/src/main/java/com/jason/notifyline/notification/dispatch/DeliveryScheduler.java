package com.jason.notifyline.notification.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 週期性把 outbox 裡的待送批次撿出來送。
 *
 * <p>這支排程是<strong>正確性的保證</strong>，不是效能優化：受理當下的即時派送
 * （{@link DispatchKicker}）只是為了降低延遲，它可能因為程序被 kill、執行緒池滿、
 * 或非同步任務拋例外而沒跑成。只要資料庫裡那一列還是 PENDING，這支排程就會把它撿回來。
 *
 * <p>所以<strong>永遠不要因為「即時派送已經夠快了」而把它關掉</strong>。
 */
@Component
@ConditionalOnProperty(name = "app.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);

    private final DeliveryDispatcher dispatcher;

    public DeliveryScheduler(DeliveryDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * {@code fixedDelay} 而非 {@code fixedRate}：間隔從「上一輪結束」起算。
     *
     * <p>{@code fixedRate} 在單輪耗時超過間隔時會讓工作堆積，而派送變慢的原因
     * 通常正是 LINE 端變慢 —— 那是最不該再加壓的時候。
     */
    @Scheduled(fixedDelayString = "${app.dispatch.poll-interval:PT10S}")
    public void poll() {
        try {
            dispatcher.runOnce();
        } catch (Exception e) {
            // 排程方法拋例外會讓 Spring 停掉這個排程的後續執行 ——
            // 那等於整個派送靜悄悄地永久停擺。一定要吞掉。
            log.error("派送輪次失敗，下一輪會再試", e);
        }
    }
}
