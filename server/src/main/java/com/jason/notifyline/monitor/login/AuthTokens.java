package com.jason.notifyline.monitor.login;

/**
 * 登入成功後 Cognito 回的 token 組。
 *
 * @param idToken          實際注入 header 的 token
 * @param refreshToken     換新 idToken 用；{@code REFRESH_TOKEN_AUTH} 的回應<strong>不含</strong>
 *                         這個欄位（沿用舊的），所以可能是 {@code null}
 * @param expiresInSeconds Cognito 宣告的壽命，只當 JWT 解析失敗時的退路
 */
public record AuthTokens(String idToken, String refreshToken, int expiresInSeconds) {
}
