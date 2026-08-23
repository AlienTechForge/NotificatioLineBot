package com.jason.notifyline.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Period;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MonitorProperties")
class MonitorPropertiesTest {

    private static MonitorProperties allNulls() {
        return new MonitorProperties(null, null, 0, null, null, null, null, 0, null, 0, null, null);
    }

    @Test
    @DisplayName("全部留空時套用與 application.yml 一致的預設值")
    void defaults_matchApplicationYml() {
        MonitorProperties props = allNulls();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.pollInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.claimLimit()).isEqualTo(5);
        assertThat(props.lease()).isEqualTo(Duration.ofMinutes(2));
        assertThat(props.minInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.maxBodyBytes()).isEqualTo(1_048_576);
        assertThat(props.allowedHosts()).isEmpty();
        assertThat(props.failureNotifyThreshold()).isEqualTo(3);
        assertThat(props.runRetention()).isEqualTo(Period.ofDays(14));
        assertThat(props.seenItemRetention()).isEqualTo(Period.ofDays(90));
    }

    @Test
    @DisplayName("enabled=false 明確保留（不是 null 才代表未關閉）")
    void enabled_explicitFalse_isRespected() {
        MonitorProperties props = new MonitorProperties(
                false, null, 0, null, null, null, null, 0, null, 0, null, null);

        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("allowedHostList：空白代表允許任何公開網域")
    void allowedHostList_blank_meansAnyPublicHost() {
        assertThat(allNulls().allowedHostList()).isEmpty();

        MonitorProperties blank = new MonitorProperties(
                null, null, 0, null, null, null, null, 0, "   ", 0, null, null);
        assertThat(blank.allowedHostList()).isEmpty();
    }

    @Test
    @DisplayName("allowedHostList：逗號分隔、去空白、轉小寫")
    void allowedHostList_parsesAndNormalizes() {
        MonitorProperties props = new MonitorProperties(
                null, null, 0, null, null, null, null, 0,
                " Example.com, API.Foo.COM ,, bar.com ", 0, null, null);

        assertThat(props.allowedHostList())
                .containsExactly("example.com", "api.foo.com", "bar.com");
    }

    @Test
    @DisplayName("非法數值（<=0）一律回退到預設值，不是拋例外")
    void nonPositiveNumbers_fallBackToDefaults() {
        MonitorProperties props = new MonitorProperties(
                null, null, -1, null, null, null, null, -100, null, -5, null, null);

        assertThat(props.claimLimit()).isEqualTo(5);
        assertThat(props.maxBodyBytes()).isEqualTo(1_048_576);
        assertThat(props.failureNotifyThreshold()).isEqualTo(3);
    }
}
