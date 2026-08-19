package com.jason.notifyline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 應用層設定。加密金鑰另見 {@link CryptoProperties}。
 *
 * @param publicBaseUrl    產生 enrollment 一次性連結用，必須是 https
 * @param ownerLineUserId  啟動時要標記為 owner 的 LINE user，逗號分隔可多個
 * @param allowedUriHosts  {@code notify:raw} 的訊息中允許出現的連結網域，逗號分隔
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicBaseUrl,
        String ownerLineUserId,
        String allowedUriHosts) {

    public AppProperties {
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        ownerLineUserId = ownerLineUserId == null ? "" : ownerLineUserId.trim();
        allowedUriHosts = allowedUriHosts == null ? "" : allowedUriHosts.trim();
    }

    /**
     * 允許的連結網域，全部轉小寫（網域比對不分大小寫）。
     *
     * <p>空集合代表<strong>不允許任何外部連結</strong> —— 這是安全的預設值。
     * 見缺口 G5：原始 message object 可帶 uri action，一組外洩的金鑰就能發出
     * 掛著官方帳號名義的釣魚連結。
     */
    public List<String> allowedUriHostList() {
        return splitCsv(allowedUriHosts).stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .toList();
    }

    public List<String> ownerLineUserIdList() {
        return splitCsv(ownerLineUserId);
    }

    private static List<String> splitCsv(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
