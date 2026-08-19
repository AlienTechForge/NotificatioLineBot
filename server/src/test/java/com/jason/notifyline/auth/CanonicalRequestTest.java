package com.jason.notifyline.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Canonical string 的組串規格。見 {@code Docs/plan/03-權限與認證設計.md} §2.2。
 *
 * <p>本測試的核心價值是**寫死的測試向量**。canonical string 的任何細節改動
 * （欄位順序、分隔符、編碼、大小寫）都會讓所有呼叫端同時失效，而症狀只會是
 * 一句「簽章不符」，極難診斷。用固定期望值把契約釘死。
 *
 * <p>期望值以 openssl 獨立產生，不是從實作反推。
 */
@DisplayName("CanonicalRequest")
class CanonicalRequestTest {

    private static final String TIMESTAMP = "1755500000";
    private static final String NONCE = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String PATH = "/api/v1/notifications";

    private static final String BODY_JSON = "{\"target\":{\"type\":\"SELF\"},\"message\":{\"text\":\"hi\"}}";

    // openssl dgst -sha256 於各 body 上的實際輸出
    private static final String SHA256_BODY_JSON =
            "d46ab9de5e14a82348bca47e62ccf831e987149ed9c2fa11b770542e28f79c68";
    private static final String SHA256_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SHA256_CJK =
            "f1b70c5a4d0ebf91956c4a3a12de77b56968dafee94887d9632668a8b508d0fe";
    private static final String SHA256_WITH_NEWLINES =
            "8164669836e51c324aa26742645b519732d41a60b8047ebdea8e769ef8565d79";

    // ---------------------------------------------------------------- sha256Hex

    @Test
    @DisplayName("sha256Hex：一般 JSON body 產生固定雜湊")
    void sha256Hex_jsonBody_matchesVector() {
        assertThat(CanonicalRequest.sha256Hex(BODY_JSON.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(SHA256_BODY_JSON);
    }

    @Test
    @DisplayName("sha256Hex：空 body 是空位元組陣列的雜湊，不是空字串本身")
    void sha256Hex_emptyBody_hashesEmptyByteArray() {
        assertThat(CanonicalRequest.sha256Hex(new byte[0])).isEqualTo(SHA256_EMPTY);
    }

    @Test
    @DisplayName("sha256Hex：UTF-8 中文以 UTF-8 bytes 計算，不受平台預設編碼影響")
    void sha256Hex_utf8Body_usesUtf8Bytes() {
        assertThat(CanonicalRequest.sha256Hex("{\"text\":\"測試中文\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(SHA256_CJK);
    }

    @Test
    @DisplayName("sha256Hex：body 內的換行不做任何正規化")
    void sha256Hex_bodyWithNewlines_noNormalisation() {
        assertThat(CanonicalRequest.sha256Hex("{\n  \"a\": 1\n}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(SHA256_WITH_NEWLINES);
    }

    @Test
    @DisplayName("sha256Hex：輸出為小寫十六進位，長度 64")
    void sha256Hex_output_isLowercaseHex64() {
        String hex = CanonicalRequest.sha256Hex(BODY_JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
    }

    // -------------------------------------------------------- toCanonicalString

    @Test
    @DisplayName("toCanonicalString：五段以 \\n 串接，與規格範例逐字元相同")
    void toCanonicalString_fixedInput_matchesSpecExample() {
        String canonical = CanonicalRequest
                .of("POST", PATH, TIMESTAMP, NONCE, BODY_JSON.getBytes(StandardCharsets.UTF_8))
                .toCanonicalString();

        assertThat(canonical).isEqualTo(
                "POST\n"
                        + PATH + "\n"
                        + TIMESTAMP + "\n"
                        + NONCE + "\n"
                        + SHA256_BODY_JSON);
    }

    @Test
    @DisplayName("toCanonicalString：無結尾換行")
    void toCanonicalString_output_hasNoTrailingNewline() {
        String canonical = CanonicalRequest
                .of("POST", PATH, TIMESTAMP, NONCE, new byte[0])
                .toCanonicalString();

        assertThat(canonical).doesNotEndWith("\n");
        assertThat(canonical.split("\n", -1)).hasSize(5);
    }

    @Test
    @DisplayName("toCanonicalString：HTTP method 一律轉大寫")
    void toCanonicalString_lowercaseMethod_isUppercased() {
        String canonical = CanonicalRequest
                .of("post", PATH, TIMESTAMP, NONCE, new byte[0])
                .toCanonicalString();

        assertThat(canonical).startsWith("POST\n");
    }

    @Test
    @DisplayName("of：null body 視同空 body")
    void of_nullBody_treatedAsEmpty() {
        assertThat(CanonicalRequest.of("GET", PATH, TIMESTAMP, NONCE, null).bodySha256Hex())
                .isEqualTo(SHA256_EMPTY);
    }

    // ------------------------------------------------------------------ 防呆

    @Test
    @DisplayName("of：缺少必要欄位時拒絕建立")
    void of_missingComponent_throws() {
        byte[] body = new byte[0];
        assertThatThrownBy(() -> CanonicalRequest.of(null, PATH, TIMESTAMP, NONCE, body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalRequest.of("POST", "  ", TIMESTAMP, NONCE, body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalRequest.of("POST", PATH, "", NONCE, body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalRequest.of("POST", PATH, TIMESTAMP, null, body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString：只輸出可安全記錄的摘要，不傾印整包 canonical string")
    void toString_isLogSafeSummary() {
        String text = CanonicalRequest
                .of("POST", PATH, TIMESTAMP, NONCE, BODY_JSON.getBytes(StandardCharsets.UTF_8))
                .toString();

        assertThat(text).contains("POST").contains(PATH).contains(TIMESTAMP);
        assertThat(text).doesNotContain(SHA256_BODY_JSON).doesNotContain(NONCE);
    }

    @Test
    @DisplayName("record 建構子同樣拒絕空白欄位（不只 of 有防呆）")
    void canonicalConstructor_blankComponent_throws() {
        assertThatThrownBy(() ->
                new CanonicalRequest("POST", PATH, TIMESTAMP, NONCE, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("path 不含 query string —— 帶 query 時視為規格違反")
    void of_pathWithQueryString_throws() {
        assertThatThrownBy(() ->
                CanonicalRequest.of("POST", PATH + "?x=1", TIMESTAMP, NONCE, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }
}
