package com.jason.notifyline.monitor.login;

/**
 * Cognito 對 {@code USER_SRP_AUTH} 回的 {@code PASSWORD_VERIFIER} 挑戰。
 *
 * @param saltHex       {@code SALT}
 * @param srpBHex       {@code SRP_B}
 * @param secretBlock   {@code SECRET_BLOCK}（base64 原文，簽章時要先解碼）
 * @param userIdForSrp  {@code USER_ID_FOR_SRP} —— <strong>回覆挑戰時的 {@code USERNAME}
 *                      必須用這個值</strong>，不是登入用的 email，見 {@link CognitoSrp}
 */
public record SrpChallenge(String saltHex, String srpBHex, String secretBlock, String userIdForSrp) {
}
