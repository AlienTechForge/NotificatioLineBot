package com.jason.notifyline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 派送器設定。見 {@code Docs/plan/07-非同步與可靠性設計.md} §5。
 *
 * @param enabled      關掉後<strong>完全不會自動送出</strong>：排程輪詢與受理後的
 *                     即時派送都停用。給整合測試用，正式環境不要關
 * @param pollInterval 取件間隔，也就是最壞情況的排隊延遲
 * @param batchLimit   單輪取件上限
 * @param lease        租約長度。取件後把 {@code next_attempt_at} 推到這麼久之後
 */
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(Boolean enabled, Duration pollInterval,
                                 int batchLimit, Duration lease) {

    public DispatchProperties {
        enabled = enabled == null || enabled;
        pollInterval = pollInterval == null ? Duration.ofSeconds(10) : pollInterval;
        batchLimit = batchLimit <= 0 ? 10 : batchLimit;
        lease = lease == null ? Duration.ofMinutes(2) : lease;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
