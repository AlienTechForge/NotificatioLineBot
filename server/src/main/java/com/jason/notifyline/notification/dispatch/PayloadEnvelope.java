package com.jason.notifyline.notification.dispatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code notification.payload} 的內容格式。
 *
 * <p>存的是「要送給 LINE 的請求範本」，只缺 {@code to} —— 收件人在
 * {@code notification_delivery.line_user_ids}，因為每一批不同，而且 payload
 * 過了保留期會被清空，收件人卻是重試時唯一的來源。
 *
 * <h2>為什麼送出時只轉發白名單內的鍵</h2>
 *
 * <p>這個 envelope 混了兩種東西：要原樣送去 LINE 的（{@code messages}、
 * {@code notificationDisabled}），以及我們自己的處理旗標（{@code persistPayload}）。
 * 若送出時整包 {@code putAll} 過去，內部旗標就會跟著飛到 LINE 的 API。
 *
 * <p>白名單只涵蓋 multicast 的<strong>頂層</strong>欄位，那是一組小而穩定的集合。
 * 「原樣轉發」要保護的是 {@code messages} 陣列<strong>裡面</strong>的訊息物件 ——
 * 那裡一個位元組都不會被動到。
 */
public final class PayloadEnvelope {

    public static final String MESSAGES = "messages";
    public static final String NOTIFICATION_DISABLED = "notificationDisabled";
    public static final String PERSIST_PAYLOAD = "persistPayload";

    /** 送往 LINE multicast 時可以出現的頂層欄位（{@code to} 由派送器自行填入）。 */
    private static final List<String> FORWARDED_KEYS =
            List.of(MESSAGES, NOTIFICATION_DISABLED, "customAggregationUnits");

    private PayloadEnvelope() {
    }

    public static Map<String, Object> build(List<Map<String, Object>> messages,
                                            boolean notificationDisabled,
                                            boolean persistPayload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(MESSAGES, messages);
        envelope.put(NOTIFICATION_DISABLED, notificationDisabled);
        envelope.put(PERSIST_PAYLOAD, persistPayload);
        return envelope;
    }

    /** 從 envelope 取出要送給 LINE 的欄位。 */
    public static Map<String, Object> forwardable(Map<String, Object> envelope) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : FORWARDED_KEYS) {
            if (envelope.containsKey(key)) {
                out.put(key, envelope.get(key));
            }
        }
        return out;
    }

    /** 沒寫就當作要保存 —— 保守的預設值，不會意外丟掉呼叫端想留的紀錄。 */
    public static boolean persistPayload(Map<String, Object> envelope) {
        return !Boolean.FALSE.equals(envelope.get(PERSIST_PAYLOAD));
    }
}
