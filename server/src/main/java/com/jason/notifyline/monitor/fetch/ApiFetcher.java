package com.jason.notifyline.monitor.fetch;

import com.jason.notifyline.monitor.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 實際打目標 API。負責 {@code Docs/plan/11-API監控輪詢設計.md} §5.1 第 5–7 項：
 * 逾時、回應大小上限（邊讀邊擋）、Content-Type 必須是 JSON。
 *
 * <p><strong>網址是否可以打是 {@link OutboundUrlGuard} 的職責，這裡不重複做</strong>。
 * 呼叫端要照 §7 的順序：先 {@code guard.check(uri)}，通過才呼叫這裡的
 * {@link #fetch}。這個類別本身完全不知道 scheme / DNS / 內網範圍這些規則 ——
 * 這不是偷懶，是刻意的介面切分：{@link OutboundUrlGuard} 驗證的是「使用者填的
 * 網址」，這裡執行的是「實際的一次 HTTP 呼叫」，兩者職責不同，混在一起會讓
 * 兩邊都難測試（這個類別的測試要打真的 loopback 位址給 {@code MockWebServer}
 * 用，而 loopback 正是 guard 要擋的範圍之一）。
 *
 * <h2>{@code HttpClient.Redirect.NEVER}</h2>
 *
 * <p>絕不可跟隨 redirect。跟隨 redirect 等於讓對方一跳就繞過
 * {@link OutboundUrlGuard} 做過的所有主機檢查 —— guard 驗證的是原始網址，
 * 不是重導向後最終連上的網址。3xx 一律視為 {@link FetchResult.Failure}，
 * 要不要對新網址重新走一次完整的 guard 檢查是呼叫端的決定，這裡不自動跟。
 *
 * <h2>邊讀邊擋</h2>
 *
 * <p>回應大小上限用串流讀取、每讀一個 chunk 就累加位元組數來擋，超過上限立刻
 * 中止連線 —— 不是整包讀完再用 {@code body.length()} 判斷。後者對一個刻意回傳
 * 超大 body（或無限串流）的目標毫無防護效果，會把整包吃進記憶體，上限形同虛設。
 */
@Component
public class ApiFetcher {

    private static final Logger log = LoggerFactory.getLogger(ApiFetcher.class);

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String SET_COOKIE_HEADER = "set-cookie";
    private static final int READ_CHUNK_SIZE = 8192;

    private final MonitorProperties properties;
    private final HttpClient httpClient;

    public ApiFetcher(MonitorProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchResult fetch(FetchRequest request) {
        HttpRequest httpRequest = buildRequest(request);
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            return handleResponse(response);

        } catch (HttpTimeoutException e) {
            return failure(FetchResult.Reason.TIMEOUT, "request timed out", null);

        } catch (IOException e) {
            // 連線被拒、連線中斷、TLS 握手失敗等等。細節放伺服器端 log，
            // 回傳值只留類別名稱 —— 目標 API 自己的 I/O 錯誤訊息不該原樣外流。
            log.debug("ApiFetcher I/O 錯誤：uri={}", request.uri(), e);
            return failure(FetchResult.Reason.NETWORK_ERROR,
                    e.getClass().getSimpleName(), null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(FetchResult.Reason.NETWORK_ERROR, "interrupted", null);
        }
    }

    private HttpRequest buildRequest(FetchRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                // JDK HttpClient 沒有獨立的「連線逾時」與「讀取逾時」兩個旋鈕 ——
                // connectTimeout 設在 HttpClient 上（見建構子），這裡的 timeout()
                // 涵蓋整個請求—回應週期，拿來當「讀取逾時」的近似值。
                .timeout(properties.readTimeout());

        request.headers().forEach(builder::header);

        if ("POST".equals(request.method())) {
            String body = request.body() == null ? "" : request.body();
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private FetchResult handleResponse(HttpResponse<InputStream> response) {
        int status = response.statusCode();
        // 不論最終走哪個分支都先抓下來——即使是 3xx/4xx/5xx，目標站台仍可能夾帶
        // Set-Cookie（例如 401 順便清空失效的 session），見 FetchResult 類別註解。
        List<String> setCookieHeaders = response.headers().allValues(SET_COOKIE_HEADER);

        if (status >= 300 && status < 400) {
            closeQuietly(response.body());
            return failure(FetchResult.Reason.REDIRECT_NOT_ALLOWED,
                    "server returned HTTP " + status, status, setCookieHeaders);
        }

        String contentType = response.headers().firstValue(CONTENT_TYPE_HEADER).orElse(null);
        if (!isJsonContentType(contentType)) {
            closeQuietly(response.body());
            return failure(FetchResult.Reason.NON_JSON_CONTENT_TYPE,
                    "content type: " + abbreviate(contentType), status, setCookieHeaders);
        }

        String body;
        try {
            body = readBounded(response.body(), properties.maxBodyBytes());
        } catch (BodyTooLargeException e) {
            return failure(FetchResult.Reason.BODY_TOO_LARGE,
                    "response exceeded " + properties.maxBodyBytes() + " byte cap", status, setCookieHeaders);
        } catch (IOException e) {
            log.debug("ApiFetcher 讀取回應失敗", e);
            return failure(FetchResult.Reason.NETWORK_ERROR, e.getClass().getSimpleName(), status, setCookieHeaders);
        }

        if (status >= 400) {
            return failure(FetchResult.Reason.HTTP_ERROR, "HTTP " + status, status, setCookieHeaders);
        }
        return new FetchResult.Success(status, contentType, body, setCookieHeaders);
    }

    /**
     * @param contentType 已忽略 {@code charset} 等參數的完整 header 值
     * @return {@code application/json} 或以 {@code +json} 結尾（例如
     *         {@code application/vnd.api+json}）才算 JSON
     */
    private static boolean isJsonContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    /**
     * 邊讀邊擋：每讀一個 chunk 就累加已讀位元組數，一旦超過 {@code maxBytes}
     * 立刻丟例外中止 —— 不會把超過上限之後的內容繼續讀進記憶體。
     */
    private static String readBounded(InputStream in, int maxBytes) throws IOException {
        try (in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, READ_CHUNK_SIZE));
            byte[] chunk = new byte[READ_CHUNK_SIZE];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BodyTooLargeException();
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // 這裡只是要放棄這個回應（redirect / 非 JSON），關閉本身失敗與否無關緊要。
        }
    }

    private static FetchResult.Failure failure(FetchResult.Reason reason, String detail, Integer httpStatus) {
        return new FetchResult.Failure(reason, detail, httpStatus);
    }

    private static FetchResult.Failure failure(FetchResult.Reason reason, String detail, Integer httpStatus,
                                                List<String> setCookieHeaders) {
        return new FetchResult.Failure(reason, detail, httpStatus, setCookieHeaders);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "(none)";
        }
        String trimmed = value.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 197) + "...";
    }

    /** 內部訊號用，不攜帶目標回應的任何內容。 */
    private static final class BodyTooLargeException extends IOException {
    }

    /**
     * 一次抓取請求。{@code method} 只接受 {@code GET} / {@code POST}，與
     * {@code ApiMonitor} 的資料庫約束（{@code api_monitor_method_chk}）一致。
     *
     * @param headers 明文 header（解密是 {@code ApiMonitorStore}／呼叫端的職責，
     *                這裡只負責把 header 放進去打）
     */
    public record FetchRequest(URI uri, String method, String body, Map<String, String> headers) {

        public FetchRequest {
            if (uri == null) {
                throw new IllegalArgumentException("uri must not be null");
            }
            String normalizedMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            if (!"GET".equals(normalizedMethod) && !"POST".equals(normalizedMethod)) {
                throw new IllegalArgumentException("method must be GET or POST: " + method);
            }
            method = normalizedMethod;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }
}
