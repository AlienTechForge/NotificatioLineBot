package com.jason.notifyline.monitor.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CookieCodec")
class CookieCodecTest {

    // ------------------------------------------------------------ Cookie header

    @Test
    @DisplayName("解析 Cookie header：多組 name=value")
    void parseCookieHeader_multiplePairs() {
        Map<String, String> cookies = CookieCodec.parseCookieHeader("session=abc123; csrf=def456");

        assertThat(cookies).containsExactly(Map.entry("session", "abc123"), Map.entry("csrf", "def456"));
    }

    @Test
    @DisplayName("空白／null 輸入回傳空 map")
    void parseCookieHeader_blankOrNull_returnsEmpty() {
        assertThat(CookieCodec.parseCookieHeader(null)).isEmpty();
        assertThat(CookieCodec.parseCookieHeader("   ")).isEmpty();
        assertThat(CookieCodec.parseCookieHeader("")).isEmpty();
    }

    @Test
    @DisplayName("沒有 = 的片段跳過，不影響其餘片段解析")
    void parseCookieHeader_malformedSegment_isSkipped() {
        Map<String, String> cookies = CookieCodec.parseCookieHeader("a=1; malformed; b=2");

        assertThat(cookies).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
    }

    @Test
    @DisplayName("組裝回 Cookie header 值，保留插入順序")
    void serializeCookieHeader_preservesOrder() {
        Map<String, String> cookies = new LinkedHashMap<>();
        cookies.put("session", "abc");
        cookies.put("csrf", "def");

        assertThat(CookieCodec.serializeCookieHeader(cookies)).isEqualTo("session=abc; csrf=def");
    }

    // ------------------------------------------------------------ Set-Cookie header

    @Test
    @DisplayName("解析 Set-Cookie：name=value，沒有 Domain 屬性")
    void parseSetCookie_withoutDomain() {
        CookieCodec.SetCookieAttributes parsed = CookieCodec.parseSetCookie("session=xyz; Path=/; HttpOnly");

        assertThat(parsed.name()).isEqualTo("session");
        assertThat(parsed.value()).isEqualTo("xyz");
        assertThat(parsed.domain()).isNull();
    }

    @Test
    @DisplayName("解析 Set-Cookie：帶 Domain 屬性")
    void parseSetCookie_withDomain() {
        CookieCodec.SetCookieAttributes parsed =
                CookieCodec.parseSetCookie("session=xyz; Domain=example.com; Secure");

        assertThat(parsed.domain()).isEqualTo("example.com");
    }

    @Test
    @DisplayName("Domain 屬性的前導點視同不存在（舊式寫法 .example.com）")
    void parseSetCookie_leadingDotOnDomain_isStripped() {
        CookieCodec.SetCookieAttributes parsed =
                CookieCodec.parseSetCookie("session=xyz; Domain=.example.com");

        assertThat(parsed.domain()).isEqualTo("example.com");
    }

    @Test
    @DisplayName("Domain 屬性名稱大小寫不拘")
    void parseSetCookie_domainAttributeIsCaseInsensitive() {
        CookieCodec.SetCookieAttributes parsed =
                CookieCodec.parseSetCookie("session=xyz; domain=example.com");

        assertThat(parsed.domain()).isEqualTo("example.com");
    }

    @Test
    @DisplayName("沒有 = 的 Set-Cookie 回傳 null，讓呼叫端忽略這一筆")
    void parseSetCookie_malformed_returnsNull() {
        assertThat(CookieCodec.parseSetCookie("not-a-cookie")).isNull();
        assertThat(CookieCodec.parseSetCookie(null)).isNull();
        assertThat(CookieCodec.parseSetCookie("")).isNull();
    }

    // ------------------------------------------------------------ header 查找（大小寫不拘）

    @Test
    @DisplayName("findHeaderValueIgnoreCase：忽略大小寫找到值")
    void findHeaderValueIgnoreCase_findsRegardlessOfCase() {
        Map<String, String> headers = Map.of("Cookie", "a=1", "Accept", "application/json");

        assertThat(CookieCodec.findHeaderValueIgnoreCase(headers, "cookie")).contains("a=1");
        assertThat(CookieCodec.findHeaderValueIgnoreCase(headers, "COOKIE")).contains("a=1");
    }

    @Test
    @DisplayName("findHeaderValueIgnoreCase：找不到回傳空 Optional")
    void findHeaderValueIgnoreCase_notFound_returnsEmpty() {
        assertThat(CookieCodec.findHeaderValueIgnoreCase(Map.of("Accept", "x"), "cookie")).isEmpty();
        assertThat(CookieCodec.findHeaderValueIgnoreCase(null, "cookie")).isEmpty();
    }

    @Test
    @DisplayName("withoutHeaderIgnoreCase：移除指定 header（大小寫不拘），其餘保留")
    void withoutHeaderIgnoreCase_removesMatchingKeyOnly() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "a=1");
        headers.put("Accept", "application/json");

        Map<String, String> result = CookieCodec.withoutHeaderIgnoreCase(headers, "cookie");

        assertThat(result).containsExactly(Map.entry("Accept", "application/json"));
    }
}
