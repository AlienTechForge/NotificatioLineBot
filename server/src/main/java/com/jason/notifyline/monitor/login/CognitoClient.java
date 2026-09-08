package com.jason.notifyline.monitor.login;

/**
 * Cognito 那三個呼叫的抽象。存在的唯一理由是<strong>可測試</strong>：
 * {@code SiteLoginService} 的快取、鎖、停用規則都要在沒有網路、沒有真實帳號的
 * 情況下驗證，而那些邏輯才是最容易寫錯的部分。
 *
 * <p>實作只有一個（{@link HttpCognitoClient}），測試用 fake。
 */
public interface CognitoClient {

    /**
     * 發起 SRP：{@code InitiateAuth(USER_SRP_AUTH)}。
     *
     * @param username 使用者輸入的登入帳號（email 之類）。回應裡的
     *                 {@link SrpChallenge#userIdForSrp()} 才是後續簽章要用的值
     * @param srpAHex  {@code A = g^a mod N} 的 hex
     */
    SrpChallenge initiateSrp(CognitoEndpoint endpoint, String username, String srpAHex);

    /**
     * 回覆挑戰：{@code RespondToAuthChallenge(PASSWORD_VERIFIER)}。
     *
     * @param userIdForSrp 必須是 {@link SrpChallenge#userIdForSrp()}
     * @param timestamp    必須與計算簽章時用的字串逐字相同
     */
    AuthTokens respondToPasswordVerifier(CognitoEndpoint endpoint,
                                         String userIdForSrp,
                                         String secretBlock,
                                         String signature,
                                         String timestamp);

    /**
     * 用 refresh token 換新的 idToken：{@code InitiateAuth(REFRESH_TOKEN_AUTH)}。
     *
     * <p>refresh token 過期時 Cognito 回的是
     * {@link CognitoAuthException.Reason#INVALID_CREDENTIALS} —— 與密碼錯誤同一個型別。
     * <strong>呼叫端要靠上下文區分</strong>：從這個方法拋出來的代表「refresh 過期，
     * 改走完整登入」，不是「密碼錯了，停用」。見 {@code SiteLoginService}。
     */
    AuthTokens refresh(CognitoEndpoint endpoint, String refreshToken);
}
