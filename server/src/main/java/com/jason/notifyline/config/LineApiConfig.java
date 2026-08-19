package com.jason.notifyline.config;

import com.jason.notifyline.notification.dispatch.LineMulticastClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LINE API 呼叫的保護機制。見 {@code Docs/plan/07-非同步與可靠性設計.md} §7。
 */
@Configuration
public class LineApiConfig {

    /**
     * 主動限速。
     *
     * <p>LINE multicast 的上限是 200 req/s，我們只用一半 —— 剩下的留給 webhook 回覆
     * 與 profile 校正，它們與發送<strong>共用同一個 channel 的配額</strong>。
     * 用滿 200 的結果是「大量發送期間，使用者傳訊息給 bot 得不到回覆」。
     *
     * <p>{@code timeoutDuration} 設 0：拿不到令牌就立刻回 {@code RequestNotPermitted}，
     * 由派送器把批次延後。阻塞等令牌會佔住執行緒，而工作已經在 outbox 裡了，
     * 等下一輪一點都不急。
     */
    @Bean
    RateLimiter lineRateLimiter(LineApiProperties properties) {
        return RateLimiter.of("line-api", RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(properties.rateLimitPerSecond())
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    /**
     * 斷路器。
     *
     * <p>只有 5xx 與網路層失敗會被計入 —— 400（呼叫端請求寫壞了）不代表 LINE 有問題，
     * 把它計入會讓一個持續送壞請求的呼叫端害得所有人都送不出訊息。
     *
     * <p>開路期間批次走 {@code DEFERRED}：延後但<strong>不遞增 attempt_count</strong>。
     * 遞增的話，LINE 一次長時間故障就會讓所有排隊批次耗盡重試次數變成永久失敗，
     * 那正是斷路器要防止的事。
     */
    @Bean
    CircuitBreaker lineCircuitBreaker() {
        return CircuitBreaker.of("line-api", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                // 樣本太少就開路會讓服務剛啟動時的一兩次失敗造成全面停擺
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
    }

    /**
     * 把 channel token 包成具名型別。
     *
     * <p>直接注入 {@code String} 會讓 Spring 在多個 String bean 之間選錯，
     * 而且錯了也不會編譯失敗 —— 只會在正式環境拿著錯的憑證去呼叫 LINE。
     */
    @Bean
    LineMulticastClient.LineChannelToken lineChannelToken(
            @Value("${line.bot.channel-token}") String token) {
        return new LineMulticastClient.LineChannelToken(token);
    }
}
