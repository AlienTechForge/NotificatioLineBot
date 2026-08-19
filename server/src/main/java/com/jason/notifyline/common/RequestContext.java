package com.jason.notifyline.common;

/**
 * 跨層共用的 MDC 與 request attribute 鍵名。
 *
 * <p>集中定義是為了避免同一個鍵在不同檔案被打成不同字串 —— 那種錯誤不會編譯失敗，
 * 只會讓日誌少一個欄位，而且要到出事查 log 時才發現。
 */
public final class RequestContext {

    /** correlation id。貫穿 HTTP 執行緒、@Async 執行緒與所有 LINE API 呼叫。 */
    public static final String REQUEST_ID = "requestId";

    public static final String CLIENT_ID = "clientId";
    public static final String NOTIFICATION_ID = "notificationId";
    public static final String BATCH_NO = "batchNo";

    /** 呼叫端可傳入自己的追蹤碼，回應也會帶回去。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 已驗證的呼叫端身分，由 HmacAuthFilter 放入。 */
    public static final String PRINCIPAL_ATTRIBUTE = "notifyline.principal";

    private RequestContext() {
    }
}
