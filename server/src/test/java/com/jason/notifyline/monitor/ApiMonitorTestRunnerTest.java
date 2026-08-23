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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApiMonitorTestRunner} 的編排邏輯：純 Mockito 單元測試。
 *
 * <p>重點驗證安全需求：guard 擋下時<strong>完全不呼叫 fetcher</strong>（見
 * {@code guardBlocked_returnsBlockedWithoutFetching}），以及解析失敗時絕不洩漏回應內容
 * ——理由與寫法都對照 {@link ApiMonitorRunnerTest}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiMonitorTestRunner")
class ApiMonitorTestRunnerTest {

    @Mock
    private OutboundUrlGuard guard;
    @Mock
    private ApiFetcher fetcher;
    @Mock
    private ChangeDetector changeDetector;
    @Mock
    private MessageTemplate messageTemplate;

    private ApiMonitorTestRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ApiMonitorTestRunner(guard, fetcher, changeDetector, messageTemplate);
    }

    private static ApiMonitorTestRunner.TestConfig config(CompareMode mode) {
        return new ApiMonitorTestRunner.TestConfig(
                "test monitor", URI.create("https://target.example/api"), "GET", null, Map.of(),
                mode, List.of(new ExtractRule("status", "/status")), null, null, "{{value.status}}");
    }

    // ------------------------------------------------------------ guard 擋下

    @Test
    @DisplayName("guard 擋下：回 Blocked，完全不呼叫 fetcher——安全需求：nothing fetched")
    void guardBlocked_returnsBlockedWithoutFetching() {
        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.WHOLE_BODY);
        doThrow(new OutboundUrlGuard.BlockedException("Target host resolves to a disallowed network address."))
                .when(guard).check(cfg.uri());

        MonitorTestOutcome outcome = runner.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.Blocked.class);
        assertThat(((MonitorTestOutcome.Blocked) outcome).message())
                .isEqualTo("Target host resolves to a disallowed network address.");
        verify(fetcher, never()).fetch(any());
    }

    // ------------------------------------------------------------ fetch 失敗

    @Test
    @DisplayName("fetch 失敗：回 FetchFailed，帶原因、細節、httpStatus")
    void fetchFailure_returnsFetchFailed() {
        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.WHOLE_BODY);
        when(fetcher.fetch(any())).thenReturn(
                new FetchResult.Failure(FetchResult.Reason.TIMEOUT, "request timed out", null));

        MonitorTestOutcome outcome = runner.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.FetchFailed.class);
        MonitorTestOutcome.FetchFailed failed = (MonitorTestOutcome.FetchFailed) outcome;
        assertThat(failed.reason()).isEqualTo("TIMEOUT");
        assertThat(failed.detail()).isEqualTo("request timed out");
        assertThat(failed.httpStatus()).isNull();
    }

    // ------------------------------------------------------------ 解析失敗：不可洩漏 body

    @Test
    @DisplayName("解析失敗：ParseFailed 只帶長度，不含例外訊息或回應內容")
    void parseFailure_neverLeaksResponseBody() {
        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.EXTRACTED);
        String body = "{not-json, SECRET_TOKEN=abc123}";
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", body));
        when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Response body is not valid JSON: unexpected token near SECRET_TOKEN=abc123"));

        MonitorTestOutcome outcome = runner.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.ParseFailed.class);
        String detail = ((MonitorTestOutcome.ParseFailed) outcome).detail();
        assertThat(detail).isEqualTo("body length=" + body.length());
        assertThat(detail).doesNotContain("SECRET_TOKEN");
    }

    // ------------------------------------------------------------ 成功：WHOLE_BODY/EXTRACTED

    @Test
    @DisplayName("成功（EXTRACTED）：previousFingerprint=null 視同首次執行，取出目前的值並渲染")
    void success_fingerprintMode_extractsCurrentValuesAndRenders() {
        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.EXTRACTED);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"status\":\"OK\"}"));
        ChangeResult.Unchanged unchanged =
                new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of());
        when(changeDetector.detectByFingerprint(eq(CompareMode.EXTRACTED), eq("{\"status\":\"OK\"}"),
                eq(cfg.extractRules()), isNull(), isNull())).thenReturn(unchanged);
        when(messageTemplate.render(eq("{{value.status}}"), any())).thenReturn("目前狀態：OK");

        MonitorTestOutcome outcome = runner.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.Success.class);
        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) outcome;
        assertThat(success.httpStatus()).isEqualTo(200);
        assertThat(success.values()).containsEntry("status", "OK");
        assertThat(success.items()).isEmpty();
        assertThat(success.renderedMessage()).isEqualTo("目前狀態：OK");
    }

    // ------------------------------------------------------------ 成功：NEW_ITEMS

    @Test
    @DisplayName("成功（NEW_ITEMS）：seenKeys 為空、firstRun=true，全部項目都當成預覽")
    void success_newItemsMode_previewsAllItemsAsNew() {
        ApiMonitorTestRunner.TestConfig cfg = new ApiMonitorTestRunner.TestConfig(
                "feed monitor", URI.create("https://target.example/feed"), "GET", null, Map.of(),
                CompareMode.NEW_ITEMS, List.of(new ExtractRule("title", "/title")), "/items", "/id",
                "{{item.title}}");
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"items\":[]}"));
        List<ChangeResult.NewItem> items = List.of(
                new ChangeResult.NewItem("k1", Map.of("title", "hello")),
                new ChangeResult.NewItem("k2", Map.of("title", "world")));
        ChangeResult.Unchanged unchanged = new ChangeResult.Unchanged(null, Map.of(), items);
        when(changeDetector.detectNewItems(eq("{\"items\":[]}"), eq("/items"), eq("/id"),
                eq(cfg.extractRules()), eq(Set.of()), eq(true))).thenReturn(unchanged);
        when(messageTemplate.render(eq("{{item.title}}"), any()))
                .thenAnswer(inv -> {
                    MessageTemplate.RenderContext ctx = inv.getArgument(1);
                    return "item:" + ctx.itemValues().get("title");
                });

        MonitorTestOutcome outcome = runner.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.Success.class);
        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) outcome;
        assertThat(success.values()).isEmpty();
        assertThat(success.items()).hasSize(2);
        assertThat(success.items().get(0).itemKey()).isEqualTo("k1");
        assertThat(success.renderedMessage()).isEqualTo("item:hello\nitem:world");
    }

    @Test
    @DisplayName("NEW_ITEMS 沒有任何項目：渲染出空字串，不呼叫 MessageTemplate.render")
    void success_newItemsMode_emptyArray_rendersEmptyMessage() {
        ApiMonitorTestRunner.TestConfig cfg = new ApiMonitorTestRunner.TestConfig(
                "feed monitor", URI.create("https://target.example/feed"), "GET", null, Map.of(),
                CompareMode.NEW_ITEMS, List.of(), "/items", "/id", "{{item.title}}");
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"items\":[]}"));
        when(changeDetector.detectNewItems(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new ChangeResult.Unchanged(null, Map.of(), List.of()));

        MonitorTestOutcome outcome = runner.run(cfg);

        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) outcome;
        assertThat(success.items()).isEmpty();
        assertThat(success.renderedMessage()).isEmpty();
        verify(messageTemplate, never()).render(anyString(), any());
    }
}
