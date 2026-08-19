package com.jason.notifyline.auth;

import com.jason.notifyline.common.Ids;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Client secret 的加解密。
 *
 * <p><strong>為什麼是加密而不是雜湊</strong>：HMAC 驗簽是伺服器端「重新計算」簽章
 * 再比對，計算需要 secret 本身。雜湊是單向的，拿不回 secret 就無法重算。
 * 詳見 {@code Docs/plan/adr/0002-client-secret-加密儲存而非雜湊.md}。
 *
 * <p>方案：
 * <ul>
 *   <li>AES-256-GCM —— authenticated encryption，同時保證機密性與完整性。
 *       單純的 CBC 只保證機密性，能寫資料庫的攻擊者可以竄改密文而不被發現。</li>
 *   <li>每筆記錄獨立的 12 bytes IV。<strong>GCM 在相同 key 下重用 IV 會直接洩漏明文</strong>，
 *       所以每次加密都重新產生。</li>
 *   <li>AAD 綁 {@code client_id} —— 防止把 A 的密文複製到 B 的記錄上。</li>
 *   <li>多版本 key 並存，支援不停機輪替（見 {@code Docs/plan/09-CICD與維運.md} §7.1）。</li>
 * </ul>
 */
public final class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    /** GCM 建議的 IV 長度。 */
    public static final int IV_LENGTH_BYTES = 12;
    /** GCM authentication tag 長度。 */
    public static final int TAG_LENGTH_BITS = 128;
    /** AES-256。 */
    public static final int KEY_LENGTH_BYTES = 32;

    private final Map<Integer, SecretKey> keysByVersion;
    private final int currentKeyVersion;

    /**
     * @param rawKeysByVersion  版本 → 32 bytes 金鑰。傳入的陣列會被複製
     * @param currentKeyVersion 新資料使用的版本，必須存在於 {@code rawKeysByVersion}
     */
    public SecretCipher(Map<Integer, byte[]> rawKeysByVersion, int currentKeyVersion) {
        if (rawKeysByVersion == null || rawKeysByVersion.isEmpty()) {
            throw new IllegalArgumentException("at least one encryption key is required");
        }

        Map<Integer, SecretKey> keys = new HashMap<>();
        rawKeysByVersion.forEach((version, raw) -> {
            int length = raw == null ? 0 : raw.length;
            if (length != KEY_LENGTH_BYTES) {
                throw new IllegalArgumentException(
                        "encryption key version " + version + " must be exactly "
                                + KEY_LENGTH_BYTES + " bytes (AES-256), got " + length);
            }
            keys.put(version, new SecretKeySpec(raw.clone(), KEY_ALGORITHM));
        });

        if (!keys.containsKey(currentKeyVersion)) {
            throw new IllegalArgumentException(
                    "current key version " + currentKeyVersion + " is not present in the configured keys "
                            + keys.keySet());
        }

        this.keysByVersion = Map.copyOf(keys);
        this.currentKeyVersion = currentKeyVersion;
    }

    /** 以當前版本的金鑰加密。 */
    public EncryptedSecret encrypt(String plaintext, String aad) {
        byte[] iv = Ids.randomBytes(IV_LENGTH_BYTES);
        try {
            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, currentKeyVersion, iv, aad);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(ciphertext, iv, currentKeyVersion);
        } catch (GeneralSecurityException e) {
            // 不把明文或金鑰放進訊息
            throw new SecretCipherException("failed to encrypt secret with key version " + currentKeyVersion, e);
        }
    }

    /**
     * 以指定版本的金鑰解密。
     *
     * <p>金鑰錯誤、AAD 不符、密文或 IV 被竄改都會拋例外，<strong>不會回傳垃圾資料</strong>。
     */
    public String decrypt(byte[] ciphertext, byte[] iv, int keyVersion, String aad) {
        try {
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, keyVersion, iv, aad);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new SecretCipherException(
                    "failed to decrypt secret with key version " + keyVersion
                            + " (wrong key, wrong AAD, or tampered data)", e);
        }
    }

    /** 目前設定中可用的金鑰版本，輪替期間會有兩個。 */
    public Set<Integer> keyVersions() {
        return keysByVersion.keySet();
    }

    public int currentKeyVersion() {
        return currentKeyVersion;
    }

    private Cipher newCipher(int mode, int keyVersion, byte[] iv, String aad) throws GeneralSecurityException {
        SecretKey key = keysByVersion.get(keyVersion);
        if (key == null) {
            throw new SecretCipherException(
                    "no encryption key configured for version " + keyVersion
                            + "; available versions: " + keysByVersion.keySet());
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
