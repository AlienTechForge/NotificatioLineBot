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
     * <p>{@code finally} 的 clear 同樣重要 —— 執行緒是重複使用的，不清會讓下一個
     * 工作繼承錯誤的 context。
     */
    private static Runnable propagateMdc(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                task.run();
            } finally {
                MDC.clear();
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
