package com.jason.notifyline.observability;

import com.jason.notifyline.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Correlation id。見缺口 G3。
 *
 * <p>一個請求會跨越「HTTP 執行緒 → @Async 執行緒 → N 次 LINE API 呼叫 →
 * 可能還有 redriver 在幾分鐘後重試」。沒有貫穿的識別碼，事故當下無法把 log 串起來。
 *
 * <p>必須是最外層的 filter —— 認證失敗的回應也要帶 requestId，否則呼叫端回報
 * 「我一直 401」時我們無從查起。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 呼叫端傳來的值長度上限，避免有人用超長字串灌爆日誌。 */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = sanitise(request.getHeader(RequestContext.REQUEST_ID_HEADER));
        MDC.put(RequestContext.REQUEST_ID, requestId);
        response.setHeader(RequestContext.REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 執行緒是重複使用的，不清會讓下一個請求繼承錯誤的 context
            MDC.clear();
        }
    }

    private static String sanitise(String provided) {
        if (provided == null || provided.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = provided.trim();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        // 只留可安全放進日誌與 header 的字元
        return trimmed.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
