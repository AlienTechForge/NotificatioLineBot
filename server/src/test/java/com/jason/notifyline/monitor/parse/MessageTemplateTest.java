package com.jason.notifyline.monitor.parse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageTemplate")
class MessageTemplateTest {

    // 2026-08-22T10:30:00Z = Asia/Taipei（UTC+8）08/22 18:30。
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-22T10:30:00Z"), ZoneOffset.UTC);

    private final MessageTemplate template = new MessageTemplate(FIXED_CLOCK);

    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachLogAppender() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MessageTemplate.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logAppender);
    }

    // ---------------------------------------------------------------- 各佔位符

    @Test
    @DisplayName("{{value.NAME}}：本次取出的值")
    void valuePlaceholder() {
        var context = new MessageTemplate.RenderContext(
                "M", Map.of("status", "ok"), Map.of(), Map.of());

        assertThat(template.render("狀態：{{value.status}}", context)).isEqualTo("狀態：ok");
    }

    @Test
    @DisplayName("{{old.NAME}}：上次的值")
    void oldPlaceholder() {
        var context = new MessageTemplate.RenderContext(
                "M", Map.of(), Map.of("status", "was-ok"), Map.of());

        assertThat(template.render("舊狀態：{{old.status}}", context)).isEqualTo("舊狀態：was-ok");
    }

    @Test
    @DisplayName("{{item.NAME}}：NEW_ITEMS 模式逐項渲染時該項目的欄位")
    void itemPlaceholder() {
        var context = new MessageTemplate.RenderContext(
                "M", Map.of(), Map.of(), Map.of("id", "item-42"));

        assertThat(template.render("新項目：{{item.id}}", context)).isEqualTo("新項目：item-42");
    }

    @Test
    @DisplayName("{{monitor.name}}：監控名稱")
    void monitorNamePlaceholder() {
        var context = MessageTemplate.RenderContext.of("我的監控");

        assertThat(template.render("[{{monitor.name}}]", context)).isEqualTo("[我的監控]");
    }

    @Test
    @DisplayName("{{now}}：Asia/Taipei，MM/dd HH:mm")
    void nowPlaceholder() {
        var context = MessageTemplate.RenderContext.of("M");

        assertThat(template.render("{{now}}", context)).isEqualTo("08/22 18:30");
    }

    @Test
    @DisplayName("同一份模板可以混用多種佔位符")
    void multiplePlaceholdersInOneTemplate() {
        var context = new MessageTemplate.RenderContext(
                "監控A", Map.of("status", "fail"), Map.of("status", "ok"), Map.of());

        String result = template.render(
                "[{{monitor.name}}] {{now}} 狀態從 {{old.status}} 變成 {{value.status}}", context);

        assertThat(result).isEqualTo("[監控A] 08/22 18:30 狀態從 ok 變成 fail");
    }

    @Test
    @DisplayName("佔位符前後允許空白：{{ value.status }}")
    void placeholderAllowsInnerWhitespace() {
        var context = new MessageTemplate.RenderContext(
                "M", Map.of("status", "ok"), Map.of(), Map.of());

        assertThat(template.render("{{ value.status }}", context)).isEqualTo("ok");
    }

    // ---------------------------------------------------------------- 未知佔位符

    @Test
    @DisplayName("未知佔位符：替換成 —，不可原樣輸出 {{...}}")
    void unknownPlaceholder_isReplacedNotPassedThrough() {
        var context = MessageTemplate.RenderContext.of("M");

        String result = template.render("值：{{value.nonexistent}}", context);

        assertThat(result).isEqualTo("值：—").doesNotContain("{{").doesNotContain("}}");
    }

    @Test
    @DisplayName("未知佔位符：記一筆 WARN")
    void unknownPlaceholder_logsWarn() {
        var context = MessageTemplate.RenderContext.of("M");

        template.render("{{value.nonexistent}}", context);

        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("value.nonexistent");
                });
    }

    @Test
    @DisplayName("辨識不出的 scope（不是 value/old/item/monitor/now）也算未知佔位符")
    void unrecognizedScope_isUnknown() {
        var context = MessageTemplate.RenderContext.of("M");

        assertThat(template.render("{{bogus.field}}", context)).isEqualTo("—");
    }

    @Test
    @DisplayName("null 值：替換成 —，但不記 WARN——這是資料狀態，不是模板設定錯誤")
    void nullValue_isFallbackWithoutWarn() {
        Map<String, String> values = new java.util.HashMap<>();
        values.put("missing", null);
        var context = new MessageTemplate.RenderContext("M", values, Map.of(), Map.of());

        String result = template.render("{{value.missing}}", context);

        assertThat(result).isEqualTo("—");
        assertThat(logAppender.list).isEmpty();
    }

    // ---------------------------------------------------------------- 控制字元

    @Test
    @DisplayName("剝除控制字元但保留 \\n")
    void controlCharsStrippedButNewlinePreserved() {
        String raw = "ab\tc\nde";
        var context = new MessageTemplate.RenderContext(
                "M", Map.of("raw", raw), Map.of(), Map.of());

        String result = template.render("{{value.raw}}", context);

        assertThat(result).isEqualTo("abc\nde");
    }

    // ---------------------------------------------------------------- 截斷

    @Test
    @DisplayName("渲染後超過 4500 字會被截斷")
    void truncatesOverLongResult() {
        String big = "x".repeat(5000);
        var context = new MessageTemplate.RenderContext(
                "M", Map.of("big", big), Map.of(), Map.of());

        String result = template.render("{{value.big}}", context);

        assertThat(result).hasSize(4500);
    }

    @Test
    @DisplayName("剛好 4500 字不截斷")
    void exactlyMaxLength_isNotTruncated() {
        String exact = "x".repeat(4500);
        var context = new MessageTemplate.RenderContext(
                "M", Map.of("v", exact), Map.of(), Map.of());

        assertThat(template.render("{{value.v}}", context)).hasSize(4500);
    }

    // ---------------------------------------------------------------- 邊界

    @Test
    @DisplayName("null 模板回傳空字串")
    void nullTemplate_returnsEmptyString() {
        assertThat(template.render(null, MessageTemplate.RenderContext.of("M"))).isEmpty();
    }

    @Test
    @DisplayName("空字串模板回傳空字串")
    void emptyTemplate_returnsEmptyString() {
        assertThat(template.render("", MessageTemplate.RenderContext.of("M"))).isEmpty();
    }

    @Test
    @DisplayName("沒有佔位符的純文字模板原樣輸出")
    void plainTextWithoutPlaceholders_isUnchanged() {
        assertThat(template.render("純文字，沒有佔位符", MessageTemplate.RenderContext.of("M")))
                .isEqualTo("純文字，沒有佔位符");
    }
}
