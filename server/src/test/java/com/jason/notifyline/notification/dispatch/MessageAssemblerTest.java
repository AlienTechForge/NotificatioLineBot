package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.LineLimits;
import com.jason.notifyline.config.AppProperties;
import com.jason.notifyline.notification.api.NotificationRequest;
import com.jason.notifyline.common.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MessageAssembler")
class MessageAssemblerTest {

    private final MessageAssembler assembler = new MessageAssembler(
            new UriHostValidator(new AppProperties("https://x", "", "example.com")));

    private static NotificationRequest simple(String title, String text) {
        return new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null),
                new NotificationRequest.Message(title, text),
                null, null);
    }

    private static NotificationRequest raw(List<Map<String, Object>> messages) {
        return new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null),
                null, messages, null);
    }

    // ------------------------------------------------------------ 簡易模式

    @Test
    @DisplayName("純 text 組成單一 text message object")
    void textOnly() {
        assertThat(assembler.assemble(simple(null, "備份完成")))
                .containsExactly(Map.of("type", "text", "text", "備份完成"));
    }

    @Test
    @DisplayName("title 渲染成首行")
    void titleBecomesFirstLine() {
        assertThat(assembler.assemble(simple("備份", "耗時 42 秒")))
                .containsExactly(Map.of("type", "text", "text", "備份\n耗時 42 秒"));
    }

    @Test
    @DisplayName("空白的 title 不產生空行")
    void blankTitleIgnored() {
        assertThat(assembler.assemble(simple("   ", "內容")))
                .containsExactly(Map.of("type", "text", "text", "內容"));
    }

    @Test
    @DisplayName("title + text 合計超過 5000 字要在本地擋下，不要送到 LINE 才失敗")
    void combinedLengthChecked() {
        String title = "t".repeat(100);
        String text = "x".repeat(LineLimits.MAX_TEXT_LENGTH - 50);

        assertThatThrownBy(() -> assembler.assemble(simple(title, text)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(String.valueOf(LineLimits.MAX_TEXT_LENGTH));
    }

    @Test
    @DisplayName("簡易模式的文字裡的連結一樣要過白名單 —— LINE 會自動把它變成可點連結")
    void linkInPlainTextIsValidated() {
        assertThatThrownBy(() -> assembler.assemble(simple(null, "詳見 https://evil.example.net/x")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.URI_HOST_NOT_ALLOWED);

        assertThat(assembler.assemble(simple(null, "詳見 https://example.com/x"))).hasSize(1);
    }

    // ------------------------------------------------------------ 進階模式

    @Test
    @DisplayName("原始 message object 原樣通過")
    void rawPassesThrough() {
        List<Map<String, Object>> messages = List.of(
                Map.of("type", "sticker", "packageId", "446", "stickerId", "1988"));

        assertThat(assembler.assemble(raw(messages))).isEqualTo(messages);
    }

    @Test
    @DisplayName("超過 5 個 message object 拒絕")
    void tooManyObjects() {
        List<Map<String, Object>> six = java.util.Collections.nCopies(
                6, Map.<String, Object>of("type", "text", "text", "x"));

        assertThatThrownBy(() -> assembler.assemble(raw(six)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("5 message objects");
    }

    @Test
    @DisplayName("缺少 type 的物件拒絕 —— 一定會被 LINE 退，不值得寫進 outbox 再重試 5 次")
    void missingTypeRejected() {
        assertThatThrownBy(() -> assembler.assemble(raw(List.of(Map.of("text", "x")))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("原始模式的連結也要過白名單")
    void rawLinkValidated() {
        List<Map<String, Object>> phish = List.of(Map.of(
                "type", "template",
                "template", Map.of("actions", List.of(
                        Map.of("type", "uri", "uri", "https://evil.example.net")))));

        assertThatThrownBy(() -> assembler.assemble(raw(phish)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.URI_HOST_NOT_ALLOWED);
    }

    // ------------------------------------------------------------ 二擇一

    @Test
    @DisplayName("兩種模式都給 → 拒絕，不要猜哪個優先")
    void bothSourcesRejected() {
        NotificationRequest both = new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null),
                new NotificationRequest.Message(null, "hi"),
                List.of(Map.of("type", "text", "text", "hi")),
                null);

        assertThatThrownBy(() -> assembler.assemble(both))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("兩種模式都沒給 → 拒絕")
    void noSourceRejected() {
        NotificationRequest neither = new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null), null, null, null);

        assertThatThrownBy(() -> assembler.assemble(neither))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("lineMessages 是空陣列視同沒給")
    void emptyRawListCountsAsAbsent() {
        NotificationRequest empty = new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null), null, List.of(), null);

        assertThatThrownBy(() -> assembler.assemble(empty))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exactly one");
    }
}
