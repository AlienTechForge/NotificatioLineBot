package com.jason.notifyline.admin;

import com.jason.notifyline.config.LineApiProperties;
import com.jason.notifyline.notification.dispatch.LineMulticastClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * LINE 官方帳號的月訊息配額用量，只給管理台儀表板用。
 *
 * <p>刻意獨立於 {@link LineMulticastClient}：那支走斷路器與限速器，是熱路徑，
 * 錯誤分類會影響重試決策。這支只是資訊性查詢，失敗了就顯示「拿不到」，
 * 不該牽動任何發送邏輯，也不需要那一整套保護機制。放在 {@code admin} 套件
 * 而不是 {@code notification.dispatch}，是因為唯一的呼叫端就是管理台。
 */
@Component
class LineQuotaClient {

    private static final Logger log = LoggerFactory.getLogger(LineQuotaClient.class);

    private static final String QUOTA_PATH = "/v2/bot/message/quota";
    private static final String CONSUMPTION_PATH = "/v2/bot/message/quota/consumption";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 快取存活時間。管理台每次進總覽都可能觸發查詢，沒有快取的話一個人狂按
     * 重新整理就是在浪費查詢次數。快取<strong>連失敗也快取</strong>，避免 LINE
     * 端故障時被當成「重新整理就會好」而狂打。
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile AdminDto.LineQuota cached;
    private volatile Instant cachedAt = Instant.MIN;

    LineQuotaClient(LineApiProperties properties,
                    ObjectMapper objectMapper,
                    Clock clock,
                    LineMulticastClient.LineChannelToken channelToken) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + channelToken.value())
                .build();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** @return 永遠有值。呼叫失敗時回 {@link AdminDto.LineQuota#unavailable()}，不拋例外。 */
    synchronized AdminDto.LineQuota current() {
        Instant now = clock.instant();
        if (cached != null && Duration.between(cachedAt, now).compareTo(CACHE_TTL) < 0) {
            return cached;
        }

        AdminDto.LineQuota result = fetch();
        cached = result;
        cachedAt = now;
        return result;
    }

    private AdminDto.LineQuota fetch() {
        try {
            Map<?, ?> quota = getJson(QUOTA_PATH);
            Map<?, ?> consumption = getJson(CONSUMPTION_PATH);

            long used = asLong(consumption.get("totalUsage"), 0L);
            String type = String.valueOf(quota.get("type"));

            if ("none".equals(type)) {
                return AdminDto.LineQuota.unlimitedPlan(used);
            }
            long limit = asLong(quota.get("value"), 0L);
            return AdminDto.LineQuota.of(limit, used);

        } catch (Exception e) {
            // 逾時、5xx、憑證失效……任何原因都一樣：這只是資訊性顯示，
            // 拿不到就老實說拿不到，不猜測、不讓整個儀表板連帶壞掉。
            log.warn("查詢 LINE 月配額失敗：{}", e.toString());
            return AdminDto.LineQuota.unavailable();
        }
    }

    private Map<?, ?> getJson(String path) {
        String body = restClient.get().uri(path).retrieve().body(String.class);
        return objectMapper.readValue(body, Map.class);
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return fallback;
    }
}
