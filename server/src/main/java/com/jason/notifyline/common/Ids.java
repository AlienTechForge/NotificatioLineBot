package com.jason.notifyline.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 識別碼與機密值的產生。一律走 {@link SecureRandom}，不使用 {@code Math.random()}
 * 或 {@code java.util.Random}。
 *
 * <p>長度規格見 {@code Docs/plan/03-權限與認證設計.md} §5.1。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Client 公開識別碼的前綴，讓外洩到 log 或設定檔時一眼看得出是什麼。 */
    public static final String CLIENT_ID_PREFIX = "cli_";

    private static final int CLIENT_ID_BODY_LENGTH = 20;
    private static final int CLIENT_SECRET_BYTES = 48;
    private static final int ENROLLMENT_TOKEN_BYTES = 32;

    /**
     * 只用小寫英數。避免大小寫在設定檔／環境變數中被意外正規化，也避免
     * base64 的 {@code -} {@code _} 在人工轉抄時出錯。
     * 36^20 約等於 103 bits 熵，遠超過需求。
     */
    private static final char[] CLIENT_ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {
    }

    /** {@code cli_} + 20 個小寫英數字元。 */
    public static String newClientId() {
        StringBuilder builder = new StringBuilder(CLIENT_ID_PREFIX.length() + CLIENT_ID_BODY_LENGTH);
        builder.append(CLIENT_ID_PREFIX);
        for (int i = 0; i < CLIENT_ID_BODY_LENGTH; i++) {
            // SecureRandom.nextInt(bound) 內部已處理 modulo bias
            builder.append(CLIENT_ID_ALPHABET[RANDOM.nextInt(CLIENT_ID_ALPHABET.length)]);
        }
        return builder.toString();
    }

    /**
     * 48 bytes 隨機值的 base64url。
     *
     * <p>這是 HMAC 的金鑰，且會顯示給使用者複製，所以用 URL-safe 編碼避免
     * 在網址或 header 中需要跳脫。
     */
    public static String newClientSecret() {
        return URL_ENCODER.encodeToString(randomBytes(CLIENT_SECRET_BYTES));
    }

    /**
     * 32 bytes（256 bits 熵）隨機值的 base64url。
     *
     * <p>這個 token 會直接放進 URL path，且本身即憑證，所以熵要夠高到不可窮舉。
     */
    public static String newEnrollmentToken() {
        return URL_ENCODER.encodeToString(randomBytes(ENROLLMENT_TOKEN_BYTES));
    }

    public static byte[] randomBytes(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive, got " + length);
        }
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * SHA-256。
     *
     * <p>用於 enrollment token —— 資料庫只存雜湊，外洩時攻擊者拿不到可用的連結。
     */
    public static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256。專案內所有 SHA-256 都走這裡，避免重複的例外處理樣板。 */
    public static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必備演算法，實務上走不到這裡
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
