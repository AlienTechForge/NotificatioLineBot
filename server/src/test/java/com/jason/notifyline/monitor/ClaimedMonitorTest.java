package com.jason.notifyline.monitor;

import com.jason.notifyline.monitor.domain.CompareMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ClaimedMonitor")
class ClaimedMonitorTest {

    @Test
    @DisplayName("lastState 含 null 值時仍建立不可變快照")
    void lastState_withNullValue_isPreservedAsImmutableSnapshot() {
        Map<String, String> persistedState = new LinkedHashMap<>();
        persistedState.put("availability", null);

        ClaimedMonitor claimed = new ClaimedMonitor(
                1L, "庫存", "https://example.com/stock", "GET", null, Map.of(),
                CompareMode.EXTRACTED, List.of(), null, null, "{{value.availability}}",
                null, persistedState, false, Set.of());
        persistedState.put("availability", "available");

        assertThat(claimed.lastState()).containsKey("availability");
        assertThat(claimed.lastState().get("availability")).isNull();
        assertThatThrownBy(() -> claimed.lastState().put("availability", "available"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
