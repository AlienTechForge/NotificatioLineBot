package com.jason.notifyline.monitor.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

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

    // ============================================================ W3：狀態轉換

    @Nested
    @DisplayName("lease")
    class LeaseTest {

        @Test
        @DisplayName("把 next_run_at 推到租約時間")
        void pushesNextRunAt() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            Instant leaseUntil = T0.plusSeconds(120);

            monitor.lease(leaseUntil);

            assertThat(monitor.getNextRunAt()).isEqualTo(leaseUntil);
        }
    }

    @Nested
    @DisplayName("recordSuccess / recordFailure")
    class RecordOutcomeTest {

        @Test
        @DisplayName("recordSuccess：重置失敗計數與失敗通知旗標，排下一次執行時間")
        void recordSuccess_resetsFailureStateAndSchedulesNext() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            monitor.recordFailure(T0.plusSeconds(10), 60);
            monitor.recordFailure(T0.plusSeconds(20), 60);
            monitor.markFailureNotified();

            Instant now = T0.plusSeconds(100);
            monitor.recordSuccess(now, 60);

            assertThat(monitor.getLastRunAt()).isEqualTo(now);
            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(60));
            assertThat(monitor.getConsecutiveFailures()).isZero();
            assertThat(monitor.isFailureNotified()).isFalse();
        }

        @Test
        @DisplayName("recordSuccess 用呼叫端算好的有效間隔，不是監控自己的 interval_seconds")
        void recordSuccess_usesEffectiveInterval() {
            ApiMonitor monitor = newMonitor("GET", 30, CompareMode.WHOLE_BODY, null, null);

            Instant now = T0.plusSeconds(500);
            monitor.recordSuccess(now, 300); // 服務層的 min-interval 比監控設定的 30 秒還高

            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(300));
        }

        @Test
        @DisplayName("recordFailure 退避倍數：1/2/3 次失敗依 2^(n-1) 遞增")
        void recordFailure_backoffMultiplier_increasesExponentially() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            Instant now = T0.plusSeconds(1000);

            monitor.recordFailure(now, 60);
            assertThat(monitor.getConsecutiveFailures()).isEqualTo(1);
            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(60)); // ×1

            monitor.recordFailure(now, 60);
            assertThat(monitor.getConsecutiveFailures()).isEqualTo(2);
            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(120)); // ×2

            monitor.recordFailure(now, 60);
            assertThat(monitor.getConsecutiveFailures()).isEqualTo(3);
            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(240)); // ×4
        }

        @Test
        @DisplayName("recordFailure 退避倍數：連續失敗達 5 次以上封頂在 ×16")
        void recordFailure_backoffMultiplier_capsAt16() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            Instant now = T0.plusSeconds(1000);

            for (int i = 0; i < 4; i++) {
                monitor.recordFailure(now, 60); // 走到第 4 次，倍數應是 ×8
            }
            assertThat(monitor.getConsecutiveFailures()).isEqualTo(4);
            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(60 * 8));

            monitor.recordFailure(now, 60); // 第 5 次：達封頂 ×16
            assertThat(monitor.getConsecutiveFailures()).isEqualTo(5);
            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(60 * 16));

            monitor.recordFailure(now, 60); // 第 6 次：仍是 ×16，不會繼續往上長
            assertThat(monitor.getConsecutiveFailures()).isEqualTo(6);
            assertThat(monitor.getNextRunAt()).isEqualTo(now.plusSeconds(60 * 16));
        }

        @Test
        @DisplayName("shouldNotifyFailure：未達門檻或已通知過都回 false")
        void shouldNotifyFailure_thresholdAndFlagGate() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            Instant now = T0.plusSeconds(1000);

            monitor.recordFailure(now, 60);
            monitor.recordFailure(now, 60);
            assertThat(monitor.shouldNotifyFailure(3)).isFalse(); // 還沒達門檻

            monitor.recordFailure(now, 60);
            assertThat(monitor.shouldNotifyFailure(3)).isTrue(); // 達門檻且還沒通知過

            monitor.markFailureNotified();
            assertThat(monitor.shouldNotifyFailure(3)).isFalse(); // 已經通知過這次故障

            monitor.recordFailure(now, 60); // 第 4 次失敗，仍是同一次故障
            assertThat(monitor.shouldNotifyFailure(3)).isFalse();
        }
    }

    @Nested
    @DisplayName("防洗版：冷卻與每日上限")
    class AntiSpamTest {

        @Test
        @DisplayName("isCooldownActive：冷卻期間內為 true，過了冷卻期為 false")
        void isCooldownActive_reflectsCooldownWindow() {
            ApiMonitor monitor = new ApiMonitor(
                    "test", 1L, "https://example.com", "GET", null,
                    null, null, null,
                    60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                    "{{value.x}}", true, 300, null, T0);
            monitor.markNotified(T0);

            assertThat(monitor.isCooldownActive(T0.plusSeconds(100))).isTrue();
            assertThat(monitor.isCooldownActive(T0.plusSeconds(301))).isFalse();
        }

        @Test
        @DisplayName("cooldown_seconds = 0：從不冷卻")
        void isCooldownActive_zeroCooldown_neverActive() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            monitor.markNotified(T0);

            assertThat(monitor.isCooldownActive(T0.plusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("rolloverNotifiedDayIfNeeded：換日時歸零計數")
        void rollover_resetsCountOnNewDay() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            LocalDate day1 = LocalDate.of(2026, 8, 18);
            monitor.rolloverNotifiedDayIfNeeded(day1);
            monitor.markNotified(T0);
            monitor.markNotified(T0);
            assertThat(monitor.getNotifiedCount()).isEqualTo(2);

            LocalDate day2 = LocalDate.of(2026, 8, 19);
            monitor.rolloverNotifiedDayIfNeeded(day2);

            assertThat(monitor.getNotifiedCount()).isZero();
            assertThat(monitor.getNotifiedDay()).isEqualTo(day2);
        }

        @Test
        @DisplayName("rolloverNotifiedDayIfNeeded：同一天呼叫多次不影響既有計數")
        void rollover_sameDayIsNoop() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            LocalDate day = LocalDate.of(2026, 8, 18);
            monitor.rolloverNotifiedDayIfNeeded(day);
            monitor.markNotified(T0);

            monitor.rolloverNotifiedDayIfNeeded(day);

            assertThat(monitor.getNotifiedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("hasReachedDailyCap：null 上限代表不限；達到上限才回 true")
        void hasReachedDailyCap_respectsNullAndThreshold() {
            ApiMonitor unlimited = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            unlimited.rolloverNotifiedDayIfNeeded(LocalDate.of(2026, 8, 18));
            for (int i = 0; i < 100; i++) {
                unlimited.markNotified(T0);
            }
            assertThat(unlimited.hasReachedDailyCap()).isFalse();

            ApiMonitor capped = new ApiMonitor(
                    "test", 1L, "https://example.com", "GET", null,
                    null, null, null,
                    60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                    "{{value.x}}", true, 0, 2, T0);
            capped.rolloverNotifiedDayIfNeeded(LocalDate.of(2026, 8, 18));
            assertThat(capped.hasReachedDailyCap()).isFalse();
            capped.markNotified(T0);
            assertThat(capped.hasReachedDailyCap()).isFalse();
            capped.markNotified(T0);
            assertThat(capped.hasReachedDailyCap()).isTrue();
        }

        @Test
        @DisplayName("markNotified：累加計數、更新 last_notified_at")
        void markNotified_updatesCounters() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);

            monitor.markNotified(T0.plusSeconds(5));

            assertThat(monitor.getLastNotifiedAt()).isEqualTo(T0.plusSeconds(5));
            assertThat(monitor.getNotifiedCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("applyFingerprint")
    class ApplyFingerprintTest {

        @Test
        @DisplayName("寫回指紋與 last_state，並防禦性複製指紋位元組")
        void writesBackFingerprintAndState() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            byte[] fingerprint = {1, 2, 3};

            monitor.applyFingerprint(fingerprint, "{\"status\":\"OK\"}");
            fingerprint[0] = 99;

            assertThat(monitor.getLastFingerprint()).containsExactly(1, 2, 3);
            assertThat(monitor.getLastState()).isEqualTo("{\"status\":\"OK\"}");
        }

        @Test
        @DisplayName("null 指紋（NEW_ITEMS 模式）寫回後 getter 也是 null")
        void nullFingerprint_roundTrips() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);

            monitor.applyFingerprint(null, "{}");

            assertThat(monitor.getLastFingerprint()).isNull();
        }
    }

    // ============================================================ W4：後台編輯

    @Nested
    @DisplayName("applyUpdate")
    class ApplyUpdateTest {

        @Test
        @DisplayName("整份取代設定欄位，比對基準不動（fingerprint 保留）")
        void replacesConfigButKeepsComparisonBaseline() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            monitor.applyFingerprint(new byte[]{9, 9}, "{\"x\":\"1\"}");
            monitor.recordFailure(T0.plusSeconds(10), 60);

            Instant now = T0.plusSeconds(500);
            monitor.applyUpdate(
                    "renamed", 2L, "https://example.org/new", "post", "{\"q\":1}",
                    90, false, CompareMode.EXTRACTED, "[{\"name\":\"x\",\"pointer\":\"/x\"}]",
                    null, null, "{{value.x}}", false, 30, 5, now);

            assertThat(monitor.getName()).isEqualTo("renamed");
            assertThat(monitor.getClientId()).isEqualTo(2L);
            assertThat(monitor.getUrl()).isEqualTo("https://example.org/new");
            assertThat(monitor.getMethod()).isEqualTo("POST");
            assertThat(monitor.getRequestBody()).isEqualTo("{\"q\":1}");
            assertThat(monitor.getIntervalSeconds()).isEqualTo(90);
            assertThat(monitor.isEnabled()).isFalse();
            assertThat(monitor.getCompareMode()).isEqualTo(CompareMode.EXTRACTED);
            assertThat(monitor.getCooldownSeconds()).isEqualTo(30);
            assertThat(monitor.getMaxNotificationsPerDay()).isEqualTo(5);
            assertThat(monitor.isNotifyOnFailure()).isFalse();
            assertThat(monitor.getUpdatedAt()).isEqualTo(now);

            // 比對基準不受編輯影響——清掉它會讓下一輪把整包內容當成新的而發假通知。
            assertThat(monitor.getLastFingerprint()).containsExactly(9, 9);
            assertThat(monitor.getLastState()).isEqualTo("{\"x\":\"1\"}");
        }

        @Test
        @DisplayName("正在退避時存檔：清掉退避、立刻可取件——使用者改設定就是在說「再試一次」")
        void clearsBackoffSoTheFixCanTakeEffectImmediately() {
            ApiMonitor monitor = newMonitor("GET", 1800, CompareMode.WHOLE_BODY, null, null);
            // 失敗 5 次 = 退避封頂 ×16：間隔 1800 秒的監控要 8 小時後才會再動一次
            Instant failedAt = T0.plusSeconds(10);
            for (int i = 0; i < 5; i++) {
                monitor.recordFailure(failedAt, 1800);
            }
            monitor.markFailureNotified();
            assertThat(monitor.getNextRunAt()).isEqualTo(failedAt.plusSeconds(1800 * 16));

            Instant now = T0.plusSeconds(500);
            monitor.applyUpdate(
                    "renamed", 1L, "https://example.org/fixed", "GET", null,
                    1800, true, CompareMode.WHOLE_BODY, "[]", null, null,
                    "{{value.x}}", true, 0, null, now);

            assertThat(monitor.getNextRunAt()).as("立刻可取件").isEqualTo(now);
            assertThat(monitor.getConsecutiveFailures()).isZero();
            assertThat(monitor.isFailureNotified()).as("下次真的壞掉時要能再通知一次").isFalse();
        }

        @Test
        @DisplayName("沒在退避時存檔：排程時間不動——改個 cooldown 不該把監控拉去立刻執行")
        void doesNotDisturbScheduleOfAHealthyMonitor() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            monitor.recordSuccess(T0.plusSeconds(10), 60);
            Instant nextRunBefore = monitor.getNextRunAt();

            monitor.applyUpdate(
                    "renamed", 1L, "https://example.com/api", "GET", null,
                    60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                    "{{value.x}}", true, 30, null, T0.plusSeconds(500));

            assertThat(monitor.getNextRunAt()).isEqualTo(nextRunBefore);
            assertThat(monitor.getConsecutiveFailures()).isZero();
        }

        @Test
        @DisplayName("驗證規則與建構子共用：method 不合法拒絕")
        void rejectsInvalidMethod() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);

            assertThatThrownBy(() -> monitor.applyUpdate(
                    "x", 1L, "https://example.com", "DELETE", null,
                    60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                    "{{value.x}}", true, 0, null, T0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DELETE");
        }

        @Test
        @DisplayName("驗證規則與建構子共用：NEW_ITEMS 缺 pointer 拒絕")
        void rejectsNewItemsWithoutPointers() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);

            assertThatThrownBy(() -> monitor.applyUpdate(
                    "x", 1L, "https://example.com", "GET", null,
                    60, true, CompareMode.NEW_ITEMS, "[]", null, null,
                    "{{item.x}}", true, 0, null, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("驗證規則與建構子共用：interval 低於 30 秒拒絕")
        void rejectsIntervalBelowMinimum() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);

            assertThatThrownBy(() -> monitor.applyUpdate(
                    "x", 1L, "https://example.com", "GET", null,
                    29, true, CompareMode.WHOLE_BODY, "[]", null, null,
                    "{{value.x}}", true, 0, null, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("applyHeaders")
    class ApplyHeadersTest {

        @Test
        @DisplayName("覆寫既有的 header 密文/IV/版本，並防禦性複製")
        void overwritesHeadersDefensively() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            byte[] cipher = {1, 2, 3};
            byte[] iv = {4, 5, 6};

            monitor.applyHeaders(cipher, iv, 2, T0.plusSeconds(1));
            cipher[0] = 99;
            iv[0] = 99;

            assertThat(monitor.getHeadersCiphertext()).containsExactly(1, 2, 3);
            assertThat(monitor.getHeadersIv()).containsExactly(4, 5, 6);
            assertThat(monitor.getHeadersKeyVersion()).isEqualTo(2);
            assertThat(monitor.getUpdatedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        @DisplayName("傳 null 清除既有 header")
        void nullClearsExistingHeaders() {
            ApiMonitor monitor = new ApiMonitor(
                    "test", 1L, "https://example.com", "GET", null,
                    new byte[]{1}, new byte[]{2}, 1,
                    60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                    "{{value.x}}", true, 0, null, T0);

            monitor.applyHeaders(null, null, null, T0.plusSeconds(1));

            assertThat(monitor.getHeadersCiphertext()).isNull();
            assertThat(monitor.getHeadersIv()).isNull();
            assertThat(monitor.getHeadersKeyVersion()).isNull();
        }
    }

    @Nested
    @DisplayName("setEnabled")
    class SetEnabledTest {

        @Test
        @DisplayName("切換 enabled，不動 next_run_at")
        void togglesEnabledWithoutTouchingSchedule() {
            ApiMonitor monitor = newMonitor("GET", 60, CompareMode.WHOLE_BODY, null, null);
            Instant nextRunBefore = monitor.getNextRunAt();

            monitor.setEnabled(false, T0.plusSeconds(1));

            assertThat(monitor.isEnabled()).isFalse();
            assertThat(monitor.getNextRunAt()).isEqualTo(nextRunBefore);
            assertThat(monitor.getUpdatedAt()).isEqualTo(T0.plusSeconds(1));

            monitor.setEnabled(true, T0.plusSeconds(2));
            assertThat(monitor.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("重新啟用退避中的監控：清掉退避，否則這個開關按了等於沒按")
        void reEnabling_clearsBackoff() {
            ApiMonitor monitor = newMonitor("GET", 1800, CompareMode.WHOLE_BODY, null, null);
            for (int i = 0; i < 5; i++) {
                monitor.recordFailure(T0.plusSeconds(10), 1800);
            }
            monitor.setEnabled(false, T0.plusSeconds(20));

            Instant now = T0.plusSeconds(30);
            monitor.setEnabled(true, now);

            assertThat(monitor.isEnabled()).isTrue();
            assertThat(monitor.getNextRunAt()).as("立刻可取件").isEqualTo(now);
            assertThat(monitor.getConsecutiveFailures()).isZero();
            assertThat(monitor.isFailureNotified()).isFalse();
        }

        @Test
        @DisplayName("已經是啟用狀態時再設一次啟用：不動排程——這不是「重新啟用」")
        void enablingAnAlreadyEnabledMonitor_doesNotDisturbSchedule() {
            ApiMonitor monitor = newMonitor("GET", 1800, CompareMode.WHOLE_BODY, null, null);
            monitor.recordFailure(T0.plusSeconds(10), 1800);
            Instant nextRunBefore = monitor.getNextRunAt();

            monitor.setEnabled(true, T0.plusSeconds(30));

            assertThat(monitor.getNextRunAt()).isEqualTo(nextRunBefore);
            assertThat(monitor.getConsecutiveFailures()).isEqualTo(1);
        }
    }
}
