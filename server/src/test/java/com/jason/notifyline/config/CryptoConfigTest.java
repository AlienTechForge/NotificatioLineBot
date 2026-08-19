package com.jason.notifyline.config;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 啟動期 fail-fast。見 {@code Docs/plan/03-權限與認證設計.md} §3.3。
 *
 * <p>寧可起不來，也不要跑起來之後每個請求都 500 —— 加密 key 有問題時，
 * 所有認證都會壞掉，而且症狀會出現在離根因很遠的地方。
 */
@DisplayName("CryptoConfig 啟動檢查")
class CryptoConfigTest {

    private static final String KEY_32 = base64Of(32, (byte) 0x11);
    private static final String KEY_32_ALT = base64Of(32, (byte) 0x22);
    private static final String KEY_16 = base64Of(16, (byte) 0x33);

    private static String base64Of(int length, byte fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    // CryptoConfig 自帶 @EnableConfigurationProperties，不需要額外的 auto-configuration
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CryptoConfig.class);

    @Test
    @DisplayName("32 bytes key：建立 SecretCipher 並通過 round-trip 自我檢查")
    void validKey_createsCipher() {
        runner.withPropertyValues(
                        "app.crypto.current-key-version=1",
                        "app.crypto.keys.1=" + KEY_32)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SecretCipher.class);

                    SecretCipher cipher = context.getBean(SecretCipher.class);
                    EncryptedSecret enc = cipher.encrypt("hello", "cli_x");
                    assertThat(cipher.decrypt(enc.ciphertext(), enc.iv(), enc.keyVersion(), "cli_x"))
                            .isEqualTo("hello");
                });
    }

    @Test
    @DisplayName("完全沒設定 key：拒絕啟動")
    void missingKey_contextFails() {
        runner.run(context ->
                assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("app.crypto"));
    }

    @Test
    @DisplayName("key 為空字串：視同未設定，拒絕啟動")
    void blankKey_contextFails() {
        runner.withPropertyValues(
                        "app.crypto.current-key-version=1",
                        "app.crypto.keys.1=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("key 長度不是 32 bytes：拒絕啟動")
    void wrongLengthKey_contextFails() {
        runner.withPropertyValues(
                        "app.crypto.current-key-version=1",
                        "app.crypto.keys.1=" + KEY_16)
                .run(context ->
                        assertThat(context).hasFailed()
                                .getFailure().hasStackTraceContaining("32"));
    }

    @Test
    @DisplayName("key 不是合法 base64：拒絕啟動")
    void malformedBase64Key_contextFails() {
        runner.withPropertyValues(
                        "app.crypto.current-key-version=1",
                        "app.crypto.keys.1=!!! not base64 !!!")
                .run(context ->
                        assertThat(context).hasFailed()
                                .getFailure().hasStackTraceContaining("base64"));
    }

    @Test
    @DisplayName("current-key-version 指向不存在的 key：拒絕啟動")
    void currentVersionMissing_contextFails() {
        runner.withPropertyValues(
                        "app.crypto.current-key-version=2",
                        "app.crypto.keys.1=" + KEY_32)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("多版本並存：以 current-key-version 加密，舊版本仍可解密")
    void multipleVersions_rotationSupported() {
        runner.withPropertyValues(
                        "app.crypto.current-key-version=2",
                        "app.crypto.keys.1=" + KEY_32,
                        "app.crypto.keys.2=" + KEY_32_ALT)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    SecretCipher cipher = context.getBean(SecretCipher.class);
                    assertThat(cipher.encrypt("x", "cli_x").keyVersion()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("空白的次要版本被忽略，不影響啟動")
    void blankSecondaryVersion_isIgnored() {
        runner.withPropertyValues(
                        "app.crypto.current-key-version=1",
                        "app.crypto.keys.1=" + KEY_32,
                        "app.crypto.keys.2=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SecretCipher.class).keyVersions()).containsExactly(1);
                });
    }
}
