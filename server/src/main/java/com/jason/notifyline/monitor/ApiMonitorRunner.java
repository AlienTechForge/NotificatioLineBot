package com.jason.notifyline.monitor;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.LineLimits;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.fetch.ApiFetcher;
import com.jason.notifyline.monitor.fetch.FetchResult;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.monitor.parse.ChangeDetector;
import com.jason.notifyline.monitor.parse.ChangeResult;
import com.jason.notifyline.monitor.parse.MessageTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final ChangeDetector changeDetector;
    private final MessageTemplate messageTemplate;
    private final ApiMonitorStore store;
    private final MonitorProperties properties;
    private final Executor monitorTaskExecutor;
    private final Clock clock;

    public ApiMonitorRunner(OutboundUrlGuard guard,
                            ApiFetcher fetcher,
                            ChangeDetector changeDetector,
                            MessageTemplate messageTemplate,
                            ApiMonitorStore store,
                            MonitorProperties properties,
                            @Qualifier("monitorTaskExecutor") Executor monitorTaskExecutor,
                            Clock clock) {
        this.guard = guard;
        this.fetcher = fetcher;
        this.changeDetector = changeDetector;
        this.messageTemplate = messageTemplate;
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

    /** 無交易：guard 檢查 → HTTP 抓取 → 解析 → 比對 → （有變更才）組訊息。 */
    private RunAttempt execute(ClaimedMonitor monitor) {
        Instant startedAt = clock.instant();

        try {
            guard.check(monitor.uri());
        } catch (OutboundUrlGuard.BlockedException e) {
            return failure(startedAt, null, "BLOCKED_URL", e.getMessage());
        }

        FetchResult fetchResult = fetcher.fetch(new ApiFetcher.FetchRequest(
                monitor.uri(), monitor.method(), monitor.requestBody(), monitor.headers()));

        if (fetchResult instanceof FetchResult.Failure fetchFailure) {
            return failure(startedAt, fetchFailure.httpStatus(),
                    fetchFailure.reason().name(), fetchFailure.detail());
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
                    "body length=" + success.body().length());
        }

        String renderedMessage = changeResult instanceof ChangeResult.Changed changed
                ? buildMessage(monitor, changed)
                : null;

        return new RunAttempt.Success(
                startedAt, durationSince(startedAt), success.httpStatus(), changeResult, renderedMessage);
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

    private int durationSince(Instant startedAt) {
        return (int) Duration.between(startedAt, clock.instant()).toMillis();
    }
}
