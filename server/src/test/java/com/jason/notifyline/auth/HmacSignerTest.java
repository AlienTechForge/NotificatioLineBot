package com.jason.notifyline.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HMAC-SHA256 簽章。見 {@code Docs/plan/03-權限與認證設計.md} §2.2。
 *
 * <p>期望簽章值以 openssl 獨立產生：
 * <pre>
 * printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$secret" -binary | base64
 * </pre>
 */
@DisplayName("HmacSigner")
class HmacSignerTest {

    private static final String SECRET = "test-secret-do-not-use";
    private static final String TIMESTAMP = "1755500000";
    private static final String NONCE = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    /** POST /api/v1/notifications，body = {"target":{"type":"SELF"},"message":{"text":"hi"}} */
    private static final String CANONICAL_POST =
            "POST\n"
                    + "/api/v1/notifications\n"
                    + TIMESTAMP + "\n"
                    + NONCE + "\n"
                    + "d46ab9de5e14a82348bca47e62ccf831e987149ed9c2fa11b770542e28f79c68";
    private static final String SIGNATURE_POST = "9jfuWzfTGPDR3sLLZFPLRcxulNgNpVP5vP5Wb+i/Vjg=";

    /** GET /api/v1/notifications/abc，空 body */
    private static final String CANONICAL_GET =
            "GET\n"
                    + "/api/v1/notifications/abc\n"
                    + TIMESTAMP + "\n"
                    + NONCE + "\n"
                    + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SIGNATURE_GET = "uyQQCfvWrjLcKGxyEHChuhiTrO0YESWvE3gCt7ABGts=";

    // ------------------------------------------------------------------- sign

    @Test
    @DisplayName("sign：POST 測試向量產生固定簽章")
    void sign_postVector_matchesExpected() {
        assertThat(HmacSigner.sign(SECRET, CANONICAL_POST)).isEqualTo(SIGNATURE_POST);
    }

    @Test
    @DisplayName("sign：GET 空 body 測試向量產生固定簽章")
    void sign_getVector_matchesExpected() {
        assertThat(HmacSigner.sign(SECRET, CANONICAL_GET)).isEqualTo(SIGNATURE_GET);
    }

    @Test
    @DisplayName("sign：canonical string 以 UTF-8 bytes 簽，不受平台預設編碼影響")
    void sign_utf8Canonical_usesUtf8Bytes() {
        String canonical = "POST\n/x\n1\n2\n" + CanonicalRequest.sha256Hex(
                "中文".getBytes(StandardCharsets.UTF_8));

        assertThat(HmacSigner.sign(SECRET, canonical))
                .isEqualTo(HmacSigner.sign(SECRET, new String(
                        canonical.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("sign：secret 不同則簽章不同")
    void sign_differentSecret_producesDifferentSignature() {
        assertThat(HmacSigner.sign("another-secret", CANONICAL_POST)).isNotEqualTo(SIGNATURE_POST);
    }

    @Test
    @DisplayName("sign：canonical string 差一個字元，簽章就完全不同")
    void sign_oneCharDifference_producesDifferentSignature() {
        assertThat(HmacSigner.sign(SECRET, CANONICAL_POST + "x")).isNotEqualTo(SIGNATURE_POST);
    }

    @Test
    @DisplayName("sign：secret 為空時拒絕簽章")
    void sign_blankSecret_throws() {
        assertThatThrownBy(() -> HmacSigner.sign("", CANONICAL_POST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacSigner.sign(null, CANONICAL_POST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- verify

    @Test
    @DisplayName("verify：正確簽章通過")
    void verify_correctSignature_returnsTrue() {
        assertThat(HmacSigner.verify(SECRET, CANONICAL_POST, SIGNATURE_POST)).isTrue();
    }

    @Test
    @DisplayName("verify：簽章被竄改則失敗")
    void verify_tamperedSignature_returnsFalse() {
        String tampered = "A" + SIGNATURE_POST.substring(1);
        assertThat(HmacSigner.verify(SECRET, CANONICAL_POST, tampered)).isFalse();
    }

    @Test
    @DisplayName("verify：canonical string 被竄改則失敗")
    void verify_tamperedCanonical_returnsFalse() {
        assertThat(HmacSigner.verify(SECRET, CANONICAL_POST + " ", SIGNATURE_POST)).isFalse();
    }

    @Test
    @DisplayName("verify：secret 不對則失敗")
    void verify_wrongSecret_returnsFalse() {
        assertThat(HmacSigner.verify("wrong-secret", CANONICAL_POST, SIGNATURE_POST)).isFalse();
    }

    @Test
    @DisplayName("verify：格式不合法的 base64 回 false 而不是拋例外")
    void verify_malformedBase64_returnsFalseNotThrow() {
        assertThat(HmacSigner.verify(SECRET, CANONICAL_POST, "!!!not-base64!!!")).isFalse();
    }

    @Test
    @DisplayName("verify：null 或空簽章回 false 而不是拋例外")
    void verify_nullOrBlankSignature_returnsFalse() {
        assertThat(HmacSigner.verify(SECRET, CANONICAL_POST, null)).isFalse();
        assertThat(HmacSigner.verify(SECRET, CANONICAL_POST, "")).isFalse();
    }

    @Test
    @DisplayName("verify：長度不同的簽章回 false 而不是拋例外")
    void verify_wrongLengthSignature_returnsFalse() {
        assertThat(HmacSigner.verify(SECRET, CANONICAL_POST, "YWJj")).isFalse();
    }
}
