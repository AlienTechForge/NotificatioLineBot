package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.config.DispatchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 受理後立刻踢一次派送，不必等下一輪排程。
 *
 * <h2>為什麼一定要是「另一個 bean」的 {@code @Async} 方法</h2>
 *
 * <p>這支是從 {@code afterCommit} 回呼裡叫的，而 {@code afterCommit}
 * <strong>跑在原本那條 HTTP 執行緒上</strong>。若在那裡同步做事，呼叫端要等到
 * 整批送完才拿到 202 —— 202 的意義就沒了。更糟的是，那裡拋出的例外會讓一個
 * <strong>已經提交</strong>的請求對外變成 500，呼叫端於是重送，而工作其實早就
 * 排好了。
 *
 * <p>{@code @Async} 靠 Spring 代理生效，所以必須是另一個 bean 的方法 ——
 * 從同一個類別內部呼叫自己會安靜地變成同步執行，上面兩個問題就都回來了。
 *
 * <p>這只是<strong>延遲優化</strong>。工作已經持久化在 outbox 裡，這支沒跑成
 * 也只是慢一輪，{@link DeliveryScheduler} 一定會撿到。
 */
@Component
public class DispatchKicker {

    private static final Logger log = LoggerFactory.getLogger(DispatchKicker.class);

    private final DeliveryDispatcher dispatcher;
    private final DispatchProperties properties;

    public DispatchKicker(DeliveryDispatcher dispatcher, DispatchProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Async("notifyTaskExecutor")
    public void kick() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            dispatcher.runOnce();
        } catch (Exception e) {
            log.warn("即時派送失敗，交由排程重試", e);
        }
    }
}
