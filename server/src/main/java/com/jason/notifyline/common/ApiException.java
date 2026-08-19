package com.jason.notifyline.common;

/**
 * 會被轉成對外錯誤回應的例外。
 *
 * <p>訊息會直接送給呼叫端，所以<strong>不可包含內部細節</strong>。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
