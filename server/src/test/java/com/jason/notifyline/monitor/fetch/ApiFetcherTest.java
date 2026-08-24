package com.jason.notifyline.monitor.fetch;

import com.jason.notifyline.monitor.MonitorProperties;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiFetcher} 的 §5.1 第 5–7 項行為：逾時、回應大小上限（邊讀邊擋）、
 * Content-Type 必須是 JSON。網址是否可以打（scheme / DNS / 內網範圍）不在這裡測，
 * 那是 {@code OutboundUrlGuardTest} 的職責 —— 這也是為什麼這裡可以直接把
 * {@code MockWebServer} 開在 loopback 位址上打樁：{@link ApiFetcher} 本身完全不做
 * 網址檢查，只管一次 HTTP 呼叫的機制。
 */
@DisplayName("ApiFetcher")
class ApiFetcherTest {

    private static final MockWebServer SERVER = new MockWebServer();

    static {
        try {
            SERVER.start();
        } catch (IOException e) {
            throw new IllegalStateException("無法啟動打樁伺服器", e);
        }
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.close();
    }

    /** MockWebServer 的請求佇列跨測試殘留，沒清會讓下一個測試的斷言碰到舊請求。 */
    @AfterEach
    void drainRequestLog() throws InterruptedException {
        while (SERVER.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 丟掉
        }
    }

    private static MonitorProperties properties(Duration connectTimeout, Duration readTimeout, int maxBodyBytes) {
        return new MonitorProperties(
                null, null, 0, null, null, connectTimeout, readTimeout, maxBodyBytes, null, 0, null, null);
    }

    private static ApiFetcher defaultFetcher() {
        return new ApiFetcher(properties(Duration.ofSeconds(5), Duration.ofSeconds(5), 1_048_576));
    }

    private static ApiFetcher.FetchRequest getRequest(String path) {
        return new ApiFetcher.FetchRequest(URI.create(SERVER.url(path).toString()), "GET", null, Map.of());
    }

    private static FetchResult.Failure asFailure(FetchResult result) {
        assertThat(result).isInstanceOf(FetchResult.Failure.class);
        return (FetchResult.Failure) result;
    }

    // ------------------------------------------------------------ happy path

    @Test
    @DisplayName("正常 JSON 回應回傳 Success，帶上狀態碼、content-type 與內容")
    void normalJsonResponse_returnsSuccess() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .body("{\"status\":\"ok\"}")
                .build());

        FetchResult result = defaultFetcher().fetch(getRequest("/data"));

        assertThat(result).isInstanceOf(FetchResult.Success.class);
        FetchResult.Success success = (FetchResult.Success) result;
        assertThat(success.httpStatus()).isEqualTo(200);
        assertThat(success.body()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    @DisplayName("+json 結尾的 content type（忽略 charset 參數）也視為 JSON")
    void plusJsonContentType_treatedAsJson() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/vnd.api+json; charset=utf-8")
                .body("{}")
                .build());

        FetchResult result = defaultFetcher().fetch(getRequest("/data"));

        assertThat(result).isInstanceOf(FetchResult.Success.class);
    }

    @Test
    @DisplayName("POST 連同自訂 header 與 body 一併送出")
    void postRequest_sendsBodyAndHeaders() throws InterruptedException {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build());

        ApiFetcher.FetchRequest request = new ApiFetcher.FetchRequest(
                URI.create(SERVER.url("/submit").toString()), "post", "{\"ping\":true}",
                Map.of("X-Custom", "abc"));

        FetchResult result = defaultFetcher().fetch(request);

        assertThat(result).isInstanceOf(FetchResult.Success.class);
        RecordedRequest recorded = SERVER.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeaders().get("X-Custom")).isEqualTo("abc");
        assertThat(recorded.getBody().utf8()).isEqualTo("{\"ping\":true}");
    }

    // ------------------------------------------------------------ 必要案例：非 JSON content-type

    @Test
    @DisplayName("非 JSON content-type 回傳 Failure(NON_JSON_CONTENT_TYPE)")
    void nonJsonContentType_returnsFailure() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<html></html>")
                .build());

        FetchResult.Failure failure = asFailure(defaultFetcher().fetch(getRequest("/data")));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.NON_JSON_CONTENT_TYPE);
    }

    @Test
    @DisplayName("完全沒有 content-type header 一樣視為非 JSON")
    void missingContentType_returnsFailure() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{}")
                .build());

        FetchResult.Failure failure = asFailure(defaultFetcher().fetch(getRequest("/data")));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.NON_JSON_CONTENT_TYPE);
    }

    // ------------------------------------------------------------ 必要案例：超大 body

    @Test
    @DisplayName("回應超過大小上限回傳 Failure(BODY_TOO_LARGE)，邊讀邊擋不會整包讀完")
    void oversizedBody_returnsFailure() {
        String hugeValue = "x".repeat(10_000);
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"data\":\"" + hugeValue + "\"}")
                .build());

        // 上限故意設得比第一個讀取 chunk（8192 bytes）還小，這樣「邊讀邊擋」
        // 只需要一次 read() 就能驗證到 —— 不必真的等它把 10000 多 byte 讀完。
        ApiFetcher fetcher = new ApiFetcher(properties(Duration.ofSeconds(5), Duration.ofSeconds(5), 100));
        FetchResult.Failure failure = asFailure(fetcher.fetch(getRequest("/data")));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.BODY_TOO_LARGE);
    }

    @Test
    @DisplayName("剛好等於上限的回應仍算成功")
    void bodyExactlyAtCap_succeeds() {
        String body = "x".repeat(50);
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build());

        ApiFetcher fetcher = new ApiFetcher(properties(Duration.ofSeconds(5), Duration.ofSeconds(5), body.length()));
        FetchResult result = fetcher.fetch(getRequest("/data"));

        assertThat(result).isInstanceOf(FetchResult.Success.class);
    }

    // ------------------------------------------------------------ 必要案例：逾時

    @Test
    @DisplayName("伺服器遲遲不回應時回傳 Failure(TIMEOUT)")
    void slowResponse_returnsTimeoutFailure() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .headersDelay(2, TimeUnit.SECONDS)
                .body("{}")
                .build());

        ApiFetcher fetcher = new ApiFetcher(properties(Duration.ofSeconds(5), Duration.ofMillis(200), 1_048_576));
        FetchResult.Failure failure = asFailure(fetcher.fetch(getRequest("/data")));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.TIMEOUT);
    }

    // ------------------------------------------------------------ 必要案例：3xx

    @Test
    @DisplayName("3xx 一律視為失敗，絕不跟隨 redirect")
    void redirectResponse_returnsFailure_notFollowed() {
        int requestsBefore = SERVER.getRequestCount();
        SERVER.enqueue(new MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://internal.example/secret")
                .build());

        FetchResult.Failure failure = asFailure(defaultFetcher().fetch(getRequest("/data")));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.REDIRECT_NOT_ALLOWED);
        assertThat(failure.httpStatus()).isEqualTo(302);
        // 只送出了一次請求 —— 沒有因為 redirect 又對 Location 發第二個請求。
        // MockWebServer 的計數是整個伺服器累積的，跨測試不會歸零，所以要看差值。
        assertThat(SERVER.getRequestCount() - requestsBefore).isEqualTo(1);
    }

    // ------------------------------------------------------------ 必要案例：5xx（與 4xx）

    @Test
    @DisplayName("5xx 回傳 Failure(HTTP_ERROR)，帶上狀態碼")
    void serverError_returnsFailure() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(500)
                .addHeader("Content-Type", "application/json")
                .body("{\"error\":\"boom\"}")
                .build());

        FetchResult.Failure failure = asFailure(defaultFetcher().fetch(getRequest("/data")));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.HTTP_ERROR);
        assertThat(failure.httpStatus()).isEqualTo(500);
        // 目標 API 的回應內容不可以流進失敗訊息 —— 那可能是它自己的機敏資料。
        assertThat(failure.detail()).doesNotContain("boom");
    }

    @Test
    @DisplayName("4xx 回傳 Failure(HTTP_ERROR)，帶上狀態碼")
    void clientError_returnsFailure() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(404)
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build());

        FetchResult.Failure failure = asFailure(defaultFetcher().fetch(getRequest("/data")));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.HTTP_ERROR);
        assertThat(failure.httpStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------ Set-Cookie（W6）

    @Test
    @DisplayName("成功回應帶 Set-Cookie：原樣（未解析）放進 Success.setCookieHeaders")
    void successResponse_capturesSetCookieHeaders() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "session=abc; Path=/")
                .addHeader("Set-Cookie", "csrf=def; Path=/")
                .body("{}")
                .build());

        FetchResult result = defaultFetcher().fetch(getRequest("/data"));

        assertThat(result).isInstanceOf(FetchResult.Success.class);
        assertThat(((FetchResult.Success) result).setCookieHeaders())
                .containsExactlyInAnyOrder("session=abc; Path=/", "csrf=def; Path=/");
    }

    @Test
    @DisplayName("沒有 Set-Cookie 的回應：setCookieHeaders 是空的，不是 null")
    void responseWithoutSetCookie_hasEmptyList() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build());

        FetchResult result = defaultFetcher().fetch(getRequest("/data"));

        assertThat(((FetchResult.Success) result).setCookieHeaders()).isEmpty();
    }

    @Test
    @DisplayName("4xx（例如 401）回應也一併帶回 Set-Cookie——過期偵測之外，站台仍可能順便清空/刷新 cookie")
    void clientErrorResponse_stillCapturesSetCookieHeaders() {
        SERVER.enqueue(new MockResponse.Builder()
                .code(401)
                .addHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "csrf=refreshed; Path=/")
                .body("{\"error\":\"unauthorized\"}")
                .build());

        FetchResult.Failure failure = asFailure(defaultFetcher().fetch(getRequest("/data")));

        assertThat(failure.httpStatus()).isEqualTo(401);
        assertThat(failure.setCookieHeaders()).containsExactly("csrf=refreshed; Path=/");
    }

    // ------------------------------------------------------------ 網路層錯誤

    @Test
    @DisplayName("連線被拒回傳 Failure(NETWORK_ERROR)")
    void connectionRefused_returnsNetworkErrorFailure() throws IOException {
        // 開一個伺服器拿它綁定的埠號、立刻關掉 —— 確保這個埠上真的沒有東西在聽。
        MockWebServer deadServer = new MockWebServer();
        deadServer.start();
        URI deadUri = URI.create(deadServer.url("/data").toString());
        deadServer.close();

        ApiFetcher fetcher = new ApiFetcher(properties(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_048_576));
        FetchResult.Failure failure = asFailure(
                fetcher.fetch(new ApiFetcher.FetchRequest(deadUri, "GET", null, Map.of())));

        assertThat(failure.reason()).isEqualTo(FetchResult.Reason.NETWORK_ERROR);
    }
}
