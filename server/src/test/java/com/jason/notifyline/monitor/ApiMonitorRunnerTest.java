package com.jason.notifyline.monitor;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.compute.ComputedFieldEvaluator;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.monitor.fetch.ApiFetcher;
import com.jason.notifyline.monitor.fetch.FetchResult;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.monitor.parse.ChangeDetector;
import com.jason.notifyline.monitor.parse.ChangeResult;
import com.jason.notifyline.monitor.parse.MessageTemplate;
import com.jason.notifyline.monitor.request.RequestTemplate;
import com.jason.notifyline.monitor.login.SiteLoginService;
import com.jason.notifyline.monitor.session.SiteSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiMonitorRunner} 的編排邏輯：純 Mockito 單元測試，不碰資料庫或真的 HTTP。
 *
 * <p>{@code store} 全程是 mock——這裡只驗證 Runner 傳給
 * {@code recordSuccess}/{@code recordFailure} 的內容對不對（分類、細節、渲染出來的
 * 訊息），防洗版的實際判斷（冷卻、每日上限）是 {@code ApiMonitorStore} 的職責，
 * 見 {@code ApiMonitorStoreIT}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiMonitorRunner")
class ApiMonitorRunnerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final Executor SAME_THREAD_EXECUTOR = Runnable::run;

    @Mock
    private OutboundUrlGuard guard;
    @Mock
    private ApiFetcher fetcher;
    @Mock
    private SiteSessionService siteSessionService;
    @Mock
    private SiteLoginService siteLoginService;
    @Mock
    private ChangeDetector changeDetector;
    @Mock
    private MessageTemplate messageTemplate;
    @Mock
    private ApiMonitorStore store;

    private ApiMonitorRunner runner;
    private MonitorProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MonitorProperties(
                true, Duration.ofSeconds(10), 5, Duration.ofMinutes(2), Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofSeconds(10), 1_048_576, "", 3,
                Period.ofDays(14), Period.ofDays(90));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // RequestTemplate 用真的實例（不是 mock）——這裡的固定測試網址／header 完全
        // 沒有 {{ }} 佔位符，render() 是無害的原樣傳回，用真的實例比每個測試都要
        // stub render()/renderHeaders() 簡單，樣板替換本身的行為另有 RequestTemplateTest 覆蓋。
        RequestTemplate requestTemplate = new RequestTemplate(clock);
        // 真的實例，理由同 requestTemplate：這裡的固定測試監控全部沒有 computedFields
        // （ClaimedMonitor 的簡便建構子預設空清單），evaluate() 對空清單原樣回傳空 map，
        // 用真的實例比每個測試都要 stub 簡單，求值本身的行為另有專門的
        // ComputedFieldEvaluatorTest 覆蓋。
        ComputedFieldEvaluator computedFieldEvaluator = new ComputedFieldEvaluator();
        // 預設原樣傳回 header：這個類別要驗證的是編排邏輯，不是 cookie jar 本身的行為
        // （那是 SiteSessionServiceTest 的職責）。lenient()——guard 擋下等案例根本不會
        // 走到 attachCookies，嚴格模式下會被判定成「多餘的 stub」。
        lenient().when(siteSessionService.attachCookies(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        runner = new ApiMonitorRunner(guard, fetcher, siteSessionService, siteLoginService, changeDetector, messageTemplate,
                requestTemplate, computedFieldEvaluator, store, properties, SAME_THREAD_EXECUTOR, clock);
    }

    private static ClaimedMonitor monitor(CompareMode mode) {
        return new ClaimedMonitor(
                1L, "my monitor", "https://target.example/api", "GET", null, Map.of(),
                mode, List.of(new ExtractRule("status", "/status")), null, null,
                "{{value.status}}", null, Map.of(), true, Set.of());
    }

    private static ClaimedMonitor newItemsMonitor() {
        return new ClaimedMonitor(
                2L, "feed monitor", "https://target.example/feed", "GET", null, Map.of(),
                CompareMode.NEW_ITEMS, List.of(new ExtractRule("title", "/title")), "/items", "/id",
                "{{item.title}}", null, Map.of(), true, Set.of());
    }

    private void claims(ClaimedMonitor... monitors) {
        when(store.claim(5)).thenReturn(List.of(monitors));
    }

    // ------------------------------------------------------------ guard 擋下

    @Test
    @DisplayName("guard 擋下：分類 BLOCKED_URL，httpStatus 為 null，完全不呼叫 fetcher")
    void guardBlocked_recordsFailureWithoutFetching() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        doThrow(new OutboundUrlGuard.BlockedException("Target host resolves to a disallowed network address."))
                .when(guard).check(URI.create(claimed.url()));

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Failure> captor = ArgumentCaptor.forClass(RunAttempt.Failure.class);
        verify(store).recordFailure(eq(claimed), captor.capture());
        assertThat(captor.getValue().classification()).isEqualTo("BLOCKED_URL");
        assertThat(captor.getValue().httpStatus()).isNull();
        verify(fetcher, never()).fetch(any());
    }

    // ------------------------------------------------------------ 樣板替換 + guard 重新檢查（W5）

    @Test
    @DisplayName("URL 含佔位符：guard 檢查的是替換後的網址，fetch 也打替換後的網址（縱深防禦）")
    void urlWithPlaceholder_guardAndFetchSeeSubstitutedUrl() {
        ClaimedMonitor claimed = new ClaimedMonitor(
                3L, "templated monitor", "https://target.example/api?ts={{now.epochSeconds}}", "GET", null,
                Map.of("x-request-id", "{{uuid}}"),
                CompareMode.WHOLE_BODY, List.of(new ExtractRule("status", "/status")), null, null,
                "{{value.status}}", null, Map.of(), true, Set.of());
        claims(claimed);
        URI expectedUri = URI.create("https://target.example/api?ts=" + NOW.getEpochSecond());
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{}"));
        when(changeDetector.detectByFingerprint(any(), any(), any(), any(), any()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));

        runner.runOnce();

        verify(guard).check(expectedUri);
        ArgumentCaptor<ApiFetcher.FetchRequest> captor = ArgumentCaptor.forClass(ApiFetcher.FetchRequest.class);
        verify(fetcher).fetch(captor.capture());
        assertThat(captor.getValue().uri()).isEqualTo(expectedUri);
        assertThat(captor.getValue().headers()).containsKey("x-request-id");
        assertThat(captor.getValue().headers().get("x-request-id")).isNotEqualTo("{{uuid}}");
    }

    @Test
    @DisplayName("未知佔位符：分類 TEMPLATE_ERROR，完全不呼叫 guard 或 fetcher（跟 PARSE_ERROR 一樣走完整失敗記錄路徑）")
    void unknownPlaceholder_recordsTemplateErrorFailure() {
        ClaimedMonitor claimed = new ClaimedMonitor(
                4L, "bad template monitor", "https://target.example/api?x={{totally.unknown}}", "GET", null,
                Map.of(), CompareMode.WHOLE_BODY, List.of(), null, null,
                "{{value.status}}", null, Map.of(), true, Set.of());
        claims(claimed);

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Failure> captor = ArgumentCaptor.forClass(RunAttempt.Failure.class);
        verify(store).recordFailure(eq(claimed), captor.capture());
        assertThat(captor.getValue().classification()).isEqualTo("TEMPLATE_ERROR");
        verify(guard, never()).check(any());
        verify(fetcher, never()).fetch(any());
    }

    // ------------------------------------------------------------ fetch 失敗

    @Test
    @DisplayName("fetch 失敗：分類與細節原樣傳給 recordFailure")
    void fetchFailure_passesThroughClassificationAndDetail() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(
                new FetchResult.Failure(FetchResult.Reason.TIMEOUT, "request timed out", null));

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Failure> captor = ArgumentCaptor.forClass(RunAttempt.Failure.class);
        verify(store).recordFailure(eq(claimed), captor.capture());
        assertThat(captor.getValue().classification()).isEqualTo("TIMEOUT");
        assertThat(captor.getValue().detail()).isEqualTo("request timed out");
    }

    // ------------------------------------------------------------ 解析失敗：不可洩漏 body

    @Test
    @DisplayName("解析失敗：分類是 PARSE_ERROR，細節只有長度，絕不含例外訊息或回應內容")
    void parseFailure_neverLeaksResponseBody() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        String body = "{not-json, contains SECRET_TOKEN=abc123}";
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", body));
        when(changeDetector.detectByFingerprint(any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Response body is not valid JSON: unexpected token near SECRET_TOKEN=abc123"));

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Failure> captor = ArgumentCaptor.forClass(RunAttempt.Failure.class);
        verify(store).recordFailure(eq(claimed), captor.capture());
        RunAttempt.Failure failure = captor.getValue();
        assertThat(failure.classification()).isEqualTo("PARSE_ERROR");
        assertThat(failure.detail()).isEqualTo("body length=" + body.length());
        assertThat(failure.detail()).doesNotContain("SECRET_TOKEN");
        assertThat(failure.httpStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------ 成功：Unchanged

    @Test
    @DisplayName("Unchanged：renderedMessage 是 null，且不呼叫 MessageTemplate.render")
    void unchanged_doesNotRenderMessage() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{}"));
        ChangeResult.Unchanged unchanged = new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of());
        when(changeDetector.detectByFingerprint(any(), any(), any(), any(), any())).thenReturn(unchanged);

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Success> captor = ArgumentCaptor.forClass(RunAttempt.Success.class);
        verify(store).recordSuccess(eq(claimed), captor.capture());
        assertThat(captor.getValue().renderedMessage()).isNull();
        assertThat(captor.getValue().changeResult()).isSameAs(unchanged);
        verify(messageTemplate, never()).render(anyString(), any());
    }

    // ------------------------------------------------------------ 成功：Changed（一般模式）

    @Test
    @DisplayName("Changed（WHOLE_BODY/EXTRACTED）：只渲染一次，帶上 current/previous values")
    void changed_wholeBodyMode_rendersOnce() {
        ClaimedMonitor claimed = monitor(CompareMode.EXTRACTED);
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"status\":\"BAD\"}"));
        ChangeResult.Changed changed = new ChangeResult.Changed(
                new byte[]{2}, Map.of("status", "BAD"), Map.of("status", "OK"), List.of());
        when(changeDetector.detectByFingerprint(any(), any(), any(), any(), any())).thenReturn(changed);
        when(messageTemplate.render(eq("{{value.status}}"), any())).thenReturn("狀態變成 BAD 了");

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Success> captor = ArgumentCaptor.forClass(RunAttempt.Success.class);
        verify(store).recordSuccess(eq(claimed), captor.capture());
        assertThat(captor.getValue().renderedMessage()).isEqualTo("狀態變成 BAD 了");
        verify(messageTemplate, times(1)).render(anyString(), any());
    }

    // ------------------------------------------------------------ 成功：Changed（NEW_ITEMS）

    @Test
    @DisplayName("NEW_ITEMS：15 筆全部渲染，沒有「還有 N 筆」")
    void newItems_underCap_noSuffix() {
        ClaimedMonitor claimed = newItemsMonitor();
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"items\":[]}"));
        List<ChangeResult.NewItem> items = newItemList(15);
        ChangeResult.Changed changed = new ChangeResult.Changed(null, Map.of(), Map.of(), items);
        when(changeDetector.detectNewItems(any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(changed);
        when(messageTemplate.render(eq("{{item.title}}"), any()))
                .thenAnswer(inv -> {
                    MessageTemplate.RenderContext ctx = inv.getArgument(1);
                    return "item:" + ctx.itemValues().get("title");
                });

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Success> captor = ArgumentCaptor.forClass(RunAttempt.Success.class);
        verify(store).recordSuccess(eq(claimed), captor.capture());
        String message = captor.getValue().renderedMessage();
        assertThat(message).doesNotContain("還有");
        assertThat(message.lines().count()).isEqualTo(15);
        verify(messageTemplate, times(15)).render(anyString(), any());
    }

    @Test
    @DisplayName("NEW_ITEMS：超過 20 筆時只渲染 20 筆，並附加「還有 N 筆」")
    void newItems_overCap_rendersOnly20AndAppendsSuffix() {
        ClaimedMonitor claimed = newItemsMonitor();
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"items\":[]}"));
        List<ChangeResult.NewItem> items = newItemList(27);
        ChangeResult.Changed changed = new ChangeResult.Changed(null, Map.of(), Map.of(), items);
        when(changeDetector.detectNewItems(any(), any(), any(), any(), any(), any(Boolean.class)))
                .thenReturn(changed);
        when(messageTemplate.render(eq("{{item.title}}"), any()))
                .thenAnswer(inv -> {
                    MessageTemplate.RenderContext ctx = inv.getArgument(1);
                    return "item:" + ctx.itemValues().get("title");
                });

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Success> captor = ArgumentCaptor.forClass(RunAttempt.Success.class);
        verify(store).recordSuccess(eq(claimed), captor.capture());
        String message = captor.getValue().renderedMessage();
        assertThat(message).contains("還有 7 筆");
        verify(messageTemplate, times(20)).render(anyString(), any());
    }

    @Test
    @DisplayName("NEW_ITEMS：firstRun 值原樣傳給 ChangeDetector.detectNewItems")
    void newItems_passesFirstRunFlagThrough() {
        ClaimedMonitor claimed = newItemsMonitor(); // firstRun = true
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"items\":[]}"));
        when(changeDetector.detectNewItems(any(), any(), any(), any(), any(), eq(true)))
                .thenReturn(new ChangeResult.Unchanged(null, Map.of(), List.of()));

        runner.runOnce();

        verify(changeDetector).detectNewItems(
                any(), eq("/items"), eq("/id"), eq(claimed.extractRules()), eq(claimed.seenKeys()), eq(true));
    }

    // ------------------------------------------------------------ 站台登入狀態（W6）

    @Test
    @DisplayName("抓取前呼叫 attachCookies，帶上替換後的請求 host")
    void attachesCookiesBeforeFetch_withResolvedHost() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{}"));
        when(changeDetector.detectByFingerprint(any(), any(), any(), any(), any()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));

        runner.runOnce();

        verify(siteSessionService).attachCookies(eq("target.example"), eq(Map.of()));
    }

    @Test
    @DisplayName("attachCookies 回傳的 header（含附加的 Cookie）原樣送進 fetch")
    void fetchUsesHeadersReturnedByAttachCookies() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        Map<String, String> withCookie = Map.of("cookie", "session=abc");
        when(siteSessionService.attachCookies(eq("target.example"), any())).thenReturn(withCookie);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{}"));
        when(changeDetector.detectByFingerprint(any(), any(), any(), any(), any()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));

        runner.runOnce();

        ArgumentCaptor<ApiFetcher.FetchRequest> captor = ArgumentCaptor.forClass(ApiFetcher.FetchRequest.class);
        verify(fetcher).fetch(captor.capture());
        assertThat(captor.getValue().headers()).isEqualTo(withCookie);
    }

    @Test
    @DisplayName("回應後把 Set-Cookie 交給 mergeSetCookies，帶上請求 host")
    void mergesSetCookiesAfterFetch() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(
                new FetchResult.Success(200, "application/json", "{}", List.of("session=new; Path=/")));
        when(changeDetector.detectByFingerprint(any(), any(), any(), any(), any()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));

        runner.runOnce();

        verify(siteSessionService).mergeSetCookies("target.example", List.of("session=new; Path=/"));
    }

    @Test
    @DisplayName("guard 擋下時完全不呼叫 attachCookies——連 fetch 都沒發生")
    void guardBlocked_doesNotAttachCookies() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        doThrow(new OutboundUrlGuard.BlockedException("blocked"))
                .when(guard).check(URI.create(claimed.url()));

        runner.runOnce();

        verify(siteSessionService, never()).attachCookies(any(), any());
        verify(siteSessionService, never()).mergeSetCookies(any(), any());
    }

    @Test
    @DisplayName("fetch 失敗（例如 401）一樣會嘗試合併 Set-Cookie，且失敗紀錄帶上 host")
    void fetchFailure_stillMergesSetCookies_andRecordsHost() {
        ClaimedMonitor claimed = monitor(CompareMode.WHOLE_BODY);
        claims(claimed);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Failure(
                FetchResult.Reason.HTTP_ERROR, "HTTP 401", 401, List.of("csrf=fresh")));

        runner.runOnce();

        verify(siteSessionService).mergeSetCookies("target.example", List.of("csrf=fresh"));
        ArgumentCaptor<RunAttempt.Failure> captor = ArgumentCaptor.forClass(RunAttempt.Failure.class);
        verify(store).recordFailure(eq(claimed), captor.capture());
        assertThat(captor.getValue().host()).isEqualTo("target.example");
        assertThat(captor.getValue().httpStatus()).isEqualTo(401);
    }

    private static List<ChangeResult.NewItem> newItemList(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ChangeResult.NewItem("key-" + i, Map.of("title", "title-" + i)))
                .toList();
    }
}
