package com.jason.notifyline.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * Webhook 冪等閘門。見缺口 G1。
 *
 * <p>LINE 在 webhook 回應逾時或非 200 時會<strong>重送同一事件</strong>。
 * 有些操作天生冪等（upsert user），有些不是：
 *
 * <ul>
 *   <li>{@code FollowEvent} 會發歡迎訊息 → 重送就重複打招呼</li>
 *   <li>「申請金鑰」指令 → 重送就發出兩個 token</li>
 * </ul>
 *
 * <p>所以在<strong>所有業務邏輯之前</strong>先過這道閘門。這比「把每個操作各自
 * 寫成冪等」更可靠：只有一個地方需要確認正確性，而不是每加一個新事件處理
 * 就要重新思考一次冪等性。
 *
 * <p>刻意的取捨：處理到一半失敗時，{@code webhook_event} 已經寫入，LINE 重送
 * 也不會重跑。<strong>寧可漏處理一個事件（有 log 可查、可人工補），也不要重複
 * 處理</strong>（可能重複發訊息給使用者）。
 */
@Component
public class WebhookEventGuard {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventGuard.class);

    /** LINE 的重送窗口遠短於此。 */
    public static final Duration RETENTION = Duration.ofDays(30);

    private final WebhookEventRepository repository;
    private final Clock clock;

    public WebhookEventGuard(WebhookEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 用 {@code REQUIRES_NEW}：這筆記錄必須獨立提交。若跟著業務邏輯的交易一起
     * rollback，LINE 重送時就會再處理一次 —— 那正是這道閘門要防的事。
     *
     * @param webhookEventId LINE 提供；為 null 時一律視為首次（無從去重）
     * @return true 代表首次收到，應該處理
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean shouldProcess(String webhookEventId, String eventType, String lineUserId) {
        if (webhookEventId == null || webhookEventId.isBlank()) {
            // 理論上 LINE 一定會給。真的沒有就只能放行，並留下痕跡供追查。
            log.warn("Webhook 事件缺少 webhookEventId，無法去重：type={} user={}", eventType, lineUserId);
            return true;
        }

        boolean firstTime = repository.insertIfAbsent(
                webhookEventId, eventType, lineUserId, clock.instant()) == 1;

        if (!firstTime) {
            log.info("Webhook 重送，略過：eventId={} type={}", webhookEventId, eventType);
        }
        return firstTime;
    }

    /**
     * 多實例注意：這個排程在多實例下會重複執行（缺口 G12）。
     * 重複刪除是冪等的，但會浪費資源。加第二個實例前需導入 ShedLock。
     */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteByReceivedAtBefore(clock.instant().minus(RETENTION));
        if (deleted > 0) {
            log.info("清除過期 webhook 事件紀錄：{} 筆", deleted);
        }
    }
}
