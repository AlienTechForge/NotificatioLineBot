package com.jason.notifyline.config;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 加密金鑰的啟動期檢查（fail-fast）。
 *
 * <p>見 {@code Docs/plan/03-權限與認證設計.md} §3.3。
 *
 * <p><strong>寧可起不來，也不要跑起來之後每個請求都 500。</strong>
 * 金鑰有問題時所有認證都會壞掉，而症狀（「簽章不符」）離根因很遠，
 * 在啟動時大聲失敗才能讓人立刻知道要改什麼。
 */
@Configuration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {

    private static final Logger log = LoggerFactory.getLogger(CryptoConfig.class);

    private static final String KEY_HINT =
            "請設定 app.crypto.keys.<版本>（環境變數 APP_SECRET_ENC_KEY），"
                    + "值為 base64 編碼的 32 bytes。產生方式：openssl rand -base64 32";

    @Bean
    public SecretCipher secretCipher(CryptoProperties properties) {
        Map<Integer, String> configured = properties.nonBlankKeys();

        if (configured.isEmpty()) {
            throw new IllegalStateException("app.crypto.keys 未設定：至少需要一組加密金鑰。" + KEY_HINT);
        }

        Map<Integer, byte[]> decoded = new LinkedHashMap<>();
        configured.forEach((version, encoded) -> decoded.put(version, decodeKey(version, encoded)));

        if (!decoded.containsKey(properties.currentKeyVersion())) {
            throw new IllegalStateException(
                    "app.crypto.current-key-version=" + properties.currentKeyVersion()
                            + " 在 app.crypto.keys 中不存在。已設定的版本：" + decoded.keySet());
        }

        SecretCipher cipher = new SecretCipher(decoded, properties.currentKeyVersion());
        selfTest(cipher);

        log.info("Client secret 加密已就緒：目前版本 v{}，可解密版本 {}",
                cipher.currentKeyVersion(), cipher.keyVersions());
        return cipher;
    }

    private static byte[] decodeKey(int version, String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            // 訊息不含金鑰內容
            throw new IllegalStateException(
                    "app.crypto.keys." + version + " 不是合法的 base64。" + KEY_HINT, e);
        }
        if (key.length != SecretCipher.KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "app.crypto.keys." + version + " 解碼後為 " + key.length + " bytes，"
                            + "必須恰好是 " + SecretCipher.KEY_LENGTH_BYTES + " bytes（AES-256）。" + KEY_HINT);
        }
        return key;
    }

    /**
     * 啟動時實際跑一次 round-trip。
     *
     * <p>光檢查長度不夠 —— 這一步確認 JCE 供應者真的能用 AES-256-GCM
     * （某些受限的 JRE 發行版不行），而不是等到第一個請求進來才發現。
     */
    private static void selfTest(SecretCipher cipher) {
        String probe = "crypto-self-test";
        String aad = "startup-probe";
        try {
            EncryptedSecret encrypted = cipher.encrypt(probe, aad);
            String decrypted = cipher.decrypt(
                    encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), aad);
            if (!probe.equals(decrypted)) {
                throw new IllegalStateException("加解密 round-trip 結果不符");
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "加密金鑰無法完成 round-trip 自我檢查，服務不予啟動：" + e.getMessage(), e);
        }
    }
}
