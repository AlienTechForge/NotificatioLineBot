package com.jason.notifyline.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 統一回應信封。見 {@code Docs/plan/05-API契約.md}。
 *
 * <p>成功與失敗用同一個形狀，呼叫端不必依 HTTP 狀態碼切換解析方式。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message, String requestId) {
        return new ApiResponse<>(false, null, new ApiError(code.name(), message, requestId));
    }

    /**
     * @param message  給人看的說明。<strong>絕不可含 stack trace、SQL、內部檔名
     *                 或其他使用者的 LINE User ID</strong>
     * @param requestId 對應伺服器日誌的追蹤碼，回報問題時附上
     */
    public record ApiError(String code, String message, String requestId) {
    }
}
