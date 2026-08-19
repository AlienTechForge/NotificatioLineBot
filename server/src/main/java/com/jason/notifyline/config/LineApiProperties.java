package com.jason.notifyline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LINE Messaging API 的呼叫端設定。
 *
 * @param apiBaseUrl         正式是 {@code https://api.line.me}；測試指向 MockWebServer
 * @param rateLimitPerSecond 我方主動限速，永遠低於 LINE 的上限
 */
@ConfigurationProperties(prefix = "app.line")
public record LineApiProperties(String apiBaseUrl, int rateLimitPerSecond) {

    public LineApiProperties {
        apiBaseUrl = (apiBaseUrl == null || apiBaseUrl.isBlank())
                ? "https://api.line.me"
                : apiBaseUrl.trim().replaceAll("/+$", "");
        rateLimitPerSecond = rateLimitPerSecond <= 0 ? 100 : rateLimitPerSecond;
    }
}
