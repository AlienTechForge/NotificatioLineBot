package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.config.DispatchProperties;
import com.jason.notifyline.notification.domain.Notification;
import com.jason.notifyline.notification.domain.NotificationDelivery;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 派送流程中所有<strong>需要交易</strong>的資料庫動作。
 *
 * <p>與 {@link DeliveryDispatcher} 分成兩個 bean 不是為了整潔，是<strong>必要的</strong>：
 * {@code @Transactional} 靠 Spring 的代理生效，同一個 bean 內部呼叫自己的方法
 * 不會經過代理，交易註解會安靜地失效。那種 bug 不會有任何錯誤訊息 ——
 * 只會在某次併發下出現兩批重複送出，然後查不出原因。
 *
 * <p>每個方法都是一段<strong>短</strong>交易，中間絕不夾任何外部 API 呼叫。
 */
@Service
public class DeliveryStore {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStore.class);

    /** 斷路器開路時的延後長度。 */
    private static final int DEFER_SECONDS = 30;

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final DispatchProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeliveryStore(NotificationDeliveryRepository deliveries,
                         NotificationRepository notifications,
                         DispatchProperties properties,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 取件並上租約。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 與租約解決的是<strong>不同</strong>問題：
     * 前者管「兩個工作者同時取件」，後者管「這一輪還沒回寫、下一輪又取到同一批」。
     * 只做其中一個都不夠。
     */
    @Transactional
    public List<ClaimedBatch> claim(int limit) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.lease());

        List<NotificationDelivery> rows = deliveries.lockPending(now, Limit.of(limit));
        List<ClaimedBatch> claimed = new ArrayList<>(rows.size());

        for (NotificationDelivery row : rows) {
            Optional<Notification> notification = notifications.findById(row.getNotificationId());
            if (notification.isEmpty() || notification.get().getPayload() == null) {
                // payload 被保留期清理掉了，或通知已被刪除。沒有內容可送，
                // 標為失敗好過讓這一列永遠 PENDING 被反覆取出又反覆放棄。
                row.markFailed("PAYLOAD_GONE", "Notification payload is no longer available.", now);
                log.warn("批次沒有可送的內容，標記失敗：deliveryId={}", row.getId());
                continue;
            }
            notification.get().markSending(now);
            row.lease(leaseUntil);

            claimed.add(new ClaimedBatch(
                    row.getId(),
                    row.getNotificationId(),
                    row.getBatchNo(),
                    row.getRetryKey(),
                    row.getLineUserIds(),
                    notification.get().getPayload()));
        }
        return claimed;
    }

    /** 把一次 LINE 呼叫的結果寫回，並在所有批次都結束時算出通知的最終狀態。 */
    @Transactional
    public void writeBack(long deliveryId, SendOutcome outcome) {
        NotificationDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null) {
            log.warn("回寫時找不到批次，可能已被保留期清理：deliveryId={}", deliveryId);
            return;
        }
        Instant now = clock.instant();

        switch (outcome.kind()) {
            case SENT -> {
                delivery.markSent(outcome.lineRequestId(), now);
                log.info("批次送出：lineRequestId={} recipients={}",
                        outcome.lineRequestId(), delivery.getRecipientCount());
            }
            case FATAL -> {
                delivery.markFailed(outcome.errorCode(), outcome.errorMessage(), now);
                log.warn("批次終局失敗，不重試：code={} message={}",
                        outcome.errorCode(), outcome.errorMessage());
            }
            case RETRY -> {
                delivery.scheduleRetry(outcome.errorCode(), outcome.errorMessage(), now, jitter());
                log.warn("批次將重試：code={} attempt={} nextAt={}",
                        outcome.errorCode(), delivery.getAttemptCount(), delivery.getNextAttemptAt());
            }
            case DEFERRED -> {
                // 根本沒送出去，attempt_count 不動
                delivery.deferForCircuitBreaker(now.plusSeconds(DEFER_SECONDS));
                log.info("批次延後（未送出）：code={}", outcome.errorCode());
            }
        }

        finalise(delivery.getNotificationId(), now);
    }

    /**
     * 所有批次都不是 PENDING 了，就從明細重算最終狀態。
     *
     * <p>每次回寫都算一次，而不是指定「最後一批負責算」—— 誰是最後一批取決於
     * 執行緒排程，而且程序中途重啟後就沒有人是最後一批了。重算是冪等的。
     */
    private void finalise(UUID notificationId, Instant now) {
        var tally = deliveries.tally(notificationId);
        if (tally == null || tally.getPending() > 0) {
            return;
        }
        notifications.findById(notificationId).ifPresent(notification -> {
            if (!notification.getStatus().isTerminal()) {
                notification.complete((int) tally.getSuccess(), (int) tally.getFailure(), now);
                log.info("通知完成：notificationId={} status={} success={} failure={}",
                        notificationId, notification.getStatus(),
                        tally.getSuccess(), tally.getFailure());
                applyPayloadRetention(notification);
            }
        });
    }

    /**
     * {@code persistPayload=false} 的通知在送完後立刻清空內容。
     *
     * <p>受理當下就不存內容<strong>是行不通的</strong>：重試需要內容，redriver 需要
     * 內容，程序重啟後撿回來的批次也需要內容。真的不存，這個選項就從「不留紀錄」
     * 悄悄變成「重試一律失敗」—— 一個宣稱保護隱私的旗標實際上是在丟訊息。
     *
     * <p>所以界線畫在「送完就清」：內容在資料庫裡的存活時間是一次發送的長度，
     * 而不是保留期的 90 天。
     */
    private void applyPayloadRetention(Notification notification) {
        String payload = notification.getPayload();
        if (payload == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = objectMapper.readValue(payload, Map.class);
            if (!PayloadEnvelope.persistPayload(envelope)) {
                notification.clearPayload();
            }
        } catch (Exception e) {
            // 讀不懂就留著。清錯了無法復原，留著只是多佔一點空間。
            log.warn("無法判讀 payload 的保存旗標，保留內容：notificationId={}",
                    notification.getId(), e);
        }
    }

    /** ±20%，避免大量批次在同一毫秒一起重試造成尖峰。 */
    private static double jitter() {
        return ThreadLocalRandom.current().nextDouble(-0.2, 0.2);
    }
}
