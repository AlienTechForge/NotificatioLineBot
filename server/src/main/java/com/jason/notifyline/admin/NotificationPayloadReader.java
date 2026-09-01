package com.jason.notifyline.admin;

import com.jason.notifyline.notification.dispatch.PayloadEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@code notification.payload} 翻成後台看得到的「發送內容」。
 *
 * <p>存進去的格式見 {@link PayloadEnvelope}：一個信封，裡面的 {@code messages}
 * 是原樣要送給 LINE 的 message object 陣列。後台的發送紀錄要回答的問題是
 * 「那則通知到底寫了什麼」，所以這裡把信封拆開，逐則取出型別與（文字訊息的）內文，
 * 另外附上該則的格式化 JSON —— 進階模式送的 flex / template 訊息沒有單一「內文」
 * 可言，只有原始物件看得出全貌。
 *
 * <h2>解析失敗不是錯誤</h2>
 *
 * <p>payload 可能是 {@code null}（呼叫端指定 {@code persistPayload=false}，或過了
 * 90 天保留期被 {@code clearPayloadsBefore} 清空），也可能是舊版本寫進去、與現在的
 * 信封格式對不起來的字串。這兩種都<strong>不</strong>該讓「查看發送紀錄」整頁失敗 ——
 * 紀錄的其他欄位（狀態、批次、成敗計數）仍然有價值。因此這裡一律回傳一個帶狀態碼的
 * 結果讓前端說明原因，而不是往上拋。
 */
@Component
public class NotificationPayloadReader {

    private static final Logger log = LoggerFactory.getLogger(NotificationPayloadReader.class);

    /** 列表那一欄的節錄長度。夠認出是哪一則，又不會把表格撐開。 */
    static final int PREVIEW_MAX_LENGTH = 120;

    /** message object 缺 {@code type} 時的顯示值（正常路徑不會發生，{@code MessageAssembler} 會擋）。 */
    private static final String UNKNOWN_TYPE = "unknown";

    private static final String TEXT_TYPE = "text";

    private final ObjectMapper objectMapper;

    /** 縮排輸出用。建一次就好 —— 一頁明細會用它序列化每一則訊息。 */
    private final ObjectWriter prettyWriter;

    public NotificationPayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.prettyWriter = objectMapper.writerWithDefaultPrettyPrinter();
    }

    /**
     * 完整內容，供發送明細對話框顯示。
     *
     * @param payload {@code notification.payload} 的原始字串，可為 null
     */
    public AdminDto.NotificationContent read(String payload) {
        if (payload == null || payload.isBlank()) {
            return AdminDto.NotificationContent.cleared();
        }

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            // 只記 message，不記 payload 本身 —— 內容可能含呼叫端誤送的機密
            log.warn("發送紀錄的 payload 不是合法 JSON，改以「無法解析」顯示：{}", e.getMessage());
            return AdminDto.NotificationContent.unreadable();
        }

        JsonNode messages = envelope.path(PayloadEnvelope.MESSAGES);
        if (!messages.isArray()) {
            log.warn("發送紀錄的 payload 缺少 messages 陣列，改以「無法解析」顯示");
            return AdminDto.NotificationContent.unreadable();
        }

        List<AdminDto.NotificationMessage> parsed = new ArrayList<>();
        int index = 0;
        for (JsonNode message : messages) {
            parsed.add(toMessage(index++, message));
        }
        return AdminDto.NotificationContent.of(
                envelope.path(PayloadEnvelope.NOTIFICATION_DISABLED).asBoolean(false),
                List.copyOf(parsed));
    }

    /**
     * 列表用的單行節錄。
     *
     * @return 節錄字串，或 {@code null} 代表沒有內容可顯示（已清空或解析不出來）——
     *         列表只有一欄的寬度，「為什麼看不到」留給明細對話框說明
     */
    public String preview(String payload) {
        return preview(read(payload));
    }

    /** 已經讀好的內容轉節錄，避免同一筆 payload 解析兩次。 */
    public String preview(AdminDto.NotificationContent content) {
        if (!content.available() || content.messages().isEmpty()) {
            return null;
        }

        AdminDto.NotificationMessage first = content.messages().getFirst();
        String base = first.text() != null ? collapse(first.text()) : "(" + first.type() + ")";
        String suffix = content.messages().size() > 1
                ? "（共 " + content.messages().size() + " 則）"
                : "";

        // 先截斷再接後綴：後綴是「還有幾則」的重要資訊，不該被自己的截斷吃掉
        return truncate(base, PREVIEW_MAX_LENGTH) + suffix;
    }

    private AdminDto.NotificationMessage toMessage(int index, JsonNode message) {
        String type = message.path("type").isString()
                ? message.path("type").asString()
                : UNKNOWN_TYPE;

        // text 只有文字訊息有。其餘型別（flex、template…）的全貌只在 json 裡
        String text = TEXT_TYPE.equals(type) && message.path(TEXT_TYPE).isString()
                ? message.path(TEXT_TYPE).asString()
                : null;

        return new AdminDto.NotificationMessage(index, type, text, pretty(message));
    }

    /** 縮排過的 JSON，直接貼進 {@code <pre>} 就能讀。 */
    private String pretty(JsonNode message) {
        return prettyWriter.writeValueAsString(message);
    }

    /** 換行、tab 一律壓成單一空白 —— 表格的一列只有一行高度。 */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
