package com.jason.notifyline.notification.api;

import com.jason.notifyline.notification.domain.TargetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/v1/notifications} 的請求。契約見 {@code Docs/plan/05-API契約.md} §2.1。
 *
 * @param message      簡易模式（純文字）。與 {@code lineMessages} 二擇一
 * @param lineMessages 進階模式：原始 LINE message object。<strong>需要 notify:raw scope</strong>
 */
public record NotificationRequest(
        @NotNull(message = "target is required")
        @Valid Target target,

        @Valid Message message,

        @Size(max = 5, message = "at most 5 message objects")
        List<Map<String, Object>> lineMessages,

        @Valid Options options) {

    /** LINE 文字訊息的字元上限。 */
    public static final int MAX_TEXT_LENGTH = 5000;
    public static final int MAX_TITLE_LENGTH = 100;
    /** multicast 單次收件人上限，見 06 §1.1。 */
    public static final int MAX_USER_IDS = 500;

    public NotificationRequest {
        options = options == null ? Options.defaults() : options;
    }

    /**
     * 恰好要有一種訊息來源。
     *
     * <p>兩個都給會讓「實際送出的是哪一個」變成需要記憶的規則 —— 寧可明確拒絕。
     */
    public boolean hasExactlyOneMessageSource() {
        boolean simple = message != null;
        boolean raw = lineMessages != null && !lineMessages.isEmpty();
        return simple ^ raw;
    }

    public boolean usesRawMessages() {
        return lineMessages != null && !lineMessages.isEmpty();
    }

    /**
     * @param userIds 只有 {@code type=USER} 需要
     */
    public record Target(
            @NotNull(message = "target.type is required") TargetType type,
            @Size(max = MAX_USER_IDS, message = "at most " + MAX_USER_IDS + " userIds")
            List<@Pattern(regexp = "^U[0-9a-f]{32}$",
                    message = "must be a LINE user id") String> userIds) {
    }

    /**
     * @param title 選填，渲染成訊息首行
     */
    public record Message(
            @Size(max = MAX_TITLE_LENGTH, message = "title must be at most "
                    + MAX_TITLE_LENGTH + " characters") String title,

            @NotNull(message = "message.text is required")
            @Size(min = 1, max = MAX_TEXT_LENGTH,
                    message = "text must be 1.." + MAX_TEXT_LENGTH + " characters") String text) {
    }

    /**
     * @param notificationDisabled true 時使用者手機不跳推播（訊息仍會送達）
     * @param persistPayload       false 時不保存通知內容，只留 metadata 與內容 hash。
     *                             預設保存 90 天，所以<strong>不要傳送機密資料</strong>
     */
    public record Options(Boolean notificationDisabled, Boolean persistPayload) {

        public static Options defaults() {
            return new Options(false, true);
        }

        public boolean notificationDisabledOrDefault() {
            return Boolean.TRUE.equals(notificationDisabled);
        }

        public boolean persistPayloadOrDefault() {
            return persistPayload == null || persistPayload;
        }
    }
}
