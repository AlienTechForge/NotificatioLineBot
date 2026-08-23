package com.jason.notifyline.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Period;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * API 監控輪詢設定。見 {@code Docs/plan/11-API監控輪詢設計.md} §9。
 *
 * @param enabled                關掉後 {@code ApiMonitorScheduler} 不註冊，完全不會自動輪詢。
 *                                給整合測試用，正式環境不要關
 * @param pollInterval           排程輪詢間隔
 * @param claimLimit             單輪取件上限
 * @param lease                  租約長度。取件後把 {@code next_run_at} 推到這麼久之後，
 *                                避免下一輪重複取到同一筆
 * @param minInterval             使用者可設定的最小輪詢間隔（服務層檢查，DB 另有
 *                                {@code interval_seconds >= 30} 的硬底線）
 * @param connectTimeout          抓取目標 API 的連線逾時
 * @param readTimeout             抓取目標 API 的讀取逾時
 * @param maxBodyBytes            回應大小上限，邊讀邊擋
 * @param allowedHosts            白名單網域，逗號分隔。空 = 允許任何公開網域（仍受 IP 層防護）
 * @param failureNotifyThreshold  連續失敗達此次數才發一則失敗通知
 * @param runRetention            {@code api_monitor_run} 的保留期
 * @param seenItemRetention       {@code api_monitor_seen_item} 的保留期
 */
@ConfigurationProperties(prefix = "app.monitor")
public record MonitorProperties(
        Boolean enabled,
        Duration pollInterval,
        int claimLimit,
        Duration lease,
        Duration minInterval,
        Duration connectTimeout,
        Duration readTimeout,
        int maxBodyBytes,
        String allowedHosts,
        int failureNotifyThreshold,
        Period runRetention,
        Period seenItemRetention) {

    public MonitorProperties {
        enabled = enabled == null || enabled;
        pollInterval = pollInterval == null ? Duration.ofSeconds(10) : pollInterval;
        claimLimit = claimLimit <= 0 ? 5 : claimLimit;
        lease = lease == null ? Duration.ofMinutes(2) : lease;
        minInterval = minInterval == null ? Duration.ofSeconds(60) : minInterval;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        maxBodyBytes = maxBodyBytes <= 0 ? 1_048_576 : maxBodyBytes;
        allowedHosts = allowedHosts == null ? "" : allowedHosts.trim();
        failureNotifyThreshold = failureNotifyThreshold <= 0 ? 3 : failureNotifyThreshold;
        runRetention = runRetention == null ? Period.ofDays(14) : runRetention;
        seenItemRetention = seenItemRetention == null ? Period.ofDays(90) : seenItemRetention;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 白名單網域，全部轉小寫（網域比對不分大小寫）。
     *
     * <p>空集合代表<strong>允許任何公開網域</strong>——與 {@code AppProperties.allowedUriHostList()}
     * 剛好相反的預設值語意，因為這裡仍受 §5.1 的 IP 層防護全面約束，白名單只是選配的
     * 額外收斂，不是唯一防線。
     */
    public List<String> allowedHostList() {
        if (allowedHosts.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .toList();
    }
}
