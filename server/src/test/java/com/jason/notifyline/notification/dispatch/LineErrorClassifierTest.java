package com.jason.notifyline.notification.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分類矩陣。
 *
 * <p>這裡的每一格都對應一個實際後果：判成 RETRY 就是「再打 4 次」，判成 FATAL
 * 就是「這則訊息永久遺失」。不對稱的代價決定了預設要偏向哪一邊。
 */
@DisplayName("LineErrorClassifier")
class LineErrorClassifierTest {

    private final LineErrorClassifier classifier = new LineErrorClassifier();

    private SendOutcome.Kind kindOf(int status, String body) {
        return classifier.classify(HttpStatusCode.valueOf(status), body).kind();
    }

    @Test
    @DisplayName("429 + monthly limit → FATAL：重試到下個月都不會成功")
    void monthlyQuotaIsFatal() {
        SendOutcome outcome = classifier.classify(HttpStatus.TOO_MANY_REQUESTS,
                "{\"message\":\"You have reached your monthly limit.\"}");

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.FATAL);
        assertThat(outcome.errorCode()).isEqualTo("LINE_MONTHLY_QUOTA");
    }

    @Test
    @DisplayName("月額度的判定不分大小寫")
    void monthlyQuotaCaseInsensitive() {
        assertThat(kindOf(429, "{\"message\":\"You have reached your MONTHLY LIMIT.\"}"))
                .isEqualTo(SendOutcome.Kind.FATAL);
    }

    @Test
    @DisplayName("429 但不是月額度 → RETRY：只是打太快，等一下就好")
    void plainRateLimitIsRetryable() {
        SendOutcome outcome = classifier.classify(HttpStatus.TOO_MANY_REQUESTS,
                "{\"message\":\"Too many requests\"}");

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.RETRY);
        assertThat(outcome.errorCode()).isEqualTo("LINE_RATE_LIMITED");
    }

    @Test
    @DisplayName("429 沒有回應內容 → RETRY，不要猜成月額度")
    void rateLimitWithoutBodyIsRetryable() {
        assertThat(kindOf(429, null)).isEqualTo(SendOutcome.Kind.RETRY);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    @DisplayName("憑證問題 → FATAL：重試只是拿同一把壞掉的 token 再打一次")
    void credentialErrorsAreFatal(int status) {
        assertThat(kindOf(status, "{}")).isEqualTo(SendOutcome.Kind.FATAL);
        assertThat(classifier.classify(HttpStatusCode.valueOf(status), "{}").errorCode())
                .isEqualTo("LINE_UNAUTHORIZED");
    }

    @Test
    @DisplayName("400 → FATAL：請求內容不會自己變好")
    void badRequestIsFatal() {
        assertThat(kindOf(400, "{\"message\":\"invalid property\"}"))
                .isEqualTo(SendOutcome.Kind.FATAL);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("5xx → RETRY")
    void serverErrorsAreRetryable(int status) {
        SendOutcome outcome = classifier.classify(HttpStatusCode.valueOf(status), "oops");

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.RETRY);
        // 這個碼會讓 LineMulticastClient 把失敗餵給斷路器
        assertThat(outcome.errorCode()).isEqualTo("LINE_SERVER_ERROR");
    }

    @Test
    @DisplayName("沒見過的狀態碼 → RETRY，偏向安全的那一邊")
    void unknownStatusDefaultsToRetry() {
        assertThat(kindOf(418, "teapot")).isEqualTo(SendOutcome.Kind.RETRY);
    }

    @Test
    @DisplayName("網路層例外 → RETRY。逾時可能其實已送達，靠 retry key 防重複")
    void ioErrorsAreRetryable() {
        SendOutcome outcome = classifier.classify(new SocketTimeoutException("read timed out"));

        assertThat(outcome.kind()).isEqualTo(SendOutcome.Kind.RETRY);
        assertThat(outcome.errorCode()).isEqualTo("LINE_IO_ERROR");
        assertThat(outcome.errorMessage()).contains("SocketTimeoutException");
    }

    @Test
    @DisplayName("錯誤訊息壓成單行並截斷 —— 它會被寫進資料庫欄位")
    void messageIsFlattenedAndTruncated() {
        String noisy = "line one\n  line two\r\n" + "x".repeat(2000);

        String message = classifier.classify(HttpStatus.BAD_REQUEST, noisy).errorMessage();

        assertThat(message).doesNotContain("\n").doesNotContain("\r");
        assertThat(message).hasSizeLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("null 的回應內容不會炸")
    void nullBodyIsSafe() {
        assertThat(classifier.classify(HttpStatus.BAD_REQUEST, null).errorMessage()).isNull();
        assertThat(classifier.classify(new IOException()).errorMessage()).isNotNull();
    }
}
