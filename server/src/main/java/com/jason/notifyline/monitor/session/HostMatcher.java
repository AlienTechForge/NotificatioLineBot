package com.jason.notifyline.monitor.session;

import java.net.IDN;
import java.util.Locale;

/**
 * cookie jar 的 host 比對邏輯。見 {@code Docs/plan/12-API監控易用性升級.md} §3.2。
 *
 * <h2>⚠️ 這是這個功能最危險的地方</h2>
 *
 * <p><strong>把 A 站的 cookie 送到 B 站 = 把你的 session token 洩漏給第三方。</strong>
 * 附加 cookie 前的比對必須是：請求 host 與 jar host <strong>完全相等</strong>，或請求
 * host 以 {@code "." + jarHost} <strong>結尾</strong>。
 *
 * <p><strong>絕不可用</strong> {@code contains}、{@code startsWith}，或不帶點號邊界的
 * {@code endsWith(jarHost)}——{@code endsWith("example.com")} 會讓
 * {@code evil-example.com} 拿到 {@code example.com} 的 cookie。
 *
 * <p>這跟 {@code UriHostValidator.isAllowed()}（{@code UriHostValidator.java:151}）、
 * {@code OutboundUrlGuard} 的白名單比對是同一個陷阱的不同版本，這裡的實作照同樣的
 * 規則重寫一份（三個類別各自 private，無法直接共用），比對前都先用
 * {@link IDN#toASCII} 正規化、以 {@link Locale#ROOT} 轉小寫。
 */
public final class HostMatcher {

    private HostMatcher() {
    }

    /**
     * IDN 正規化 + 小寫，語意抄 {@code OutboundUrlGuard.normalizeHost} /
     * {@code UriHostValidator.normalise}。{@link Locale#ROOT} 是刻意的——土耳其語系的
     * JVM 會把 {@code I} 轉成 {@code ı}，讓合法網域比對失敗。
     */
    public static String normalize(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        try {
            h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            // 轉不了就用原字串比，反正比不中就是不比對——fail closed。
        }
        return h.toLowerCase(Locale.ROOT);
    }

    /**
     * {@code candidateHost} 是否在 {@code scopeHost} 的比對範圍內：完全相等，或帶點號
     * 邊界的子網域。兩邊都會先正規化，呼叫端可以傳未正規化的原始字串。
     *
     * <p>用途一：附加 cookie 前，{@code candidateHost} = 請求 host、{@code scopeHost} =
     * jar host。用途二：驗證 {@code Set-Cookie} 的 {@code Domain=} 屬性，
     * {@code candidateHost} = 請求 host、{@code scopeHost} = {@code Domain} 屬性值——
     * 標準 cookie 語意本來就是「請求 host 要落在 Domain 屬性宣告的範圍內」，跟第一個
     * 用途是同一個方向的比對，可以共用同一個方法。
     */
    public static boolean matches(String candidateHost, String scopeHost) {
        String normalizedCandidate = normalize(candidateHost);
        String normalizedScope = normalize(scopeHost);
        if (normalizedCandidate.isEmpty() || normalizedScope.isEmpty()) {
            return false;
        }
        // 完全相等，或帶點號邊界的子網域。不可只用 endsWith——
        // 那會讓 evil-example.com 誤配到 example.com。
        return normalizedCandidate.equals(normalizedScope)
                || normalizedCandidate.endsWith("." + normalizedScope);
    }
}
