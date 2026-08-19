package com.jason.notifyline.notification.dispatch;

/**
 * 一次 LINE 呼叫的結果分類。
 *
 * <p>分類決定了批次接下來怎麼走，所以每一類的界線都要能一句話說清楚。
 *
 * @param kind          分類
 * @param lineRequestId LINE 回應的 {@code x-line-request-id}，只有 SENT 才有
 * @param errorCode     寫進 {@code notification_delivery.error_code} 的短碼
 * @param errorMessage  給人看的細節
 */
public record SendOutcome(Kind kind, String lineRequestId, String errorCode, String errorMessage) {

    public enum Kind {

        /** LINE 已接受。<strong>不代表使用者看到了</strong> —— 已封鎖的收件人也回 200。 */
        SENT,

        /** 暫時性問題：逾時、5xx、連線失敗、被限速。退避後重試。 */
        RETRY,

        /**
         * 終局失敗：請求格式錯誤、憑證失效、月額度用罄。重試不會有不同結果，
         * 只會浪費 5 次呼叫並延後失敗被發現的時間。
         */
        FATAL,

        /**
         * 根本沒送出去：斷路器開路，或本地限速器擋下。
         *
         * <p><strong>{@code attempt_count} 不遞增。</strong> 若遞增，LINE 端一次
         * 長時間故障就會讓所有排隊中的批次在斷路期間耗盡重試次數變成永久失敗 ——
         * 那正是斷路器要防止的事。
         */
        DEFERRED
    }

    public static SendOutcome sent(String lineRequestId) {
        return new SendOutcome(Kind.SENT, lineRequestId, null, null);
    }

    public static SendOutcome retry(String errorCode, String message) {
        return new SendOutcome(Kind.RETRY, null, errorCode, message);
    }

    public static SendOutcome fatal(String errorCode, String message) {
        return new SendOutcome(Kind.FATAL, null, errorCode, message);
    }

    public static SendOutcome deferred(String errorCode, String message) {
        return new SendOutcome(Kind.DEFERRED, null, errorCode, message);
    }
}
