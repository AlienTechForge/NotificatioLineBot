package com.jason.notifyline.config;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 非同步執行緒池。見 {@code Docs/plan/07-非同步與可靠性設計.md} §8。
 */
@Configuration
@EnableAsync
@ConfigurationProperties(prefix = "app.async")
public class AsyncConfig {

    /** 工作是 I/O bound（等 LINE 回應），不是 CPU bound。真正的併發控制在 RateLimiter。 */
    private int corePoolSize = 4;
    private int maxPoolSize = 8;
    /** 有界佇列。無界佇列會在異常時吃光記憶體。 */
    private int queueCapacity = 200;

    @Bean("notifyTaskExecutor")
    public Executor notifyTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("notify-");
        // 池滿時由呼叫者執行緒處理，產生自然的背壓。比丟棄工作好得多 ——
        // outbox 已保證不掉單，最壞情況只是變慢。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(AsyncConfig::propagateMdc);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 把 MDC 複製到 @Async 執行緒。見缺口 G3。
     *
     * <p><strong>漏掉這段的後果</strong>：非同步段的所有 log 都沒有 requestId，
     * 出事時無法把同步段與非同步段的日誌串起來。這是最容易漏、也最容易在事故當下
     * 讓人抓狂的一個細節。
     *
     * <p>收尾用<strong>還原</strong>而不是 {@code MDC.clear()}。池滿時
     * {@link ThreadPoolExecutor.CallerRunsPolicy} 會讓工作在<strong>呼叫者的
     * HTTP 執行緒</strong>上跑，此時 clear 會把該請求自己的 requestId 一併抹掉，
     * 導致後半段的日誌無故失去追蹤碼 —— 而且只在池滿（也就是最需要查日誌）時發生。
     * 存下原本的 context 再還原，兩種情形都正確：非同步執行緒原本是空的，
     * 還原等同於清空。
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

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
