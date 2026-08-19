package com.jason.notifyline.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 識別碼與機密值的隨機產生。全部走 {@link java.security.SecureRandom}。
 *
 * <p>長度規格見 {@code Docs/plan/03-權限與認證設計.md} §5.1。
 */
@DisplayName("Ids")
class IdsTest {

    private static final Base64.Decoder URL64 = Base64.getUrlDecoder();

    // -------------------------------------------------------------- client id

    @Test
    @DisplayName("newClientId：cli_ 前綴 + 20 字元")
    void newClientId_format_isPrefixPlus20Chars() {
        String id = Ids.newClientId();

        assertThat(id).startsWith("cli_").hasSize(24);
    }

    @Test
    @DisplayName("newClientId：只含小寫英數，避免大小寫與可讀性問題")
    void newClientId_charset_isLowercaseAlphanumeric() {
        for (int i = 0; i < 200; i++) {
            assertThat(Ids.newClientId()).matches("cli_[0-9a-z]{20}");
        }
    }

    @Test
    @DisplayName("newClientId：大量產生不重複")
    void newClientId_manyCalls_areUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            assertThat(seen.add(Ids.newClientId())).isTrue();
        }
    }

    // ---------------------------------------------------------- client secret

    @Test
    @DisplayName("newClientSecret：base64url 無 padding，解碼後 48 bytes")
    void newClientSecret_decodesTo48Bytes() {
        String secret = Ids.newClientSecret();

        assertThat(secret).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(URL64.decode(secret)).hasSize(48);
    }

    @Test
    @DisplayName("newClientSecret：大量產生不重複")
    void newClientSecret_manyCalls_areUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            assertThat(seen.add(Ids.newClientSecret())).isTrue();
        }
    }

    // ------------------------------------------------------- enrollment token

    @Test
    @DisplayName("newEnrollmentToken：base64url 無 padding，解碼後 32 bytes（256 bits 熵）")
    void newEnrollmentToken_decodesTo32Bytes() {
        String token = Ids.newEnrollmentToken();

        assertThat(token).doesNotContain("=");
        assertThat(URL64.decode(token)).hasSize(32);
    }

    @Test
    @DisplayName("newEnrollmentToken：可安全放進 URL path（不含需跳脫的字元）")
    void newEnrollmentToken_isUrlPathSafe() {
        for (int i = 0; i < 200; i++) {
            assertThat(Ids.newEnrollmentToken()).matches("[A-Za-z0-9_-]+");
        }
    }

    // ------------------------------------------------------------ randomBytes

    @Test
    @DisplayName("randomBytes：長度正確且不同呼叫結果不同")
    void randomBytes_lengthAndUniqueness() {
        assertThat(Ids.randomBytes(12)).hasSize(12);
        assertThat(Ids.randomBytes(32)).isNotEqualTo(Ids.randomBytes(32));
    }

    @Test
    @DisplayName("randomBytes：長度非正數時拒絕")
    void randomBytes_nonPositiveLength_throws() {
        assertThatThrownBy(() -> Ids.randomBytes(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ids.randomBytes(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- sha256

    @Test
    @DisplayName("sha256：enrollment token 只存雜湊，同輸入同輸出")
    void sha256_isDeterministicAnd32Bytes() {
        String token = Ids.newEnrollmentToken();

        assertThat(Ids.sha256(token)).hasSize(32).isEqualTo(Ids.sha256(token));
        assertThat(Ids.sha256(token)).isNotEqualTo(Ids.sha256(token + "x"));
    }
}
