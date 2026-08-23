package com.jason.notifyline.monitor.fetch;

/**
 * {@link ApiFetcher#fetch} 的執行結果。密封成 {@link Success} / {@link Failure}
 * 兩種，呼叫端用 {@code switch} 窮舉處理，不會漏掉某個分支。
 *
 * <p>{@link Failure#detail()} 刻意只放分類描述（狀態碼、逾時、內容型別等 metadata），
 * <strong>不放回應內容本身</strong>——見 {@code V4__api_monitor.sql} 對
 * {@code api_monitor_run.error_message} 的註解：回應可能含目標 API 的機敏資料，
 * 後台執行紀錄不是存它的地方。
 */
public sealed interface FetchResult {

    /** @param body 已通過 {@link Reason#NON_JSON_CONTENT_TYPE} 與大小上限檢查的回應內容 */
    record Success(int httpStatus, String contentType, String body) implements FetchResult {
    }

    /**
     * @param httpStatus 有實際 HTTP 回應時才有值（逾時、網路層錯誤、或被
     *                    {@link OutboundUrlGuard} 擋下時為 {@code null}）
     */
    record Failure(Reason reason, String detail, Integer httpStatus) implements FetchResult {
    }

    enum Reason {
        /**
         * 被 {@link OutboundUrlGuard} 擋下。{@code ApiFetcher} 本身不會產生這個值
         * （guard 檢查發生在 fetch 之前，由呼叫端各自處理），保留給需要把 guard 的
         * {@link OutboundUrlGuard.BlockedException} 也正規化成同一種結果形狀的呼叫端使用。
         */
        BLOCKED_URL,
        /** 收到 3xx。絕不跟隨 —— 見 {@link ApiFetcher} 類別註解。 */
        REDIRECT_NOT_ALLOWED,
        TIMEOUT,
        /** 邊讀邊擋時超過 {@code app.monitor.max-body-bytes}。 */
        BODY_TOO_LARGE,
        /** Content-Type 不是 {@code application/json} 或 {@code +json} 結尾。 */
        NON_JSON_CONTENT_TYPE,
        /** 4xx / 5xx。 */
        HTTP_ERROR,
        /** 連線被拒、連線中斷等其他 I/O 層錯誤。 */
        NETWORK_ERROR
    }
}
