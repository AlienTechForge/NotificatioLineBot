package com.jason.notifyline.monitor.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code Cookie} / {@code Set-Cookie} header 的純文字解析與組裝。純字串切分，不做任何
 * 語意理解——只在乎「這個 cookie 叫什麼、值是什麼、可以送到哪個 host」，不是完整的
 * 瀏覽器 cookie jar 實作（{@code Path}、{@code Secure}、{@code SameSite}、
 * {@code Expires}／{@code Max-Age} 全部忽略）。
 */
public final class CookieCodec {

    private static final String DOMAIN_ATTR_PREFIX = "Domain=";

    private CookieCodec() {
    }

    /** 解析 {@code Cookie} header 值：{@code "a=1; b=2"} → {@code {a:"1", b:"2"}}。 */
    static Map<String, String> parseCookieHeader(String value) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return cookies;
        }
        for (String pair : value.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue; // 沒有 "=" 或名稱是空字串：跳過這一段，不讓整段解析失敗
            }
            String name = trimmed.substring(0, eq).trim();
            String val = trimmed.substring(eq + 1).trim();
            if (!name.isEmpty()) {
                cookies.put(name, val);
            }
        }
        return cookies;
    }

    /** 組裝回 {@code Cookie} header 值，供附加到出站請求。保留呼叫端傳入的順序。 */
    static String serializeCookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * 解析單一 {@code Set-Cookie} header 值：第一段的 {@code name=value}，以及可選的
     * {@code Domain=} 屬性。
     *
     * @return {@code null} 代表這段格式不合法（沒有 {@code name=value}），呼叫端應該
     *         忽略這一筆，不可讓整個回應的其餘 cookie 也一起被放棄
     */
    static SetCookieAttributes parseSetCookie(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(";");
        String first = parts[0].trim();
        int eq = first.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String name = first.substring(0, eq).trim();
        String value = first.substring(eq + 1).trim();
        if (name.isEmpty()) {
            return null;
        }

        String domain = null;
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            if (attr.regionMatches(true, 0, DOMAIN_ATTR_PREFIX, 0, DOMAIN_ATTR_PREFIX.length())) {
                domain = attr.substring(DOMAIN_ATTR_PREFIX.length()).trim();
                if (domain.startsWith(".")) {
                    // 前導點是舊式寫法（.example.com），語意上跟 example.com 相同。
                    domain = domain.substring(1);
                }
            }
        }
        return new SetCookieAttributes(name, value, blankToNull(domain));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 在 header map 裡不分大小寫找一個 header 的值——匯入解析器與使用者手打的 header
     * 大小寫不保證一致（{@code cookie} 來自 {@code -b}，{@code Cookie} 可能來自
     * {@code -H} 或 {@code fetch(...)} 的 headers 物件）。
     */
    public static Optional<String> findHeaderValueIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** 回傳一份不含指定 header（大小寫不拘）的新 map，原 map 不受影響。 */
    public static Map<String, String> withoutHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (!key.equalsIgnoreCase(name)) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
    }

    /**
     * @param domain {@code null} 代表這筆 {@code Set-Cookie} 沒有帶 {@code Domain} 屬性
     *               （host-only cookie，只能送回原本回應它的那個 host）
     */
    record SetCookieAttributes(String name, String value, String domain) {
    }
}
