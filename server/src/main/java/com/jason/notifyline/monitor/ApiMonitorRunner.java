package com.jason.notifyline.monitor;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.LineLimits;
import com.jason.notifyline.monitor.compute.ComputedFieldEvaluator;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.fetch.ApiFetcher;
import com.jason.notifyline.monitor.fetch.FetchResult;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.monitor.login.CognitoAuthException;
import com.jason.notifyline.monitor.login.ResolvedLoginHeader;
import com.jason.notifyline.monitor.login.SiteLoginService;
import com.jason.notifyline.monitor.parse.ChangeDetector;
import com.jason.notifyline.monitor.parse.ChangeResult;
import com.jason.notifyline.monitor.parse.MessageTemplate;
import com.jason.notifyline.monitor.request.RequestTemplate;
import com.jason.notifyline.monitor.session.SiteSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 取件 → 抓取目標 API → 解析 → 比對 → 回寫。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §7。
 *
 * <h2>為什麼這個類別完全沒有 {@code @Transactional}</h2>
 *
 * <p><strong>交易絕不可以撐過對目標 API 的 HTTP 呼叫。</strong> Hikari 只有 10 條連線，
 * 監控打的是使用者自己設定的第三方 API，逾時可以長達
 * {@code connect-timeout + read-timeout}（預設 5s + 10s）。把連線握在手上等一個
 * 素不相識的第三方變慢，結果是連線池被佔滿、<strong>連健康檢查都回不了</strong>——
 * 這正是 ADR-0007 的教訓，也是 {@code DeliveryDispatcher} 已經示範過的三段式：
 * ① {@link ApiMonitorStore#claim} 短交易取快照 → ② 這裡做無交易的網路呼叫 →
 * ③ {@link ApiMonitorStore#recordSuccess} / {@link ApiMonitorStore#recordFailure}
 * 短交易回寫。
 *
 * <h2>獨立執行緒池</h2>
 *
 * <p>每筆監控的抓取工作丟到{@code monitorTaskExecutor}（見 {@link MonitorAsyncConfig}）
 * 上平行處理，<strong>不共用</strong>派送用的 {@code notifyTaskExecutor}，也不佔用
 * {@code spring.task.scheduling} 那個只有 3 條執行緒、被好幾支排程共用的池子。
 * 一個慢掉的第三方 API 不該拖慢 LINE 發送，也不該拖慢 webhook 事件清理這些
 * 完全無關的排程工作。
 *
 * <p>{@link #runOnce()} 會等這一輪送出去的工作全部做完（有逾時保底）才返回——
 * 這不是「刻意讓排程執行緒卡住」，而是讓呼叫端（{@link ApiMonitorScheduler}
 * 與測試）能確定「這一輪」真的處理完了，不必另外設計輪詢或回呼機制。實際的
 * 慢 I/O 仍然發生在 {@code monitorTaskExecutor} 的執行緒上，等待本身很便宜。
 */
@Service
public class ApiMonitorRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiMonitorRunner.class);

    private static final String MDC_MONITOR_ID = "monitorId";

    /** §6.2／§8：NEW_ITEMS 模式一則訊息最多列這麼多筆，其餘寫「還有 N 筆」。 */
    private static final int MAX_ITEMS_PER_MESSAGE = 20;

    private final OutboundUrlGuard guard;
    private final ApiFetcher fetcher;
    private final SiteSessionService siteSessionService;
    private final SiteLoginService siteLoginService;
    private final ChangeDetector changeDetector;
    private final MessageTemplate messageTemplate;
    private final RequestTemplate requestTemplate;
    private final ComputedFieldEvaluator computedFieldEvaluator;
    private final ApiMonitorStore store;
    private final MonitorProperties properties;
    private final Executor monitorTaskExecutor;
    private final Clock clock;

    public ApiMonitorRunner(OutboundUrlGuard guard,
                            ApiFetcher fetcher,
                            SiteSessionService siteSessionService,
                            SiteLoginService siteLoginService,
                            ChangeDetector changeDetector,
                            MessageTemplate messageTemplate,
                            RequestTemplate requestTemplate,
                            ComputedFieldEvaluator computedFieldEvaluator,
                            ApiMonitorStore store,
                            MonitorProperties properties,
                            @Qualifier("monitorTaskExecutor") Executor monitorTaskExecutor,
                            Clock clock) {
        this.guard = guard;
        this.fetcher = fetcher;
        this.siteSessionService = siteSessionService;
        this.siteLoginService = siteLoginService;
        this.changeDetector = changeDetector;
        this.messageTemplate = messageTemplate;
        this.requestTemplate = requestTemplate;
        this.computedFieldEvaluator = computedFieldEvaluator;
        this.store = store;
        this.properties = properties;
        this.monitorTaskExecutor = monitorTaskExecutor;
        this.clock = clock;
    }

    /**
     * 跑一輪。
     *
     * @return 這一輪取到的監控數；0 代表沒有到期的工作
     */
    public int runOnce() {
        List<ClaimedMonitor> claimed = store.claim(properties.claimLimit());
        if (claimed.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<Void>> futures = claimed.stream()
                .map(monitor -> CompletableFuture.runAsync(() -> process(monitor), monitorTaskExecutor))
                .toList();
        awaitAll(futures, claimed.size());

        log.debug("監控輪詢一輪完成：claimed={}", claimed.size());
        return claimed.size();
    }

    /**
     * 逾時是保底，不是常態路徑——{@code claimLimit} 筆工作平行跑在獨立執行緒池上，
     * 正常情況下總耗時接近單筆最慢的那個，不是逐筆疊加。但執行緒池大小有限
     * （見 {@link MonitorAsyncConfig}），claim 到的筆數超過核心執行緒數時會排隊，
     * 所以逾時仍要抓「最壞情況全部排隊序列執行」的量級，避免在池子被塞滿時
     * 誤判成掛住。
     */
    private void awaitAll(List<CompletableFuture<Void>> futures, int claimedCount) {
        Duration perTaskTimeout = properties.connectTimeout().plus(properties.readTimeout());
        Duration overallTimeout = perTaskTimeout.multipliedBy(claimedCount).plusSeconds(10);
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(overallTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 不拋出去：個別工作仍在 monitorTaskExecutor 上跑，跑完會各自呼叫
            // store.recordSuccess/recordFailure 正常回寫，只是這一輪的呼叫端
            // （排程或測試）不會等到它們全部做完再拿回控制權。
            log.warn("這一輪監控處理逾時（{}ms），部分結果可能仍在背景執行緒池上跑",
                    overallTimeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // process() 內部已經自己吞掉所有例外（見該方法），理論上走不到這裡；
            // 留著是因為 CompletableFuture 的型別系統要求處理這個 checked 例外。
            log.error("監控處理出現未預期例外", e.getCause());
        }
    }

    /** 處理單一監控：抓取 + 回寫，任何例外都吞掉，不讓一筆監控的問題影響其他筆。 */
    private void process(ClaimedMonitor monitor) {
        MDC.put(MDC_MONITOR_ID, String.valueOf(monitor.id()));
        try {
            RunAttempt attempt = execute(monitor);
            switch (attempt) {
                case RunAttempt.Success success -> store.recordSuccess(monitor, success);
                case RunAttempt.Failure failure -> store.recordFailure(monitor, failure);
            }
        } catch (Exception e) {
            // 例如 recordSuccess/recordFailure 遇到資料庫瞬斷。租約到期後，
            // 下一輪 claim() 自然會重新取到這筆監控——outbox 式設計的意義就在這裡。
            log.error("監控處理失敗，將於租約到期後重試：monitorId={} name={}",
                    monitor.id(), monitor.name(), e);
        } finally {
            MDC.remove(MDC_MONITOR_ID);
        }
    }

    /**
     * 無交易：樣板替換 → guard 檢查 → 附加 cookie jar → HTTP 抓取 → 合併回寫
     * {@code Set-Cookie} → 解析 → 比對 → （有變更才）組訊息。
     *
     * <p>樣板替換（{@link RequestTemplate}）必須在這裡做，不能提前到
     * {@code ApiMonitorStore.claim()}——見 {@link ClaimedMonitor#url} 的說明。替換完成
     * 後的網址要<strong>重新</strong>過一次 {@link OutboundUrlGuard}：見
     * {@code Docs/plan/12-API監控易用性升級.md} §2.5，這是縱深防禦，目前的變數集合
     * 產不出 host，但這道檢查要在日後有人加新變數的那天之前就已經在這裡。
     *
     * <p><strong>凍結時間戳</strong>：{@link RequestTemplate.Session} 在這個方法最開始
     * 建立一次，之後計算欄位求值、URL、headers、body 全部共用同一份——見
     * {@code Docs/plan/13-監控計算欄位設計.md} §3。這裡直接拿它的 {@code instant()} 當
     * 這次執行的 {@code startedAt}，不再另外呼叫一次 {@code clock.instant()}：兩者本來
     * 就該是同一個「現在」，分開取只會製造不必要的、理論上可能跨秒的落差。
     *
     * <p>{@link SiteSessionService} 的兩次呼叫（附加、合併回寫）刻意夾在 guard 通過
     * <strong>之後</strong>、fetch 前後——見 §3.3。兩者都是各自獨立的短交易，不會讓
     * 這整段「無交易」的方法變得有交易；任何一邊的例外都只記警告、不阻斷抓取結果的
     * 正常回報，理由見下面對應的 try/catch 註解。
     */
    private RunAttempt execute(ClaimedMonitor monitor) {
        RequestTemplate.Session session = requestTemplate.newSession();
        Instant startedAt = session.instant();

        URI targetUri;
        Map<String, String> renderedHeaders;
        String renderedBody;
        try {
            Map<String, String> computedValues =
                    computedFieldEvaluator.evaluate(monitor.computedFields(), monitor.secrets(), session);
            String renderedUrl = requestTemplate.render(monitor.url(), session, computedValues);
            targetUri = URI.create(renderedUrl);
            renderedHeaders = requestTemplate.renderHeaders(monitor.headers(), session, computedValues);
            renderedBody = monitor.requestBody() == null
                    ? null : requestTemplate.render(monitor.requestBody(), session, computedValues);
        } catch (ApiException e) {
            // 樣板渲染或計算欄位求值失敗（未知佔位符、畸形 format pattern、計算欄位
            // 引用不存在的 secret／欄位）。這類錯誤理論上該在 AdminService 存檔當下就
            // 被擋下（doc 12 §2.5、doc 13 §5「拒絕存檔」），這裡是縱深防禦——萬一存檔時
            // 的驗證漏放過一筆，也不能讓例外掉進 process() 外層那個「不記錄、單純等租約
            // 到期重試」的粗糙 catch-all，而是要走跟 PARSE_ERROR 一樣完整的失敗記錄路徑，
            // 讓退避與失敗通知門檻正常生效。連 URI 都還沒解析出來，host 未知，用 4 參數
            // 版本（host=null）。
            return failure(startedAt, null, "TEMPLATE_ERROR", e.getMessage());
        } catch (IllegalArgumentException e) {
            // 替換後的字串不是合法 URI（例如佔位符替換出含空白的日期格式）。
            return failure(startedAt, null, "TEMPLATE_ERROR", "rendered URL is not a valid URI");
        }
        String requestHost = targetUri.getHost();

        try {
            guard.check(targetUri);
        } catch (OutboundUrlGuard.BlockedException e) {
            return failure(startedAt, null, "BLOCKED_URL", e.getMessage(), requestHost);
        }

        // 抓取前：依請求 host 找 jar，比對通過才附上 Cookie header。見
        // Docs/plan/12-API監控易用性升級.md §3.3。decrypt/DB 若意外失敗，寧可當成
        // 「這次沒有登入狀態可用」繼續往下打，也不要讓一筆壞掉的 jar 拖垮整個監控。
        Map<String, String> headersWithCookies;
        try {
            headersWithCookies = siteSessionService.attachCookies(requestHost, renderedHeaders);
        } catch (RuntimeException e) {
            log.warn("附加 cookie jar 失敗，本輪不使用既有登入狀態：monitorId={} host={}",
                    monitor.id(), requestHost, e);
            headersWithCookies = renderedHeaders;
        }

        // 站台登入（W16）：在真正要送出的這一刻才換 token，不在 claim() 時先換——
        // 見 ClaimedMonitor#loginId。
        //
        // 與 cookie jar 相反，這裡的失敗<strong>不可</strong>吞掉繼續打：沒有 token 的
        // 請求一定會拿到 401，那個 401 會被當成「回應變了」而發出一則莫名其妙的通知，
        // 或被記成 PARSE_ERROR。直接記一次 LOGIN_ERROR，讓失敗通知說出真正的原因。
        Map<String, String> headersWithLogin = headersWithCookies;
        if (monitor.loginId() != null) {
            try {
                ResolvedLoginHeader loginHeader = siteLoginService.resolve(monitor.loginId());
                headersWithLogin = new LinkedHashMap<>(headersWithCookies);
                headersWithLogin.put(loginHeader.name(), loginHeader.value());
            } catch (CognitoAuthException e) {
                // e.getMessage() 只含 Cognito 的錯誤型別與說明，不含帳密或 token
                // ——見 CognitoAuthException 類別註解。
                return failure(startedAt, null, "LOGIN_ERROR", e.getMessage(), requestHost);
            }
        }

        FetchResult fetchResult = fetcher.fetch(new ApiFetcher.FetchRequest(
                targetUri, monitor.method(), renderedBody, headersWithLogin));

        // 回應後：不論成功或失敗都嘗試合併 Set-Cookie 回 jar（例如 401 也可能夾帶新
        // CSRF token），理由見 FetchResult 類別註解。同樣不能讓這一步的例外蓋掉真正
        // 的抓取結果。
        try {
            siteSessionService.mergeSetCookies(requestHost, setCookieHeadersOf(fetchResult));
        } catch (RuntimeException e) {
            log.warn("合併 Set-Cookie 回 jar 失敗：monitorId={} host={}", monitor.id(), requestHost, e);
        }

        if (fetchResult instanceof FetchResult.Failure fetchFailure) {
            return failure(startedAt, fetchFailure.httpStatus(),
                    fetchFailure.reason().name(), fetchFailure.detail(), requestHost);
        }
        FetchResult.Success success = (FetchResult.Success) fetchResult;

        ChangeResult changeResult;
        try {
            changeResult = detect(monitor, success.body());
        } catch (ApiException e) {
            // 刻意不用 e.getMessage()——JsonExtractor 包的 Jackson 解析例外訊息
            // 常常夾帶原始 JSON 片段，那就是目標 API 的回應內容，直接寫進
            // api_monitor_run.error_message 會違反「不得含回應內容全文」的規則
            // （見該欄位的 migration 註解）。這裡只留分類與長度。
            return failure(startedAt, success.httpStatus(), "PARSE_ERROR",
                    "body length=" + success.body().length(), requestHost);
        }

        String renderedMessage = changeResult instanceof ChangeResult.Changed changed
                ? buildMessage(monitor, changed)
                : null;

        return new RunAttempt.Success(
                startedAt, durationSince(startedAt), success.httpStatus(), changeResult, renderedMessage);
    }

    private static List<String> setCookieHeadersOf(FetchResult result) {
        return switch (result) {
            case FetchResult.Success success -> success.setCookieHeaders();
            case FetchResult.Failure failure -> failure.setCookieHeaders();
        };
    }

    private ChangeResult detect(ClaimedMonitor monitor, String body) {
        if (monitor.compareMode() == CompareMode.NEW_ITEMS) {
            // 決策：{{item.NAME}} 沿用監控的 extract_rules 欄位，此模式下相對於
            // 每個陣列元素解讀（見 ClaimedMonitor 類別註解、ChangeDetector.detectNewItems）。
            return changeDetector.detectNewItems(
                    body, monitor.itemPointer(), monitor.itemKeyPointer(),
                    monitor.extractRules(), monitor.seenKeys(), monitor.firstRun());
        }
        return changeDetector.detectByFingerprint(
                monitor.compareMode(), body, monitor.extractRules(),
                monitor.lastFingerprint(), monitor.lastState());
    }

    /** 只有 {@link ChangeResult.Changed} 才會被呼叫——{@code Unchanged} 從不需要渲染訊息。 */
    private String buildMessage(ClaimedMonitor monitor, ChangeResult.Changed changed) {
        if (monitor.compareMode() == CompareMode.NEW_ITEMS) {
            return buildNewItemsMessage(monitor, changed.newItems());
        }
        return messageTemplate.render(monitor.messageTemplate(), new MessageTemplate.RenderContext(
                monitor.name(), changed.currentValues(), changed.previousValues(), Map.of()));
    }

    /**
     * NEW_ITEMS 模式的多項目組裝：每個新項目各呼叫一次 {@code render()}
     * （{@code itemValues = item.fields()}），最多列 {@value #MAX_ITEMS_PER_MESSAGE} 筆，
     * 其餘寫「還有 N 筆」——{@link MessageTemplate} 本身只管單次渲染，這段組裝邏輯
     * 是呼叫端（這裡）的職責，見該類別的類別註解。
     */
    private String buildNewItemsMessage(ClaimedMonitor monitor, List<ChangeResult.NewItem> newItems) {
        int included = Math.min(newItems.size(), MAX_ITEMS_PER_MESSAGE);
        List<String> lines = new ArrayList<>(included);
        for (int i = 0; i < included; i++) {
            ChangeResult.NewItem item = newItems.get(i);
            lines.add(messageTemplate.render(monitor.messageTemplate(),
                    new MessageTemplate.RenderContext(monitor.name(), Map.of(), Map.of(), item.fields())));
        }

        StringBuilder text = new StringBuilder(String.join("\n", lines));
        int remaining = newItems.size() - included;
        if (remaining > 0) {
            text.append("\n還有 ").append(remaining).append(" 筆");
        }

        // MessageTemplate.render() 每次呼叫各自截到 4500 字，但這裡把最多 20 次
        // 渲染結果接起來，總長度仍可能超過 LINE 的 5000 字硬上限
        // （MessageAssembler.simpleText 會因此丟 ApiException，讓這次通知整個送不出去，
        // 且無法再靠冷卻或每日上限那套機制自然恢復——因為問題不是「太頻繁」而是
        // 「太長」，下一輪重算出來的還是同樣長）。這裡保底再截一次，把「要不要通知」
        // 的決定權留給防洗版邏輯，不讓內容長度成為永遠卡住的原因。
        String joined = text.toString();
        return joined.length() > LineLimits.MAX_TEXT_LENGTH
                ? joined.substring(0, LineLimits.MAX_TEXT_LENGTH)
                : joined;
    }

    private RunAttempt.Failure failure(Instant startedAt, Integer httpStatus, String classification, String detail) {
        return new RunAttempt.Failure(startedAt, durationSince(startedAt), classification, detail, httpStatus);
    }

    private RunAttempt.Failure failure(Instant startedAt, Integer httpStatus, String classification, String detail,
                                       String host) {
        return new RunAttempt.Failure(startedAt, durationSince(startedAt), classification, detail, httpStatus, host);
    }

    private int durationSince(Instant startedAt) {
        return (int) Duration.between(startedAt, clock.instant()).toMillis();
    }
}
