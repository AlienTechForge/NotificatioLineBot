package com.jason.notifyline.common;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 在 filter 內產生錯誤回應。
 *
 * <p>{@code @RestControllerAdvice} 只涵蓋進到 DispatcherServlet 之後的流程，
 * 認證與速率限制都在那之前就把請求擋下，所以需要這條獨立的輸出路徑。
 * 兩者必須產生<strong>相同的信封格式</strong>，否則呼叫端要寫兩套解析。
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        write(response, code, message, null);
    }

    /**
     * @param retryAfterSeconds 非 null 時附上 {@code Retry-After}，讓呼叫端知道該等多久
     */
    public void write(HttpServletResponse response, ErrorCode code, String message,
                      Long retryAfterSeconds) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (retryAfterSeconds != null) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }

        ApiResponse<Void> body = ApiResponse.error(code, message, MDC.get(RequestContext.REQUEST_ID));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
