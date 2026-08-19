package com.jason.notifyline.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jason.notifyline.common.ApiErrorWriter;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * 呼叫端速率限制。見缺口 G2。
 *
 * <p>只靠「事後停用失控 client」是不夠的 —— 額度已經燒掉了。這是預防層。
 *
 * <p>掛在 {@link HmacAuthFilter} <strong>之後</strong>：要先知道是哪個 client
 * 才能套用它自己的限額。代價是未驗證的請求不受此限制，但那些請求在驗簽階段
 * 就會被擋下，成本很低。
 *
 * <p><strong>目前是 per-instance 計數</strong>（行程內的 Caffeine cache）。單一實例下
 * 正確；加開第二個實例時每個實例會各自計數，實際上限變成 N 倍。見 07 §11。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String PROTECTED_PREFIX = "/api/v1/";

    private final ApiErrorWriter errorWriter;
    private final Clock clock;
    private final int defaultLimitPerMinute;

    /**
     * clientId → bucket。
     *
     * <p>閒置十分鐘後淘汰：長期沒來的 client 不必占記憶體，而重新建立的 bucket
     * 是滿的，對已經十分鐘沒發請求的呼叫端來說本來就該是滿的。
     */
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    public RateLimitFilter(ApiErrorWriter errorWriter,
                           Clock clock,
                           @Value("${app.rate-limit.default-per-minute:60}") int defaultLimitPerMinute) {
        this.errorWriter = errorWriter;
        this.clock = clock;
        this.defaultLimitPerMinute = defaultLimitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Object attribute = request.getAttribute(RequestContext.PRINCIPAL_ATTRIBUTE);
        if (!(attribute instanceof ClientPrincipal principal)) {
            // 未通過認證的請求不在這裡處理（HmacAuthFilter 已經擋下了）
            chain.doFilter(request, response);
            return;
        }

        int limit = principal.rateLimitPerMin() != null
                ? principal.rateLimitPerMin()
                : defaultLimitPerMinute;

        TokenBucket bucket = buckets.get(principal.clientId(),
                key -> new TokenBucket(limit, clock.millis()));

        TokenBucket.Result result = bucket.tryConsume(limit, clock.millis());
        if (!result.allowed()) {
            log.warn("速率超限：clientId={} limit={}/min retryAfter={}s",
                    principal.clientId(), limit, result.retryAfterSeconds());
            errorWriter.write(response, ErrorCode.RATE_LIMITED,
                    "Rate limit of " + limit + " requests per minute exceeded.",
                    result.retryAfterSeconds());
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Token bucket：容量 = 每分鐘上限，每分鐘補滿一次份量。
     *
     * <p>比「固定視窗計數」好的地方是不會在視窗邊界出現兩倍突發。
     */
    static final class TokenBucket {

        private static final long WINDOW_MILLIS = 60_000L;

        private double tokens;
        private long lastRefillMillis;

        TokenBucket(int capacity, long nowMillis) {
            this.tokens = capacity;
            this.lastRefillMillis = nowMillis;
        }

        synchronized Result tryConsume(int capacity, long nowMillis) {
            refill(capacity, nowMillis);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Result(true, 0);
            }
            // 還差多少 token 才夠一個，換算成秒
            double needed = 1.0 - tokens;
            long retryAfter = (long) Math.ceil(needed * WINDOW_MILLIS / capacity / 1000.0);
            return new Result(false, Math.max(1, retryAfter));
        }

        private void refill(int capacity, long nowMillis) {
            long elapsed = nowMillis - lastRefillMillis;
            if (elapsed <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + (double) elapsed * capacity / WINDOW_MILLIS);
            lastRefillMillis = nowMillis;
        }

        record Result(boolean allowed, long retryAfterSeconds) {
        }
    }
}
