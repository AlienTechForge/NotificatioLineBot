package com.jason.notifyline.common;

/**
 * 會被轉成對外錯誤回應的例外。
 *
 * <p>訊息會直接送給呼叫端，所以<strong>不可包含內部細節</strong>。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String logMessage;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
        this.logMessage = message;
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.logMessage = message;
    }

    /**
     * 對外訊息（{@code message}）與寫進伺服器日誌的版本（{@code logMessage}）刻意分開的
     * 唯一情境：對外訊息裡有使用者剛剛自己貼上的內容（例如匯入監控失敗時回顯的
     * URL）——回顯給發起請求的使用者本人不算外洩，但 {@link GlobalExceptionHandler}
     * 會把 {@code ApiException} 的訊息原樣寫進日誌，那些內容（cookie、API token）就
     * 不該進日誌。多數呼叫端不需要這個建構子——直接用 {@link #ApiException(ErrorCode,
     * String)}，兩份訊息預設相同。
     */
    public ApiException(ErrorCode code, String message, String logMessage) {
        super(message);
        this.code = code;
        this.logMessage = logMessage;
    }

    public ErrorCode getCode() {
        return code;
    }

    /** 記錄用的版本；沒有特別指定時就是 {@link #getMessage()}。 */
    public String getLogMessage() {
        return logMessage;
    }
}
