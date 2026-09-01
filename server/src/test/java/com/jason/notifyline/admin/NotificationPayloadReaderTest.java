package com.jason.notifyline.admin;

import com.jason.notifyline.notification.dispatch.PayloadEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationPayloadReader} 的單元測試——純記憶體的 JSON 解析，不需要 Spring。
 *
 * <p>重點在「看不到內容」的那幾條路徑：payload 被清空、格式對不起來、訊息不是文字。
 * 那些都<strong>不</strong>該讓發送紀錄整頁失敗，而是要回一個說得出原因的結果。
 */
@DisplayName("NotificationPayloadReader")
class NotificationPayloadReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationPayloadReader reader = new NotificationPayloadReader(objectMapper);

    /** 用跟正式路徑同一個 builder 產生 payload，避免測試裡另外手寫一份格式。 */
    private String payload(List<Map<String, Object>> messages, boolean notificationDisabled) {
        return objectMapper.writeValueAsString(
                PayloadEnvelope.build(messages, notificationDisabled, true));
    }

    private static Map<String, Object> text(String value) {
        return Map.of("type", "text", "text", value);
    }

    @Nested
    @DisplayName("讀得到內容時")
    class Available {

        @Test
        @DisplayName("文字訊息回內文，並附上原始 message object")
        void textMessage() {
            var content = reader.read(payload(List.of(text("部署完成\n版本 1.2.3")), false));

            assertThat(content.status()).isEqualTo(AdminDto.ContentStatus.AVAILABLE);
            assertThat(content.available()).isTrue();
            assertThat(content.notificationDisabled()).isFalse();
            assertThat(content.messages()).hasSize(1);

            var message = content.messages().getFirst();
            assertThat(message.index()).isZero();
            assertThat(message.type()).isEqualTo("text");
            assertThat(message.text()).isEqualTo("部署完成\n版本 1.2.3");
            assertThat(message.json()).contains("\"type\"").contains("部署完成");
        }

        @Test
        @DisplayName("非文字訊息沒有 text，全貌只在 json 裡")
        void nonTextMessage() {
            var flex = Map.<String, Object>of("type", "flex", "altText", "報表",
                    "contents", Map.of("type", "bubble"));

            var message = reader.read(payload(List.of(flex), false)).messages().getFirst();

            assertThat(message.type()).isEqualTo("flex");
            assertThat(message.text()).isNull();
            assertThat(message.json()).contains("bubble");
        }

        @Test
        @DisplayName("多則訊息依原順序回傳，index 從 0 起算")
        void keepsOrder() {
            var content = reader.read(payload(List.of(text("一"), text("二"), text("三")), false));

            assertThat(content.messages()).extracting(AdminDto.NotificationMessage::text)
                    .containsExactly("一", "二", "三");
            assertThat(content.messages()).extracting(AdminDto.NotificationMessage::index)
                    .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("靜音發送的旗標會一起帶出來")
        void notificationDisabled() {
            assertThat(reader.read(payload(List.of(text("嗨")), true)).notificationDisabled())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("讀不到內容時")
    class Unavailable {

        @Test
        @DisplayName("payload 已清空 → CLEARED，不是錯誤")
        void cleared() {
            assertThat(reader.read(null).status()).isEqualTo(AdminDto.ContentStatus.CLEARED);
            assertThat(reader.read("   ").status()).isEqualTo(AdminDto.ContentStatus.CLEARED);
            assertThat(reader.read(null).messages()).isEmpty();
        }

        @Test
        @DisplayName("不是合法 JSON → UNREADABLE，不往上拋")
        void notJson() {
            assertThat(reader.read("{ 這不是 JSON").status())
                    .isEqualTo(AdminDto.ContentStatus.UNREADABLE);
        }

        @Test
        @DisplayName("缺 messages 陣列 → UNREADABLE")
        void missingMessages() {
            assertThat(reader.read("{\"notificationDisabled\":false}").status())
                    .isEqualTo(AdminDto.ContentStatus.UNREADABLE);
            assertThat(reader.read("{\"messages\":{}}").status())
                    .isEqualTo(AdminDto.ContentStatus.UNREADABLE);
        }
    }

    @Nested
    @DisplayName("列表的節錄")
    class Preview {

        @Test
        @DisplayName("換行壓成空白，一列只佔一行")
        void collapsesNewlines() {
            assertThat(reader.preview(payload(List.of(text("部署完成\n版本 1.2.3")), false)))
                    .isEqualTo("部署完成 版本 1.2.3");
        }

        @Test
        @DisplayName("過長就截斷，並保留「共 N 則」的後綴")
        void truncates() {
            String longText = "字".repeat(NotificationPayloadReader.PREVIEW_MAX_LENGTH + 50);

            String preview = reader.preview(payload(List.of(text(longText), text("第二則")), false));

            assertThat(preview).startsWith("字").endsWith("…（共 2 則）");
            // 截斷發生在後綴之前：後綴是「還有幾則」的資訊，不該被自己的截斷吃掉
            assertThat(preview.indexOf('…')).isEqualTo(NotificationPayloadReader.PREVIEW_MAX_LENGTH);
        }

        @Test
        @DisplayName("非文字訊息用型別代替內文")
        void nonTextUsesType() {
            var sticker = Map.<String, Object>of("type", "sticker", "packageId", "1");

            assertThat(reader.preview(payload(List.of(sticker), false))).isEqualTo("(sticker)");
        }

        @Test
        @DisplayName("內容看不到時回 null —— 一欄的寬度放不下原因")
        void nullWhenUnavailable() {
            assertThat(reader.preview((String) null)).isNull();
            assertThat(reader.preview("{ 壞掉的 JSON")).isNull();
            assertThat(reader.preview(payload(List.of(), false))).isNull();
        }
    }
}
