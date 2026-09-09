package com.jason.notifyline.monitor;

import com.jason.notifyline.auth.SecretCipher;
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
import com.jason.notifyline.monitor.session.SiteSession;
import com.jason.notifyline.monitor.session.SiteSessionRepository;
import com.jason.notifyline.monitor.login.CognitoAuthException;
import com.jason.notifyline.monitor.login.ResolvedLoginHeader;
import com.jason.notifyline.monitor.login.SiteLoginService;
import com.jason.notifyline.monitor.session.SiteSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
    private SiteSessionService siteSessionService;
    @Mock
    private SiteLoginService siteLoginService;
    @Mock
    private ChangeDetector changeDetector;
    @Mock
    private MessageTemplate messageTemplate;

    private ApiMonitorTestRunner runner;

    @BeforeEach
    void setUp() {
        // 預設原樣傳回 header：這個類別要驗證的是編排邏輯，不是 cookie jar 本身的行為
        // （那是 SiteSessionServiceTest、以及本檔案下方「站台登入狀態」區塊用真的
        // SiteSessionService 覆蓋的職責）。lenient()——guard 擋下等案例根本不會走到
        // attachCookies，嚴格模式下會被判定成「多餘的 stub」，理由同 ApiMonitorRunnerTest。
        lenient().when(siteSessionService.attachCookies(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        runner = new ApiMonitorTestRunner(guard, fetcher, siteSessionService, siteLoginService, changeDetector, messageTemplate);
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
        verify(siteSessionService, never()).attachCookies(any(), any());
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
        // 這是這一波（Docs/plan/12-API監控易用性升級.md §4.2）刻意放寬的部分：成功時要帶原始 body，
        // 供前端畫欄位選取樹——見 MonitorTestOutcome 類別註解「這是刻意放寬的例外」。
        assertThat(success.body()).isEqualTo("{\"status\":\"OK\"}");
        assertThat(success.bodyTruncated()).isFalse();
        assertThat(success.bodyOriginalLength()).isEqualTo("{\"status\":\"OK\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    // ------------------------------------------------------------ 回傳給瀏覽器的 body：256 KB 第二層上限

    private static final int MAX_TEST_BODY_BYTES = 256 * 1024;

    /** 建一段剛好 {@code byteLength} 位元組（全 ASCII，位元組數＝字元數）的合法 JSON 字串：{"pad":"aaa...a"}。 */
    private static String jsonBodyOfByteLength(int byteLength) {
        String prefix = "{\"pad\":\"";
        String suffix = "\"}";
        int padLength = byteLength - prefix.length() - suffix.length();
        return prefix + "a".repeat(padLength) + suffix;
    }

    @Test
    @DisplayName("body 剛好等於 256 KB 上限：不截斷，bodyOriginalLength 等於實際長度")
    void success_bodyExactlyAtCap_notTruncated() {
        ApiMonitorTestRunner.TestConfig cfg = new ApiMonitorTestRunner.TestConfig(
                "test monitor", URI.create("https://target.example/api"), "GET", null, Map.of(),
                CompareMode.WHOLE_BODY, List.of(), null, null, "{{value.x}}");
        String body = jsonBodyOfByteLength(MAX_TEST_BODY_BYTES);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", body));
        when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of(), List.of()));
        when(messageTemplate.render(any(), any())).thenReturn("");

        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) runner.run(cfg);

        assertThat(success.bodyTruncated()).isFalse();
        assertThat(success.bodyOriginalLength()).isEqualTo(MAX_TEST_BODY_BYTES);
        assertThat(success.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("body 超過 256 KB 上限一個位元組：截斷，bodyOriginalLength 保留原始長度，回傳的 body 縮到上限以內")
    void success_bodyExceedsCapByOneByte_isTruncated() {
        ApiMonitorTestRunner.TestConfig cfg = new ApiMonitorTestRunner.TestConfig(
                "test monitor", URI.create("https://target.example/api"), "GET", null, Map.of(),
                CompareMode.WHOLE_BODY, List.of(), null, null, "{{value.x}}");
        String body = jsonBodyOfByteLength(MAX_TEST_BODY_BYTES + 1);
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", body));
        when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of(), List.of()));
        when(messageTemplate.render(any(), any())).thenReturn("");

        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) runner.run(cfg);

        assertThat(success.bodyTruncated()).isTrue();
        assertThat(success.bodyOriginalLength()).isEqualTo(MAX_TEST_BODY_BYTES + 1);
        assertThat(success.body()).hasSize(MAX_TEST_BODY_BYTES);
        assertThat(body).startsWith(success.body());
    }

    @Test
    @DisplayName("body 被截斷時，values／renderedMessage 仍是用完整、未截斷的 body 算出來的")
    void success_bodyTruncated_valuesStillComputedFromFullBody() {
        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.EXTRACTED); // extractRule: status -> /status
        String tail = "{\"status\":\"OK\"}"; // 真正要抽取的內容藏在超過截斷點之後
        String padding = "a".repeat(MAX_TEST_BODY_BYTES + 1000);
        String hugeBody = "{\"pad\":\"" + padding + "\",\"status\":\"OK\"}";
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", hugeBody));
        when(changeDetector.detectByFingerprint(eq(CompareMode.EXTRACTED), eq(hugeBody),
                eq(cfg.extractRules()), isNull(), isNull()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));
        when(messageTemplate.render(eq("{{value.status}}"), any())).thenReturn("目前狀態：OK");

        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) runner.run(cfg);

        // changeDetector 收到的是完整、未截斷的 hugeBody（上面的 eq(hugeBody) 就是這個斷言的一部分：
        // 如果 runner 不小心把截斷後的字串傳進去，這個 stub 根本不會命中，changeDetector 會回
        // Mockito 的預設值而不是 unchanged，下面兩個斷言就會失敗）。
        assertThat(success.values()).containsEntry("status", "OK");
        assertThat(success.renderedMessage()).isEqualTo("目前狀態：OK");
        // 但回給瀏覽器的 body 本身確實被截斷了。
        assertThat(success.bodyTruncated()).isTrue();
        assertThat(success.body()).hasSize(MAX_TEST_BODY_BYTES);
    }

    @Test
    @DisplayName("body 內容絕不出現在任何 log 事件裡，即使成功回傳了它")
    void success_neverLogsBodyContent() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApiMonitorTestRunner.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String secretMarker = "SECRET_TOKEN_98765";
            String body = "{\"status\":\"" + secretMarker + "\"}";
            ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.EXTRACTED);
            when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", body));
            when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                    .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", secretMarker), List.of()));
            when(messageTemplate.render(any(), any())).thenReturn(secretMarker);

            MonitorTestOutcome outcome = runner.run(cfg);

            // 先確認這個 marker 真的有被回傳出去（不然下面「沒出現在 log 裡」的斷言毫無意義——
            // 可能只是根本沒觸發到會記錄的路徑）。
            assertThat(outcome).isInstanceOf(MonitorTestOutcome.Success.class);
            assertThat(((MonitorTestOutcome.Success) outcome).body()).contains(secretMarker);
            assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(secretMarker));
        } finally {
            logger.detachAppender(appender);
        }
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

    /**
     * 點選欄位流程的第一步：NEW_ITEMS 模式下，使用者按「立即測試」時 itemPointer／itemKeyPointer
     * 通常還沒設定（要靠這次抓到的 body 畫出樹，再從樹裡點選才能設定）。這時不可以呼叫
     * {@link ChangeDetector#detectNewItems}——它會把「兩個 pointer 都還沒指到正確位置」
     * 誤判成 ParseFailed，讓使用者連 body 都看不到，卡死整個 picker 流程。見
     * {@code ApiMonitorTestRunner.previewNewItems} 的說明。
     */
    @Test
    @DisplayName("NEW_ITEMS 且 itemPointer／itemKeyPointer 都還沒設定：回 Success 帶 body，不呼叫 ChangeDetector")
    void success_newItemsMode_pointersNotYetSet_returnsBodyWithoutCallingChangeDetector() {
        ApiMonitorTestRunner.TestConfig cfg = new ApiMonitorTestRunner.TestConfig(
                "feed monitor", URI.create("https://target.example/feed"), "GET", null, Map.of(),
                CompareMode.NEW_ITEMS, List.of(), null, null, "{{item.title}}");
        String body = "{\"items\":[{\"id\":1,\"title\":\"hello\"}]}";
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", body));

        MonitorTestOutcome outcome = runner.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.Success.class);
        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) outcome;
        assertThat(success.body()).isEqualTo(body);
        assertThat(success.bodyTruncated()).isFalse();
        assertThat(success.items()).isEmpty();
        assertThat(success.values()).isEmpty();
        assertThat(success.renderedMessage()).isEmpty();
        verify(changeDetector, never()).detectNewItems(any(), any(), any(), any(), any(), anyBoolean());
        verify(messageTemplate, never()).render(anyString(), any());
    }

    @Test
    @DisplayName("NEW_ITEMS 且只有 itemPointer 設了、itemKeyPointer 還沒設：同樣回 Success 帶 body，不呼叫 ChangeDetector")
    void success_newItemsMode_onlyItemPointerSet_returnsBodyWithoutCallingChangeDetector() {
        ApiMonitorTestRunner.TestConfig cfg = new ApiMonitorTestRunner.TestConfig(
                "feed monitor", URI.create("https://target.example/feed"), "GET", null, Map.of(),
                CompareMode.NEW_ITEMS, List.of(), "/items", null, "{{item.title}}");
        String body = "{\"items\":[{\"id\":1,\"title\":\"hello\"}]}";
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", body));

        MonitorTestOutcome outcome = runner.run(cfg);

        MonitorTestOutcome.Success success = (MonitorTestOutcome.Success) outcome;
        assertThat(success.body()).isEqualTo(body);
        assertThat(success.items()).isEmpty();
        verify(changeDetector, never()).detectNewItems(any(), any(), any(), any(), any(), anyBoolean());
    }

    // ------------------------------------------------------------ 站台登入狀態（cookie jar，見類別註解）

    /**
     * 以下三個案例刻意繞開 {@code setUp()} 裡那顆 mock 的 {@code siteSessionService}，
     * 改用真的 {@link SiteSessionService}（只有 {@link SiteSessionRepository} 是 mock）——
     * 這裡要證明的是「試跑真的不會讓 jar 的密文被動過」，純 mock 只能驗證呼叫次數，
     * 用真的 {@link SiteSessionService} + {@link SecretCipher} 才能連解密後的內容都比對，
     * 而不是只看「沒有丟例外」。stub 的 fetch/changeDetector/messageTemplate 沿用
     * 外層 {@code @Mock} 欄位——這三者不是這裡要驗證的重點。
     */
    private static final byte[] TEST_JAR_KEY = new byte[32]; // 全零測試金鑰，僅供測試用

    private SiteSessionService realSiteSessionService(SiteSessionRepository repository) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
        return new SiteSessionService(
                repository, new SecretCipher(Map.of(1, TEST_JAR_KEY), 1), new ObjectMapper(), clock);
    }

    private SiteSession jarFor(SecretCipher cipher, String host, Map<String, String> cookies) {
        String plaintext = new ObjectMapper().writeValueAsString(cookies);
        var encrypted = cipher.encrypt(plaintext, "site_session:" + host);
        return new SiteSession(host, encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(),
                String.join(",", cookies.keySet()), Instant.parse("2026-08-24T00:00:00Z"));
    }

    private Map<String, String> decryptJar(SecretCipher cipher, SiteSession jar) {
        String plaintext = cipher.decrypt(
                jar.getJarCiphertext(), jar.getJarIv(), jar.getJarKeyVersion(), "site_session:" + jar.getHost());
        return new ObjectMapper().readValue(plaintext, Map.class);
    }

    @Test
    @DisplayName("有 jar 的 host：outgoing request 帶上 jar 的 Cookie header")
    void testRun_hostWithStoredJar_outgoingRequestCarriesCookieHeader() {
        SiteSessionRepository repository = mock(SiteSessionRepository.class);
        SecretCipher cipher = new SecretCipher(Map.of(1, TEST_JAR_KEY), 1);
        SiteSession jar = jarFor(cipher, "target.example", Map.of("session", "abc"));
        when(repository.findAll()).thenReturn(List.of(jar));
        ApiMonitorTestRunner runnerWithRealSession = new ApiMonitorTestRunner(
                guard, fetcher, realSiteSessionService(repository), siteLoginService, changeDetector, messageTemplate);

        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.WHOLE_BODY); // host = target.example
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"status\":\"OK\"}"));
        when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));

        runnerWithRealSession.run(cfg);

        ArgumentCaptor<ApiFetcher.FetchRequest> captor = ArgumentCaptor.forClass(ApiFetcher.FetchRequest.class);
        verify(fetcher).fetch(captor.capture());
        assertThat(captor.getValue().headers()).containsEntry("Cookie", "session=abc");
    }

    @Test
    @DisplayName("沒有 jar 的 host：outgoing request 沒有 Cookie header，也不丟例外")
    void testRun_hostWithoutJar_noCookieHeaderAndNoError() {
        SiteSessionRepository repository = mock(SiteSessionRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        ApiMonitorTestRunner runnerWithRealSession = new ApiMonitorTestRunner(
                guard, fetcher, realSiteSessionService(repository), siteLoginService, changeDetector, messageTemplate);

        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.WHOLE_BODY); // host = target.example，沒有存過 jar
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"status\":\"OK\"}"));
        when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));

        MonitorTestOutcome outcome = runnerWithRealSession.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.Success.class);
        ArgumentCaptor<ApiFetcher.FetchRequest> captor = ArgumentCaptor.forClass(ApiFetcher.FetchRequest.class);
        verify(fetcher).fetch(captor.capture());
        assertThat(captor.getValue().headers()).doesNotContainKey("Cookie");
        assertThat(captor.getValue().headers()).doesNotContainKey("cookie");
    }

    @Test
    @DisplayName("★ 試跑收到 Set-Cookie：絕不合併回寫，jar 的密文與解密內容前後完全不變")
    void testRun_receivesSetCookie_neverMutatesStoredJar() {
        SiteSessionRepository repository = mock(SiteSessionRepository.class);
        SecretCipher cipher = new SecretCipher(Map.of(1, TEST_JAR_KEY), 1);
        SiteSession jar = jarFor(cipher, "target.example", Map.of("session", "abc"));
        when(repository.findAll()).thenReturn(List.of(jar));
        ApiMonitorTestRunner runnerWithRealSession = new ApiMonitorTestRunner(
                guard, fetcher, realSiteSessionService(repository), siteLoginService, changeDetector, messageTemplate);

        byte[] ciphertextBefore = jar.getJarCiphertext();
        Map<String, String> decryptedBefore = decryptJar(cipher, jar);

        ApiMonitorTestRunner.TestConfig cfg = config(CompareMode.WHOLE_BODY); // host = target.example
        // 回應夾帶 Set-Cookie，模擬目標端點想輪換 session——這正是最容易讓人手滑呼叫
        // mergeSetCookies 的情境，這裡要驗證的就是它沒有發生。
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(
                200, "application/json", "{\"status\":\"OK\"}", List.of("session=rotated-by-target; Path=/")));
        when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));

        MonitorTestOutcome outcome = runnerWithRealSession.run(cfg);

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.Success.class);
        // repository.save 從未被呼叫——證明整個合併回寫路徑真的沒被觸發，不是巧合地
        // 內容剛好一樣。
        verify(repository, never()).save(any());
        assertThat(jar.getJarCiphertext()).isEqualTo(ciphertextBefore);
        Map<String, String> decryptedAfter = decryptJar(cipher, jar);
        assertThat(decryptedAfter).isEqualTo(decryptedBefore);
        assertThat(decryptedAfter).containsEntry("session", "abc");
        assertThat(decryptedAfter).doesNotContainValue("rotated-by-target");
    }

    // ------------------------------------------------------------ 站台登入（W16）

    /**
     * 這一組釘住的是一個真的發生過的缺口：站台登入加進來時，試跑這條路徑沒有跟上。
     * 使用者設了站台登入、又（正確地）把手貼的過期 authorization 刪掉之後，試跑變成
     * 完全沒有認證 header，必然 401 —— 而排程其實是好的。
     */
    private static ApiMonitorTestRunner.TestConfig configWithLogin(Map<String, String> headers) {
        return new ApiMonitorTestRunner.TestConfig(
                "login monitor", URI.create("https://target.example/api"), "GET", null, headers,
                CompareMode.WHOLE_BODY, List.of(new ExtractRule("status", "/status")), null, null,
                "{{value.status}}", Map.of(), 42L);
    }

    private void stubSuccessfulFetchAndParse() {
        when(fetcher.fetch(any())).thenReturn(new FetchResult.Success(200, "application/json", "{\"status\":\"OK\"}"));
        when(changeDetector.detectByFingerprint(any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of()));
    }

    @Test
    @DisplayName("選了站台登入：試跑也要帶上 token，否則預覽的不是排程會送出的請求")
    void withLogin_injectsToken() {
        when(siteLoginService.resolve(42L))
                .thenReturn(new ResolvedLoginHeader("Authorization", "Bearer FRESH-TOKEN"));
        stubSuccessfulFetchAndParse();

        MonitorTestOutcome outcome = runner.run(configWithLogin(Map.of("accept", "application/json")));

        assertThat(outcome).isInstanceOf(MonitorTestOutcome.Success.class);
        ArgumentCaptor<ApiFetcher.FetchRequest> captor = ArgumentCaptor.forClass(ApiFetcher.FetchRequest.class);
        verify(fetcher).fetch(captor.capture());
        assertThat(captor.getValue().headers())
                .containsEntry("Authorization", "Bearer FRESH-TOKEN")
                .containsEntry("accept", "application/json");
    }

    @Test
    @DisplayName("自訂 header 有大小寫不同的同名項目：換掉它，不會兩顆 token 都送出去")
    void withLogin_replacesCaseInsensitiveDuplicate() {
        when(siteLoginService.resolve(42L))
                .thenReturn(new ResolvedLoginHeader("Authorization", "Bearer FRESH-TOKEN"));
        stubSuccessfulFetchAndParse();

        runner.run(configWithLogin(Map.of("authorization", "Bearer EXPIRED-TOKEN")));

        ArgumentCaptor<ApiFetcher.FetchRequest> captor = ArgumentCaptor.forClass(ApiFetcher.FetchRequest.class);
        verify(fetcher).fetch(captor.capture());
        Map<String, String> sent = captor.getValue().headers();
        assertThat(sent.keySet().stream().filter(k -> k.equalsIgnoreCase("authorization")))
                .as("同名 header 只能有一個").hasSize(1);
        assertThat(sent.values()).doesNotContain("Bearer EXPIRED-TOKEN");
    }

    @Test
    @DisplayName("登入失敗：回 LoginFailed 且完全不呼叫 fetcher —— 沒 token 的 401 只會誤導")
    void loginFailure_returnsLoginFailedWithoutFetching() {
        when(siteLoginService.resolve(42L)).thenThrow(new CognitoAuthException(
                CognitoAuthException.Reason.INVALID_CREDENTIALS,
                "NotAuthorizedException: Incorrect username or password."));

        MonitorTestOutcome outcome = runner.run(configWithLogin(Map.of()));

        assertThat(outcome).isInstanceOfSatisfying(MonitorTestOutcome.LoginFailed.class, failed -> {
            assertThat(failed.reason()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(failed.message()).contains("NotAuthorizedException");
        });
        verify(fetcher, never()).fetch(any());
    }

    @Test
    @DisplayName("沒選站台登入：完全不碰 SiteLoginService")
    void withoutLogin_neverResolves() {
        stubSuccessfulFetchAndParse();

        runner.run(config(CompareMode.WHOLE_BODY));

        verify(siteLoginService, never()).resolve(any());
    }
}
