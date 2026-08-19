package com.jason.notifyline.notification.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PayloadEnvelope")
class PayloadEnvelopeTest {

    private static final List<Map<String, Object>> MESSAGES =
            List.of(Map.of("type", "text", "text", "hi"));

    @Test
    @DisplayName("內部旗標不會被轉發到 LINE")
    void internalFlagsAreNotForwarded() {
        var envelope = PayloadEnvelope.build(MESSAGES, false, false);

        assertThat(envelope).containsKey(PayloadEnvelope.PERSIST_PAYLOAD);
        assertThat(PayloadEnvelope.forwardable(envelope))
                .doesNotContainKey(PayloadEnvelope.PERSIST_PAYLOAD)
                .containsOnlyKeys(PayloadEnvelope.MESSAGES, PayloadEnvelope.NOTIFICATION_DISABLED);
    }

    @Test
    @DisplayName("messages 原樣轉發")
    void messagesForwardedVerbatim() {
        var envelope = PayloadEnvelope.build(MESSAGES, true, true);

        assertThat(PayloadEnvelope.forwardable(envelope))
                .containsEntry(PayloadEnvelope.MESSAGES, MESSAGES)
                .containsEntry(PayloadEnvelope.NOTIFICATION_DISABLED, true);
    }

    @Test
    @DisplayName("未知的頂層欄位不轉發 —— 它們不屬於 multicast 的請求格式")
    void unknownTopLevelKeysDropped() {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>(
                PayloadEnvelope.build(MESSAGES, false, true));
        envelope.put("to", List.of("Uspoofed"));
        envelope.put("somethingElse", 1);

        assertThat(PayloadEnvelope.forwardable(envelope))
                .doesNotContainKey("to")
                .doesNotContainKey("somethingElse");
    }

    @Test
    @DisplayName("沒有 persistPayload 欄位時預設保存 —— 清錯了無法復原")
    void persistDefaultsToTrue() {
        assertThat(PayloadEnvelope.persistPayload(Map.of())).isTrue();
        assertThat(PayloadEnvelope.persistPayload(Map.of(PayloadEnvelope.PERSIST_PAYLOAD, true)))
                .isTrue();
        assertThat(PayloadEnvelope.persistPayload(Map.of(PayloadEnvelope.PERSIST_PAYLOAD, false)))
                .isFalse();
    }
}
