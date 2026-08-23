package com.jason.notifyline.monitor;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.monitor.fetch.ApiFetcher;
import com.jason.notifyline.monitor.fetch.FetchResult;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.monitor.parse.ChangeDetector;
import com.jason.notifyline.monitor.parse.ChangeResult;
import com.jason.notifyline.monitor.parse.MessageTemplate;
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
        runner = new ApiMonitorRunner(
                guard, fetcher, changeDetector, messageTemplate, store, properties, SAME_THREAD_EXECUTOR, clock);
    }

    private static ClaimedMonitor monitor(CompareMode mode) {
        return new ClaimedMonitor(
                1L, "my monitor", URI.create("https://target.example/api"), "GET", null, Map.of(),
                mode, List.of(new ExtractRule("status", "/status")), null, null,
                "{{value.status}}", null, Map.of(), true, Set.of());
    }

    private static ClaimedMonitor newItemsMonitor() {
        return new ClaimedMonitor(
                2L, "feed monitor", URI.create("https://target.example/feed"), "GET", null, Map.of(),
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
                .when(guard).check(claimed.uri());

        runner.runOnce();

        ArgumentCaptor<RunAttempt.Failure> captor = ArgumentCaptor.forClass(RunAttempt.Failure.class);
        verify(store).recordFailure(eq(claimed), captor.capture());
        assertThat(captor.getValue().classification()).isEqualTo("BLOCKED_URL");
        assertThat(captor.getValue().httpStatus()).isNull();
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

    private static List<ChangeResult.NewItem> newItemList(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ChangeResult.NewItem("key-" + i, Map.of("title", "title-" + i)))
                .toList();
    }
}
