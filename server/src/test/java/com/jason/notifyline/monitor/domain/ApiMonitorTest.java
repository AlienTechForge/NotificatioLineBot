package com.jason.notifyline.monitor.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ApiMonitor")
class ApiMonitorTest {

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");

    private static ApiMonitor newMonitor(String method, int intervalSeconds,
                                         CompareMode mode, String itemPointer, String itemKeyPointer) {
        return new ApiMonitor(
                "test monitor", 1L, "https://example.com/api", method, null,
                null, null, null,
                intervalSeconds, true, mode, "[]", itemPointer, itemKeyPointer,
                "{{value.status}}", true, 0, null, T0);
    }

    @Test
    @DisplayName("新建立的監控立刻可取件：next_run_at = now，計數器歸零")
    void newMonitor_isImmediatelyDue() {
        ApiMonitor monitor = newMonitor("GET", 30, CompareMode.WHOLE_BODY, null, null);

        assertThat(monitor.getNextRunAt()).isEqualTo(T0);
        assertThat(monitor.getCreatedAt()).isEqualTo(T0);
        assertThat(monitor.getUpdatedAt()).isEqualTo(T0);
        assertThat(monitor.getConsecutiveFailures()).isZero();
        assertThat(monitor.isFailureNotified()).isFalse();
        assertThat(monitor.getNotifiedCount()).isZero();
        assertThat(monitor.getId()).isNull();
    }

    @Test
    @DisplayName("method 一律正規化成大寫")
    void method_isUppercased() {
        ApiMonitor monitor = newMonitor("get", 30, CompareMode.WHOLE_BODY, null, null);

        assertThat(monitor.getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("method 為 null 時預設 GET")
    void method_nullDefaultsToGet() {
        ApiMonitor monitor = newMonitor(null, 30, CompareMode.WHOLE_BODY, null, null);

        assertThat(monitor.getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("method 只能是 GET 或 POST，抄 api_monitor_method_chk")
    void method_rejectsUnsupportedVerbs() {
        assertThatThrownBy(() -> newMonitor("DELETE", 30, CompareMode.WHOLE_BODY, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELETE");
    }

    @Test
    @DisplayName("interval 低於 30 秒拒絕，抄 api_monitor_interval_chk")
    void intervalSeconds_belowMinimum_rejected() {
        assertThatThrownBy(() -> newMonitor("GET", 29, CompareMode.WHOLE_BODY, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("29");

        assertThat(newMonitor("GET", 30, CompareMode.WHOLE_BODY, null, null).getIntervalSeconds())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("NEW_ITEMS 缺 item_pointer 或 item_key_pointer 時拒絕，抄 api_monitor_newitems_chk")
    void newItemsMode_requiresBothPointers() {
        assertThatThrownBy(() -> newMonitor("GET", 60, CompareMode.NEW_ITEMS, null, "/id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newMonitor("GET", 60, CompareMode.NEW_ITEMS, "/items", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newMonitor("GET", 60, CompareMode.NEW_ITEMS, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        ApiMonitor monitor = newMonitor("GET", 60, CompareMode.NEW_ITEMS, "/items", "/id");
        assertThat(monitor.getItemPointer()).isEqualTo("/items");
        assertThat(monitor.getItemKeyPointer()).isEqualTo("/id");
    }

    @Test
    @DisplayName("非 NEW_ITEMS 模式不要求 item pointer")
    void otherModes_dontRequirePointers() {
        assertThat(newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null)).isNotNull();
        assertThat(newMonitor("GET", 60, CompareMode.EXTRACTED, null, null)).isNotNull();
    }

    @Test
    @DisplayName("extractRules 為 null 時預設空陣列字串")
    void extractRules_nullDefaultsToEmptyArray() {
        ApiMonitor monitor = new ApiMonitor(
                "test", 1L, "https://example.com", "GET", null,
                null, null, null,
                60, true, CompareMode.WHOLE_BODY, null, null, null,
                "{{value.x}}", true, 0, null, T0);

        assertThat(monitor.getExtractRules()).isEqualTo("[]");
    }

    @Test
    @DisplayName("header 密文與 IV 是防禦性複製")
    void headerCiphertextAndIv_areDefensiveCopies() {
        byte[] cipher = {1, 2, 3};
        byte[] iv = {4, 5, 6};
        ApiMonitor monitor = new ApiMonitor(
                "test", 1L, "https://example.com", "GET", null,
                cipher, iv, 1,
                60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                "{{value.x}}", true, 0, null, T0);

        cipher[0] = 99;
        iv[0] = 99;
        assertThat(monitor.getHeadersCiphertext()).containsExactly(1, 2, 3);
        assertThat(monitor.getHeadersIv()).containsExactly(4, 5, 6);

        byte[] returnedCipher = monitor.getHeadersCiphertext();
        returnedCipher[0] = 42;
        assertThat(monitor.getHeadersCiphertext()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("header 密文為 null 時 getter 也回傳 null（無自訂 header 的監控）")
    void headerCiphertext_nullWhenNoCustomHeaders() {
        ApiMonitor monitor = newMonitor("GET", 30, CompareMode.WHOLE_BODY, null, null);

        assertThat(monitor.getHeadersCiphertext()).isNull();
        assertThat(monitor.getHeadersIv()).isNull();
    }

    @Test
    @DisplayName("toString 不輸出 URL 或 header 密文")
    void toString_doesNotLeakSensitiveData() {
        ApiMonitor monitor = new ApiMonitor(
                "my monitor", 1L, "https://example.com/secret-path?token=abc", "GET", null,
                new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, 1,
                60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                "{{value.x}}", true, 0, null, T0);

        String text = monitor.toString();
        assertThat(text).contains("my monitor").contains("WHOLE_BODY");
        assertThat(text).doesNotContain("secret-path").doesNotContain("token=abc");
        assertThat(text).doesNotContain("[1, 2, 3]");
    }
}
