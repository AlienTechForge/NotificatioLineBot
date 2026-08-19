package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.common.RequestContext;
import com.jason.notifyline.config.DispatchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 取件 → 呼叫 LINE → 回寫。
 *
 * <h2>為什麼這個類別完全沒有 {@code @Transactional}</h2>
 *
 * <p><strong>交易絕不可以撐過 LINE API 呼叫。</strong> Hikari 只有 10 條連線，
 * 一次 multicast 在 LINE 端變慢時可以卡上好幾秒。把連線握在手上等外部服務，
 * 結果是 LINE 一慢連線池就被耗盡，接著<strong>連健康檢查都回不了</strong> ——
 * 監控看到的是「我們的服務掛了」，而真正的原因是「LINE 慢了」。
 *
 * <p>所以分三段：① {@link DeliveryStore#claim} 短交易取快照 →
 * ② 這裡做無交易的網路呼叫 → ③ {@link DeliveryStore#writeBack} 短交易回寫。
 */
@Service
public class DeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private final DeliveryStore store;
    private final LineMulticastClient lineClient;
    private final DispatchProperties properties;

    public DeliveryDispatcher(DeliveryStore store,
                              LineMulticastClient lineClient,
                              DispatchProperties properties) {
        this.store = store;
        this.lineClient = lineClient;
        this.properties = properties;
    }

    /**
     * 跑一輪。
     *
     * @return 這一輪處理的批次數；0 代表沒有待送工作
     */
    public int runOnce() {
        List<ClaimedBatch> claimed = store.claim(properties.batchLimit());
        for (ClaimedBatch batch : claimed) {
            process(batch);
        }
        if (!claimed.isEmpty()) {
            log.debug("派送一輪完成：batches={}", claimed.size());
        }
        return claimed.size();
    }

    /** 送出單一批次並回寫結果。 */
    public void process(ClaimedBatch batch) {
        MDC.put(RequestContext.NOTIFICATION_ID, batch.notificationId().toString());
        MDC.put(RequestContext.BATCH_NO, String.valueOf(batch.batchNo()));
        try {
            SendOutcome outcome =
                    lineClient.multicast(batch.recipients(), batch.payload(), batch.retryKey());
            store.writeBack(batch.deliveryId(), outcome);

        } catch (Exception e) {
            // 回寫本身失敗（例如資料庫瞬斷）時不能讓例外冒上去中斷整輪。
            // 這一批的租約會到期，下一輪自然會重新取到它 —— outbox 的意義就在這裡。
            log.error("批次處理失敗，將於租約到期後重試：deliveryId={}", batch.deliveryId(), e);

        } finally {
            MDC.remove(RequestContext.NOTIFICATION_ID);
            MDC.remove(RequestContext.BATCH_NO);
        }
    }
}
