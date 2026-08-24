package com.jason.notifyline.monitor.domain;

/**
 * 計算欄位每一段（{@link ComputedStep}）使用的雜湊演算法。見
 * {@code Docs/plan/13-監控計算欄位設計.md} §2.2。
 *
 * <p>{@code HMAC_*} 需要額外的 {@link ComputedStep#keySecret()} 指定用哪個
 * {@code monitor_secret} 當金鑰；其餘四種是純雜湊，不使用金鑰。
 */
public enum HashAlgorithm {
    MD5,
    SHA1,
    SHA256,
    SHA512,
    HMAC_SHA1,
    HMAC_SHA256;

    /** 是否需要 {@link ComputedStep#keySecret()} 當 HMAC 金鑰。 */
    public boolean isHmac() {
        return this == HMAC_SHA1 || this == HMAC_SHA256;
    }
}
