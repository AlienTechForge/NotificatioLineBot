package com.jason.notifyline.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/**
 * HMAC-SHA256 簽章與驗證。
 *
 * <p>見 {@code Docs/plan/03-權限與認證設計.md} §2.2。
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSigner() {
    }

    /**
     * @return Base64 標準編碼（非 URL-safe）的簽章
     */
    public static String sign(String secret, String canonicalString) {
        return Base64.getEncoder().encodeToString(mac(secret, canonicalString));
    }

    /**
     * 常數時間比對。
     *
     * <p>簽章不符、格式不合法、長度不對一律回 {@code false}，<strong>不拋例外</strong> ——
     * 這些都是「請求有問題」而不是「伺服器有問題」，應該變成 401 而不是 500。
     *
     * <p>比對一律用 {@link MessageDigest#isEqual}。不可用 {@code String.equals} 或
     * {@code Arrays.equals} —— 它們會在第一個不同的位元組短路返回，可被時序攻擊
     * 逐位元組推出正確簽章。
     */
    public static boolean verify(String secret, String canonicalString, String providedSignatureBase64) {
        if (providedSignatureBase64 == null || providedSignatureBase64.isEmpty()) {
            return false;
        }

        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(providedSignatureBase64);
        } catch (IllegalArgumentException e) {
            return false;
        }

        // 長度不同時 isEqual 也會走完固定時間並回 false
        return MessageDigest.isEqual(mac(secret, canonicalString), provided);
    }

    private static byte[] mac(String secret, String canonicalString) {
        if (secret == null || secret.isBlank()) {
            // secret 為空代表設定或資料有問題，必須大聲失敗而不是靜默拒絕
            throw new IllegalArgumentException("secret must not be blank");
        }
        Objects.requireNonNull(canonicalString, "canonicalString must not be null");

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to compute HMAC-SHA256", e);
        }
    }
}
