package com.jason.notifyline.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 統一錯誤回應。
 *
 * <p><strong>對外訊息絕不含 stack trace、SQL 片段、內部檔名，或其他使用者的
 * LINE User ID</strong>。需要細節時請提供 {@code requestId} 讓我們查伺服器日誌。
 *
 * <p>這裡只涵蓋進到 DispatcherServlet 之後的例外。認證與速率限制在那之前就擋下，
 * 走 {@link ApiErrorWriter}，兩者產生相同的信封格式。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException e, HttpServletRequest request) {
        log.warn("API 錯誤：code={} path={} message={}", e.getCode(), request.getRequestURI(), e.getMessage());
        return build(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        // 只回欄位名與規則，不回使用者送進來的值（可能是機密）
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return build(ErrorCode.VALIDATION_ERROR, detail.isBlank() ? "Request validation failed." : detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        // e.getMessage() 會夾帶 body 片段與內部類別名，不可外洩——對外回應維持泛用訊息。
        // 記錄時同樣不可原樣寫入：Jackson 在訊息尾端附上 [Source: (String)"..."] 這種
        // 原始 payload 的回顯片段。POST /admin/api/monitors/import 這類端點的 payload
        // 是使用者貼上的 cURL/fetch，含 cookie 與 API token，畸形的請求信封（例如漏了
        // 一個引號）會讓那整段連同機密一起被 Jackson 塞進這個例外訊息裡，原樣記錄
        // 等於把貼上的機密寫進伺服器日誌。safeDiagnostic() 保留 [Source: 之前 Jackson
        // 給的診斷原因（例如 "Unexpected character..."），在那之前截斷。
        log.warn("請求 body 無法解析：{}", safeDiagnostic(e.getMessage()));
        return build(ErrorCode.VALIDATION_ERROR, "Request body is not valid JSON.");
    }

    /** {@code [Source:} 之前的部分——Jackson 的錯誤原因，不含它自己回顯的原始 payload 片段。 */
    private static final String JACKSON_SOURCE_MARKER = "[Source:";

    private static String safeDiagnostic(String message) {
        if (message == null) {
            return "(no message)";
        }
        int sourceIndex = message.indexOf(JACKSON_SOURCE_MARKER);
        return sourceIndex < 0 ? message : message.substring(0, sourceIndex).stripTrailing();
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound() {
        return build(ErrorCode.NOT_FOUND, "Resource not found.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("參數錯誤：{}", e.getMessage());
        return build(ErrorCode.VALIDATION_ERROR, "Invalid request parameter.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        // 完整細節只進日誌
        log.error("未預期的錯誤：path={}", request.getRequestURI(), e);
        return build(ErrorCode.INTERNAL_ERROR,
                "An internal error occurred. Please report the requestId if this persists.");
    }

    private static ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, message, MDC.get(RequestContext.REQUEST_ID)));
    }
}
