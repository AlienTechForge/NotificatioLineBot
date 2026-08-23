package com.jason.notifyline.monitor.fetch;

import com.jason.notifyline.monitor.MonitorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboundUrlGuard")
class OutboundUrlGuardTest {

    private static final String HOST = "target.example";

    private static MonitorProperties propertiesWithAllowedHosts(String allowedHosts) {
        return new MonitorProperties(
                null, null, 0, null, null, null, null, 0, allowedHosts, 0, null, null);
    }

    private static MonitorProperties emptyAllowList() {
        return propertiesWithAllowedHosts(null);
    }

    private static InetAddress ip(String literal) {
        try {
            // 字面量位址（IPv4 點分十進位 / IPv6 文字表示法），不觸發真正的 DNS 查詢。
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad literal in test: " + literal, e);
        }
    }

    private static OutboundUrlGuard guardResolving(MonitorProperties properties, InetAddress... addresses) {
        FakeDnsResolver resolver = new FakeDnsResolver().with(HOST, addresses);
        return new OutboundUrlGuard(properties, resolver);
    }

    private static URI targetUri() {
        return URI.create("https://" + HOST + "/data");
    }

    // ------------------------------------------------------------ scheme

    @Test
    @DisplayName("http scheme 一律拒絕")
    void httpScheme_rejected() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("8.8.8.8"));

        assertThatThrownBy(() -> guard.check(URI.create("http://" + HOST + "/data")))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    @Test
    @DisplayName("https scheme 且位址為公開 IP 時放行")
    void httpsScheme_publicAddress_allowed() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("8.8.8.8"));

        assertThatCode(() -> guard.check(targetUri())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("host 無法解析（例如底線造成 URI#getHost 回 null）時拒絕")
    void unparsableHost_rejected() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("8.8.8.8"));

        assertThatThrownBy(() -> guard.check(URI.create("https://exa_mple.com/data")))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    // ------------------------------------------------------------ 封鎖範圍：IPv4

    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("blockedIpv4Ranges")
    @DisplayName("IPv4 封鎖範圍逐一拒絕")
    void blockedIpv4Ranges_rejected(String rangeLabel, String literal) {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip(literal));

        assertThatThrownBy(() -> guard.check(targetUri()))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    static Stream<Arguments> blockedIpv4Ranges() {
        return Stream.of(
                Arguments.of("0.0.0.0/8", "0.1.2.3"),
                Arguments.of("10.0.0.0/8", "10.1.2.3"),
                Arguments.of("100.64.0.0/10 (CGNAT)", "100.64.1.1"),
                Arguments.of("127.0.0.0/8", "127.0.0.1"),
                Arguments.of("169.254.0.0/16 (metadata)", "169.254.169.254"),
                Arguments.of("172.16.0.0/12", "172.16.5.5"),
                Arguments.of("192.0.0.0/24", "192.0.0.5"),
                Arguments.of("192.0.2.0/24 (TEST-NET-1)", "192.0.2.5"),
                Arguments.of("192.168.0.0/16", "192.168.1.1"),
                Arguments.of("198.18.0.0/15", "198.18.0.5"),
                Arguments.of("198.51.100.0/24 (TEST-NET-2)", "198.51.100.5"),
                Arguments.of("203.0.113.0/24 (TEST-NET-3)", "203.0.113.5"),
                Arguments.of("224.0.0.0/4 (multicast)", "224.0.0.1"),
                Arguments.of("240.0.0.0/4 (reserved)", "240.0.0.1"),
                Arguments.of("255.255.255.255/32 (broadcast)", "255.255.255.255"));
    }

    // ------------------------------------------------------------ 封鎖範圍：IPv6

    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("blockedIpv6Ranges")
    @DisplayName("IPv6 封鎖範圍逐一拒絕")
    void blockedIpv6Ranges_rejected(String rangeLabel, String literal) {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip(literal));

        assertThatThrownBy(() -> guard.check(targetUri()))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    static Stream<Arguments> blockedIpv6Ranges() {
        return Stream.of(
                Arguments.of(":: (unspecified)", "::"),
                Arguments.of("::1 (loopback)", "::1"),
                Arguments.of("fc00::/7 (ULA)", "fc00::1"),
                Arguments.of("fe80::/10 (link-local)", "fe80::1"),
                Arguments.of("ff00::/8 (multicast)", "ff00::1"),
                Arguments.of("2001:db8::/32 (documentation)", "2001:db8::1"));
    }

    @Test
    @DisplayName("正常公開的 IPv6 位址放行")
    void publicIpv6_allowed() {
        // 2001:4860:4860::8888 是 Google 公開 DNS 的 IPv6 位址，不落在任何封鎖範圍。
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("2001:4860:4860::8888"));

        assertThatCode(() -> guard.check(targetUri())).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ IPv4-mapped / NAT64

    @Test
    @DisplayName("IPv4-mapped 位址 ::ffff:10.0.0.1 還原後命中內網範圍，拒絕")
    void ipv4Mapped_internal_rejected() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("::ffff:10.0.0.1"));

        assertThatThrownBy(() -> guard.check(targetUri()))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    @Test
    @DisplayName("IPv4-mapped 位址還原後是公開 IP 則放行")
    void ipv4Mapped_public_allowed() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("::ffff:8.8.8.8"));

        assertThatCode(() -> guard.check(targetUri())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NAT64 位址 64:ff9b::/96 內嵌內網 IPv4 還原後拒絕")
    void nat64_internal_rejected() {
        // 64:ff9b::0a00:0001 內嵌的 IPv4 是 10.0.0.1
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("64:ff9b::a00:1"));

        assertThatThrownBy(() -> guard.check(targetUri()))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    // ------------------------------------------------------------ 多筆 A 記錄

    @Test
    @DisplayName("多筆位址其中一筆是內網，即使排在後面也整個拒絕")
    void multipleAddresses_oneInternal_rejectsWholeRequest() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(),
                ip("8.8.8.8"), ip("1.1.1.1"), ip("10.0.0.5"));

        assertThatThrownBy(() -> guard.check(targetUri()))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    @Test
    @DisplayName("多筆位址全部公開才放行")
    void multipleAddresses_allPublic_allowed() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("8.8.8.8"), ip("1.1.1.1"));

        assertThatCode(() -> guard.check(targetUri())).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ 訊息不洩漏內部細節

    @Test
    @DisplayName("拒絕訊息不可包含實際解析到的內部位址")
    void blockedMessage_doesNotLeakResolvedAddress() {
        OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("169.254.169.254"));

        assertThatThrownBy(() -> guard.check(targetUri()))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("169.254.169.254"));
    }

    // ------------------------------------------------------------ DNS 解析失敗

    @Test
    @DisplayName("DNS 解析失敗時拒絕")
    void dnsResolutionFailure_rejected() {
        OutboundUrlGuard guard = new OutboundUrlGuard(emptyAllowList(), new FakeDnsResolver());

        assertThatThrownBy(() -> guard.check(targetUri()))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    // ------------------------------------------------------------ 白名單

    @Nested
    @DisplayName("host 白名單")
    class AllowList {

        @Test
        @DisplayName("空白名單允許任何公開網域")
        void emptyAllowList_permitsAnyPublicHost() {
            OutboundUrlGuard guard = guardResolving(emptyAllowList(), ip("8.8.8.8"));

            assertThatCode(() -> guard.check(targetUri())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("evil-example.com 不會被 example.com 的白名單放行")
        void similarButNotSubdomain_rejected() {
            FakeDnsResolver resolver = new FakeDnsResolver().with("evil-example.com", ip("8.8.8.8"));
            OutboundUrlGuard guard = new OutboundUrlGuard(propertiesWithAllowedHosts("example.com"), resolver);

            assertThatThrownBy(() -> guard.check(URI.create("https://evil-example.com/data")))
                    .isInstanceOf(OutboundUrlGuard.BlockedException.class);
        }

        @Test
        @DisplayName("api.example.com 算 example.com 的子網域，放行")
        void subdomain_allowed() {
            FakeDnsResolver resolver = new FakeDnsResolver().with("api.example.com", ip("8.8.8.8"));
            OutboundUrlGuard guard = new OutboundUrlGuard(propertiesWithAllowedHosts("example.com"), resolver);

            assertThatCode(() -> guard.check(URI.create("https://api.example.com/data"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("完全相等的 host 放行")
        void exactMatch_allowed() {
            FakeDnsResolver resolver = new FakeDnsResolver().with("example.com", ip("8.8.8.8"));
            OutboundUrlGuard guard = new OutboundUrlGuard(propertiesWithAllowedHosts("example.com"), resolver);

            assertThatCode(() -> guard.check(URI.create("https://example.com/data"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("不在白名單內的網域拒絕")
        void notListed_rejected() {
            FakeDnsResolver resolver = new FakeDnsResolver().with("other.test", ip("8.8.8.8"));
            OutboundUrlGuard guard = new OutboundUrlGuard(propertiesWithAllowedHosts("example.com"), resolver);

            assertThatThrownBy(() -> guard.check(URI.create("https://other.test/data")))
                    .isInstanceOf(OutboundUrlGuard.BlockedException.class);
        }
    }

    // ------------------------------------------------------------ IDN 同形字
    //
    // "аpple.com" 開頭是西里爾字母 а（U+0430），視覺上與拉丁字母 a 幾乎無法分辨。
    // java.net.URI 對 host 只接受 ASCII 語法 —— 網址裡直接放原始 Unicode 字元會讓
    // URI#getHost() 整個回 null（等同 requireHost() 那條防線就先擋下了，不會走到
    // normalizeHost）。所以「同形字繞過」實際能構造出來的攻擊面，是網址裡放
    // *已經編碼成 punycode* 的同形字網域（那是合法 ASCII hostname，能通過
    // URI 解析）—— 這正是下面兩個測試在驗證的東西。
    // IDN.toASCII("аpple.com", ALLOW_UNASSIGNED) = "xn--pple-43d.com"

    @Test
    @DisplayName("punycode 編碼的同形字網域不會被白名單裡視覺相似的 apple.com 放行")
    void idnHomographPunycode_notConflatedWithLookalike() {
        FakeDnsResolver resolver = new FakeDnsResolver().withAnyHost(ip("8.8.8.8"));
        OutboundUrlGuard guard = new OutboundUrlGuard(propertiesWithAllowedHosts("apple.com"), resolver);

        assertThatThrownBy(() -> guard.check(URI.create("https://xn--pple-43d.com/data")))
                .isInstanceOf(OutboundUrlGuard.BlockedException.class);
    }

    @Test
    @DisplayName("白名單裡用原始 Unicode 填的網域，正規化後仍可比對到請求端的 punycode 形式")
    void allowListEntry_rawUnicode_matchesRequestPunycodeForm() {
        // 白名單設定值可能是人手動貼上的原始 Unicode（例如從瀏覽器網址列複製），
        // 正規化（IDN.toASCII）要對白名單的每一筆設定值、以及請求端的 host
        // 套用同一套轉換，兩邊才會落在同一個比較基準上。
        FakeDnsResolver resolver = new FakeDnsResolver().withAnyHost(ip("8.8.8.8"));
        OutboundUrlGuard guard = new OutboundUrlGuard(propertiesWithAllowedHosts("аpple.com"), resolver);

        assertThatCode(() -> guard.check(URI.create("https://xn--pple-43d.com/data")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IDN 網域大小寫正規化後仍可比對到白名單")
    void idn_caseNormalization_stillMatches() {
        FakeDnsResolver resolver = new FakeDnsResolver().withAnyHost(ip("8.8.8.8"));
        OutboundUrlGuard guard = new OutboundUrlGuard(propertiesWithAllowedHosts("example.com"), resolver);

        assertThatCode(() -> guard.check(URI.create("https://API.EXAMPLE.COM/data")))
                .doesNotThrowAnyException();
    }
}
