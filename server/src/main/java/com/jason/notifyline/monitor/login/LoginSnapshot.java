package com.jason.notifyline.monitor.login;

import java.time.Instant;

/**
 * 一筆登入設定的不可變快照，由 {@link MonitorLoginStore#loadForUse} 在短交易內組出來。
 *
 * <p>刻意是快照而不是 JPA entity —— 理由與 {@code ClaimedMonitor} 完全相同：
 * 登入要打 Cognito，那段網路呼叫發生在交易之外，手上若拿著 entity，任何一次 getter
 * 都可能觸發 lazy loading 而在沒有交易的情況下悄悄開一條新連線。
 *
 * @param idToken       解密後的快取 token；{@code null} = 沒有可用的
 * @param refreshToken  解密後的 refresh token；{@code null} = 沒有，只能走完整登入。
 *                      <strong>絕不可外傳</strong>（DTO、log、訊息）
 */
public record LoginSnapshot(
        Long id,
        String name,
        boolean enabled,
        CognitoEndpoint endpoint,
        String username,
        String idToken,
        Instant tokenExpiresAt,
        String refreshToken,
        String headerName,
        String headerValueTemplate) {

    /**
     * 快取的 token 還能用嗎。
     *
     * @param skewSeconds 安全邊際：只剩幾秒時就當作已過期，避免「檢查時還有效、
     *                    送到對方手上已過期」那種看起來像對方不穩定的間歇性 401
     */
    public boolean hasFreshToken(Instant now, int skewSeconds) {
        return idToken != null
                && tokenExpiresAt != null
                && tokenExpiresAt.minusSeconds(skewSeconds).isAfter(now);
    }

    public boolean hasRefreshToken() {
        return refreshToken != null;
    }

    /** 不輸出 token、refresh token、帳號。 */
    @Override
    public String toString() {
        return "LoginSnapshot[" + id + " name=" + name + " enabled=" + enabled + "]";
    }
}
