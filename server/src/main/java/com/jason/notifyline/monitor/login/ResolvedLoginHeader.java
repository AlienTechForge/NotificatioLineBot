package com.jason.notifyline.monitor.login;

/**
 * 要注入請求的一個 header。
 *
 * <p><strong>{@code value} 含有效 token</strong>：不可寫進 log、{@code api_monitor_run}
 * 或任何 DTO。它只從 {@code SiteLoginService} 流向 {@code ApiFetcher}。
 */
public record ResolvedLoginHeader(String name, String value) {
}
