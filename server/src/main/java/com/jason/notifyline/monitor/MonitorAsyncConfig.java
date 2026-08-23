package com.jason.notifyline.monitor;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * {@link ApiMonitorRunner} 專用的執行緒池。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §7：「獨立執行緒池，不共用派送的」。
 *
 * <p>與 {@code config.AsyncConfig} 的 {@code notifyTaskExecutor} 刻意分開——一個慢掉
 * 的第三方監控目標不該拖慢 LINE 發送。核心/最大執行緒數比 {@code notifyTaskExecutor}
 * 小：監控目標的抓取是低頻、背景性質的工作（預設輪詢間隔 10 秒、單輪最多取
 * {@code app.monitor.claim-limit}=5 筆），不需要跟派送同等級的併發度。
 */
@Configuration
public class MonitorAsyncConfig {

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    /** 有界佇列，理由同 {@code notifyTaskExecutor}：無界佇列會在異常時吃光記憶體。 */
    private static final int QUEUE_CAPACITY = 50;

    @Bean("monitorTaskExecutor")
    public Executor monitorTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("monitor-");
        // 池滿時由呼叫者執行緒（monitorTaskExecutor 自己的排隊邏輯滿了才會走到這裡）
        // 處理，產生自然的背壓，理由同 notifyTaskExecutor：outbox 式的取件/租約機制
        // 已保證不掉單，最壞情況只是變慢，不該直接丟棄工作。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(MonitorAsyncConfig::propagateMdc);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 把呼叫端的 MDC 複製到背景執行緒，理由與作法都跟
     * {@code config.AsyncConfig#propagateMdc} 相同——這裡不引用那個 private 方法
     * （field/method 都是 private，且在不同的頂層類別），而是照同樣規則重寫一份。
     * 漏掉這段的後果：{@code ApiMonitorRunner} 在 {@code monitorTaskExecutor} 執行緒
     * 上打的所有 log 都沒有 requestId，出事時無法把觸發排程的那條執行緒與實際處理
     * 監控的背景執行緒的日誌串起來。
     */
    private static Runnable propagateMdc(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
