package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.config.LineApiProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 呼叫 LINE 的 multicast 端點。
 *
 * <h2>為什麼直接打 REST 而不用 SDK 的 typed model</h2>
 *
 * <p>{@code notify:raw} 的整個意義就是「呼叫端給什麼就送什麼」。把訊息塞進 SDK 的
 * {@code Message} 密封型別再序列化回去，等於用我們釘住的 SDK 版本去過濾 LINE 的
 * 訊息規格 —— LINE 之後新增的欄位會被安靜地丟掉，呼叫端只會看到訊息「少了一塊」，
 * 而且錯誤發生在我們這裡卻長得像 LINE 的問題。
 *
 * <p>另外兩個實務理由：{@code X-Line-Retry-Key} 要由我們指定；回應的
 * {@code x-line-request-id} 要留下來當對帳憑據，事後補不到。
 */
@Component
public class LineMulticastClient {

    private static final Logger log = LoggerFactory.getLogger(LineMulticastClient.class);

    private static final String MULTICAST_PATH = "/v2/bot/message/multicast";
    private static final String RETRY_KEY_HEADER = "X-Line-Retry-Key";
    private static final String REQUEST_ID_HEADER = "x-line-request-id";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LineErrorClassifier classifier;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;

    /**
     * 逾時一定要設。少了讀取逾時，一條卡住的連線會永久佔住一條派送執行緒 ——
     * 池只有幾條，幾次之後派送就完全停擺，而且不會有任何錯誤訊息。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    public LineMulticastClient(LineApiProperties properties,
                               ObjectMapper objectMapper,
                               LineErrorClassifier classifier,
                               RateLimiter lineRateLimiter,
                               CircuitBreaker lineCircuitBreaker,
                               LineChannelToken channelToken) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + channelToken.value())
                .build();
        this.objectMapper = objectMapper;
        this.classifier = classifier;
        this.rateLimiter = lineRateLimiter;
        this.circuitBreaker = lineCircuitBreaker;
    }

    /**
     * @param recipients 1..500 個 LINE user ID
     * @param payload    儲存於 {@code notification.payload} 的請求範本，
     *                   形如 {@code {"messages":[…],"notificationDisabled":false}}
     * @param retryKey   同一批的所有重試都要用同一把
     * @return 永遠有值 —— 例外都被轉成 {@link SendOutcome}，呼叫端不需要 try/catch
     */
    public SendOutcome multicast(List<String> recipients, String payload, UUID retryKey) {
        Map<String, Object> body = buildBody(recipients, payload);

        try {
            // 斷路器包在限速器外面：斷路開路時要「立刻」回覆，不該先去排隊等令牌
            return circuitBreaker.executeCallable(() ->
                    RateLimiter.decorateCallable(rateLimiter, () -> send(body, retryKey)).call());

        } catch (CallNotPermittedException e) {
            log.warn("LINE 斷路器開路，批次延後：retryKey={}", retryKey);
            return SendOutcome.deferred("CIRCUIT_OPEN", "LINE circuit breaker is open.");

        } catch (RequestNotPermitted e) {
            // 本地限速器擋下。同樣沒送出去，不算一次嘗試。
            return SendOutcome.deferred("RATE_LIMITER", "Local LINE rate limiter rejected the call.");

        } catch (LineServerException e) {
            // 已經分類好了，只是借例外讓斷路器看見這次失敗
            return e.outcome();

        } catch (Exception e) {
            return classifier.classify(e);
        }
    }

    private SendOutcome send(Map<String, Object> body, UUID retryKey) {
        // 自己序列化，不靠 message converter —— classpath 上同時有 Jackson 2（LINE SDK 需要）
        // 與 Jackson 3（Spring 的），讓框架去挑會挑到哪一個並不明確。
        String json = objectMapper.writeValueAsString(body);

        var response = restClient.post()
                .uri(MULTICAST_PATH)
                .header(RETRY_KEY_HEADER, retryKey.toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(json)
                // 自己判斷狀態碼。預設的 error handler 會把 4xx/5xx 變成例外，
                // 那樣就分不出「該重試」與「重試也沒用」。
                .exchange((request, clientResponse) -> {
                    var status = clientResponse.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return SendOutcome.sent(
                                clientResponse.getHeaders().getFirst(REQUEST_ID_HEADER));
                    }
                    return classifier.classify(status, readBody(clientResponse));
                });

        if (response.kind() != SendOutcome.Kind.SENT) {
            // 讓斷路器看見失敗。5xx 與逾時才該計入失敗率 ——
            // 400（呼叫端把請求寫壞了）不代表 LINE 有問題，不該推動斷路。
            if ("LINE_SERVER_ERROR".equals(response.errorCode())) {
                throw new LineServerException(response);
            }
        }
        return response;
    }

    private static String readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code payload} 已經是完整的請求範本，這裡只補上 {@code to}。
     *
     * <p>收件人不存進 payload 是刻意的：payload 過了保留期會被清空，而
     * {@code notification_delivery.line_user_ids} 是重試時唯一的收件人來源。
     */
    private Map<String, Object> buildBody(List<String> recipients, String payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = objectMapper.readValue(payload, Map.class);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", recipients);
        // 只轉發白名單內的頂層欄位。整包 putAll 會把我們自己的處理旗標
        // （persistPayload）一起送到 LINE 的 API 去。
        body.putAll(PayloadEnvelope.forwardable(envelope));
        // to 一定要是我們算出來的那份，不能被 payload 裡的同名鍵覆蓋
        body.put("to", recipients);
        return body;
    }

    /** 只用來讓斷路器統計失敗，不會外洩到 {@link #multicast}。 */
    static class LineServerException extends RuntimeException {

        private final transient SendOutcome outcome;

        LineServerException(SendOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        SendOutcome outcome() {
            return outcome;
        }
    }

    /** channel token 的具名包裝，避免與其他 String bean 混淆。 */
    public record LineChannelToken(String value) {
    }
}
