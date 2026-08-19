package com.jason.notifyline.common;

import org.springframework.http.HttpStatus;

/**
 * 對外錯誤碼。見 {@code Docs/plan/05-API契約.md} §4。
 *
 * <p>呼叫端會針對 code 分支處理，所以這些字串是<strong>契約的一部分</strong>：
 * 可以新增，不可以改名或改語意。
 */
public enum ErrorCode {

    // ---- 400 -------------------------------------------------------------
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    CLIENT_NOT_BOUND(HttpStatus.BAD_REQUEST),
    NO_RECIPIENT(HttpStatus.BAD_REQUEST),
    URI_HOST_NOT_ALLOWED(HttpStatus.BAD_REQUEST),

    // ---- 401 -------------------------------------------------------------
    AUTH_MISSING_HEADER(HttpStatus.UNAUTHORIZED),
    AUTH_TIMESTAMP_SKEW(HttpStatus.UNAUTHORIZED),
    AUTH_NONCE_REPLAY(HttpStatus.UNAUTHORIZED),
    /** 簽章不符，或 client id 不存在 —— 刻意共用同一個碼，避免被列舉出哪些 id 存在。 */
    AUTH_INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED),
    AUTH_CLIENT_DISABLED(HttpStatus.UNAUTHORIZED),

    // ---- 403 / 404 -------------------------------------------------------
    SCOPE_DENIED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),

    // ---- 409 / 413 -------------------------------------------------------
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),

    // ---- 429 -------------------------------------------------------------
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    CLIENT_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
    LINE_MONTHLY_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),

    // ---- 500 -------------------------------------------------------------
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
