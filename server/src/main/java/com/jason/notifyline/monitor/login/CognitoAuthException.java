package com.jason.notifyline.monitor.login;

/**
 * 登入流程的失敗。{@link Reason#permanent()} 決定 {@code SiteLoginService} 要停用這組
 * 登入還是退避重試 —— 見 {@code Docs/plan/15-監控站台登入設計.md} §5.1。
 *
 * <p><strong>訊息不可含帳密或 token 片段</strong>：這個例外的 {@code getMessage()} 會被
 * 寫進 {@code monitor_login.last_error} 並顯示在後台。
 */
public class CognitoAuthException extends RuntimeException {

    /**
     * 失敗類型。
     *
     * <p>{@code permanent = true} 的意思是「再試一百次也一樣」，而且對 Cognito 來說
     * 每次重試都會累加帳號的失敗計數 —— 那會把使用者自己的帳號鎖死。這類錯誤一律
     * 停用登入設定並通知，等人來處理。
     */
    public enum Reason {

        /** 帳密不符。<strong>這是會鎖帳號的那一種</strong>。 */
        INVALID_CREDENTIALS(true),

        USER_NOT_FOUND(true),

        /** 對方要求改密碼／完成註冊，機器人無法自行處理。 */
        PASSWORD_RESET_REQUIRED(true),

        /** 站台開了 MFA。這條路徑就此失效，需要人工介入。 */
        MFA_REQUIRED(true),

        /** 設定錯誤（pool / client 不存在、流程被停用）。重試無用。 */
        CONFIGURATION(true),

        /** 對方限流。等一下就好，不是我們的錯，也不會鎖帳號。 */
        RATE_LIMITED(false),

        /** 網路、5xx、回應格式不如預期。 */
        TRANSIENT(false);

        private final boolean permanent;

        Reason(boolean permanent) {
            this.permanent = permanent;
        }

        /** true = 必須停用並通知，不可重試。 */
        public boolean permanent() {
            return permanent;
        }
    }

    private final Reason reason;

    public CognitoAuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public CognitoAuthException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
