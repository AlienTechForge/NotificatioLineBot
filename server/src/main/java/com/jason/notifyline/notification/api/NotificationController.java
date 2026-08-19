package com.jason.notifyline.notification.api;

import com.jason.notifyline.auth.CachedBodyHttpServletRequest;
import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ApiResponse;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.RequestContext;
import com.jason.notifyline.notification.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import java.util.UUID;

/**
 * 發送與查詢。契約見 {@code Docs/plan/05-API契約.md} §2。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    /** 冪等鍵長度上限，對齊 {@code notification.idempotency_key} 的欄位寬度。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 回 {@code 202 Accepted}：已受理並持久化，<strong>不代表已送達</strong>。
     * 實際結果用 {@link #get} 查。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<NotificationAccepted>> create(
            @Valid @RequestBody NotificationRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {

        ClientPrincipal principal = principal(servletRequest);
        String key = normaliseKey(idempotencyKey);

        NotificationAccepted accepted = notificationService.submit(
                principal,
                request,
                rawBody(servletRequest),
                key,
                MDC.get(RequestContext.REQUEST_ID));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(accepted));
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationDetail> get(@PathVariable UUID id,
                                               HttpServletRequest servletRequest) {
        return ApiResponse.ok(notificationService.find(principal(servletRequest), id));
    }

    // ------------------------------------------------------------------ 內部

    /**
     * payload hash 必須算在<strong>呼叫端實際簽章的那串位元組</strong>上。
     *
     * <p>拿反序列化後的 DTO 再序列化一次會得到不同的位元組：鍵的順序、空白、
     * Unicode 逸出、預設值填補都會變。那樣算出來的 hash 不但無法與呼叫端對帳，
     * 連「同一個請求重送兩次」都可能因為 Jackson 版本升級而算出不同的值，
     * 讓冪等在某次部署後悄悄失效。
     *
     * <p>{@link WebUtils#getNativeRequest} 不可省 —— Spring Security 的 filter chain
     * 會在我們的 wrapper 外面再包一層，直接 cast 會 {@code ClassCastException}。
     */
    private static byte[] rawBody(HttpServletRequest request) {
        CachedBodyHttpServletRequest cached =
                WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class);
        if (cached == null) {
            // 只有在 HmacAuthFilter 沒跑到的情況才會發生，那本身就是設定錯誤
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Request body was not captured.");
        }
        return cached.getCachedBody();
    }

    private static ClientPrincipal principal(HttpServletRequest request) {
        Object principal = request.getAttribute(RequestContext.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof ClientPrincipal client)) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Authenticated principal is missing.");
        }
        return client;
    }

    private static String normaliseKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
        }
        return key;
    }
}
