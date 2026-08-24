package com.jason.notifyline.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler#handleUnreadable} 的機密洩漏修補（Wave 5 review 帶過來
 * 的修正項）。見任務規格「carried-over security fix」。
 *
 * <p>Jackson 在 {@code HttpMessageNotReadableException} 的訊息尾端附上
 * {@code [Source: (String)"..."]}——那是原始 request body 的回顯。
 * {@code POST /admin/api/monitors/import} 這類端點的 body 可能帶 cookie／API token，
 * 畸形的請求信封（例如漏了引號）就會讓那整段連同機密一起被 Jackson 塞進例外訊息，
 * 原樣記錄等於把使用者貼上的機密寫進伺服器日誌。
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachLogAppender() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("payload 內夾帶的機密不會出現在記錄的訊息裡，但保留 Jackson 給的診斷原因")
    void malformedBody_secretNotLoggedButReasonKept() {
        String secret = "cookie: session=SUPER_SECRET_TOKEN_abc123; authorization: Bearer sk-live-XYZ";
        // 模擬 Jackson 真實產生的訊息形狀：診斷原因 + [Source: ...] 回顯原始 payload。
        String jacksonMessage = "Unexpected character ('a' (code 97)): was expecting double-quote to start "
                + "field name [Source: (String)\"{\"raw\":\"" + secret + "\"}\"; line: 1, column: 2]";
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(jacksonMessage, (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadable(exception);

        // 對外回應維持原樣：泛用訊息，不含任何解析細節或機密。
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().message()).isEqualTo("Request body is not valid JSON.");

        assertThat(logAppender.list).hasSize(1);
        String logged = logAppender.list.get(0).getFormattedMessage();
        assertThat(logged)
                .doesNotContain(secret)
                .doesNotContain("SUPER_SECRET_TOKEN")
                .doesNotContain("sk-live-XYZ")
                .doesNotContain("[Source:");
        // 有用的診斷仍然留著——不是整段訊息被消音。
        assertThat(logged).contains("Unexpected character");
    }

    @Test
    @DisplayName("記錄等級是 WARN")
    void malformedBody_logsAtWarnLevel() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Unexpected end-of-input [Source: (String)\"{\"; line: 1, column: 1]",
                (org.springframework.http.HttpInputMessage) null);

        handler.handleUnreadable(exception);

        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("沒有 [Source: 標記的訊息（非 Jackson 產生）原樣記錄")
    void messageWithoutSourceMarker_isLoggedAsIs() {
        HttpMessageNotReadableException exception =
                new HttpMessageNotReadableException("Required request body is missing", (org.springframework.http.HttpInputMessage) null);

        handler.handleUnreadable(exception);

        assertThat(logAppender.list.get(0).getFormattedMessage()).contains("Required request body is missing");
    }

    @Test
    @DisplayName("message 為 null 時不拋例外，記錄一個佔位文字")
    void nullMessage_doesNotThrow() {
        HttpMessageNotReadableException exception =
                new HttpMessageNotReadableException(null, (org.springframework.http.HttpInputMessage) null);

        handler.handleUnreadable(exception);

        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage()).contains("no message");
    }
}
