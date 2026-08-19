package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.LineLimits;
import com.jason.notifyline.notification.api.NotificationRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把請求轉成要送給 LINE 的 message object 清單。
 *
 * <p>兩種模式：簡易模式（{@code message}）由這裡組出文字訊息；
 * 進階模式（{@code lineMessages}）直接沿用呼叫端給的物件，但仍要通過
 * {@link UriHostValidator} 的連結白名單。
 */
@Component
public class MessageAssembler {

    private final UriHostValidator uriHostValidator;

    public MessageAssembler(UriHostValidator uriHostValidator) {
        this.uriHostValidator = uriHostValidator;
    }

    /**
     * @return 可直接序列化成 LINE {@code messages} 陣列的清單
     * @throws ApiException 內容不合法（400）
     */
    public List<Map<String, Object>> assemble(NotificationRequest request) {
        if (!request.hasExactlyOneMessageSource()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Provide exactly one of message or lineMessages.");
        }

        List<Map<String, Object>> messages = request.usesRawMessages()
                ? sanitiseRaw(request.lineMessages())
                : List.of(simpleText(request.message()));

        // 兩種模式都要過連結白名單。簡易模式的文字裡也可能有 https:// ——
        // LINE 客戶端會自動把它變成可點的連結，釣魚風險與 uri action 相同。
        uriHostValidator.validate(messages);
        return messages;
    }

    /**
     * 簡易模式：title 渲染成首行。
     *
     * <p>title + text 合起來仍受 5000 字上限管制。分開驗證會讓「兩個欄位都合法但
     * 合起來超過」的請求送到 LINE 才被拒 —— 那時已經寫進 outbox，變成一個永遠
     * 重試不會成功的批次。
     */
    private static Map<String, Object> simpleText(NotificationRequest.Message message) {
        String text = message.title() == null || message.title().isBlank()
                ? message.text()
                : message.title() + "\n" + message.text();

        if (text.length() > LineLimits.MAX_TEXT_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "title and text combined exceed " + LineLimits.MAX_TEXT_LENGTH + " characters.");
        }

        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "text");
        object.put("text", text);
        return Map.copyOf(object);
    }

    /**
     * 進階模式的最低限度檢查。
     *
     * <p>刻意<strong>不</strong>驗證整個 message object 的 schema —— 那等於在本地
     * 重寫一份 LINE 的規格，而 LINE 隨時會新增訊息型別。這裡只擋「一定會失敗」與
     * 「會造成安全問題」的兩類，其餘讓 LINE 自己回錯。
     */
    private static List<Map<String, Object>> sanitiseRaw(List<Map<String, Object>> raw) {
        if (raw.size() > LineLimits.MAX_MESSAGE_OBJECTS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "At most " + LineLimits.MAX_MESSAGE_OBJECTS + " message objects per request.");
        }
        for (Map<String, Object> object : raw) {
            if (object == null || object.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "lineMessages must not contain empty objects.");
            }
            Object type = object.get("type");
            if (!(type instanceof String s) || s.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Each object in lineMessages requires a non-empty string type.");
            }
        }
        return List.copyOf(raw);
    }
}
