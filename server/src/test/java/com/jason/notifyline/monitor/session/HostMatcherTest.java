package com.jason.notifyline.monitor.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HostMatcher} 是這一波最危險的地方：把 A 站的 cookie 送到 B 站 = 把 session
 * token 洩漏給第三方。見 {@code Docs/plan/12-API監控易用性升級.md} §3.2。
 *
 * <p>{@link #evilPrefixHost_doesNotMatch()} 是這個類別存在的理由——這個案例曾經先用
 * 一個刻意天真的 {@code candidateHost.endsWith(scopeHost)} 實作跑過一次，
 * 確認會誤判失敗（{@code evil-example.com} 被判定「符合」{@code example.com}），
 * 才換成帶點號邊界的正確版本。
 */
@DisplayName("HostMatcher")
class HostMatcherTest {

    // ------------------------------------------------------------ 這是最重要的測試

    @Test
    @DisplayName("★ evil-example.com 不可誤判成 example.com 的子網域——絕不能用 endsWith(scopeHost)")
    void evilPrefixHost_doesNotMatch() {
        assertThat(HostMatcher.matches("evil-example.com", "example.com")).isFalse();
    }

    @Test
    @DisplayName("evil-example.com 的各種變形一樣不可誤判")
    void evilPrefixHostVariants_doNotMatch() {
        assertThat(HostMatcher.matches("notexample.com", "example.com")).isFalse();
        assertThat(HostMatcher.matches("xexample.com", "example.com")).isFalse();
        assertThat(HostMatcher.matches("example.com.evil.com", "example.com")).isFalse();
    }

    // ------------------------------------------------------------ 應該符合的案例

    @Test
    @DisplayName("完全相同的 host 符合")
    void exactHost_matches() {
        assertThat(HostMatcher.matches("example.com", "example.com")).isTrue();
    }

    @Test
    @DisplayName("子網域（帶點號邊界）符合")
    void subdomain_matches() {
        assertThat(HostMatcher.matches("api.example.com", "example.com")).isTrue();
    }

    @Test
    @DisplayName("多層子網域一樣符合")
    void deepSubdomain_matches() {
        assertThat(HostMatcher.matches("a.b.api.example.com", "example.com")).isTrue();
    }

    @Test
    @DisplayName("反過來：父網域不符合子網域的 scope（cookie 不該逆向擴張）")
    void parentHost_doesNotMatchSubdomainScope() {
        assertThat(HostMatcher.matches("example.com", "api.example.com")).isFalse();
    }

    // ------------------------------------------------------------ 正規化

    @Test
    @DisplayName("大小寫不影響比對")
    void caseInsensitive() {
        assertThat(HostMatcher.matches("API.EXAMPLE.COM", "example.com")).isTrue();
    }

    @Test
    @DisplayName("結尾多一個點（FQDN）不影響比對")
    void trailingDot_isIgnored() {
        assertThat(HostMatcher.matches("example.com.", "example.com")).isTrue();
    }

    @Test
    @DisplayName("IDN 同形字先轉 ASCII 再比對")
    void idnHost_normalizedBeforeCompare() {
        // xn--e1aybc.example.com 這種 punycode 形式應該正規化成同一個字串再比對。
        assertThat(HostMatcher.matches("xn--e1aybc.example.com", "example.com")).isTrue();
    }

    // ------------------------------------------------------------ 邊界

    @Test
    @DisplayName("null 一律不符合")
    void nullHost_doesNotMatch() {
        assertThat(HostMatcher.matches(null, "example.com")).isFalse();
        assertThat(HostMatcher.matches("example.com", null)).isFalse();
    }

    @Test
    @DisplayName("空字串一律不符合")
    void blankHost_doesNotMatch() {
        assertThat(HostMatcher.matches("", "example.com")).isFalse();
        assertThat(HostMatcher.matches("example.com", "")).isFalse();
    }
}
