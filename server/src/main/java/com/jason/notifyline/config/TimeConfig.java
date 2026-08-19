package com.jason.notifyline.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 統一的時間來源。
 *
 * <p><strong>專案內不直接呼叫 {@code Instant.now()}</strong> —— 時間戳驗證、
 * 退避重試、保留期清理全都與時間有關，沒有可注入的時鐘就只能靠 sleep 寫測試，
 * 那會又慢又不穩。見 {@code Docs/plan/08-測試計畫.md} §7。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
