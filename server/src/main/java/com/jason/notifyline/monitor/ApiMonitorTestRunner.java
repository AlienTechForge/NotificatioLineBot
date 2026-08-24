package com.jason.notifyline.monitor;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.monitor.fetch.ApiFetcher;
import com.jason.notifyline.monitor.fetch.FetchResult;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.monitor.parse.ChangeDetector;
import com.jason.notifyline.monitor.parse.ChangeResult;
import com.jason.notifyline.monitor.parse.MessageTemplate;
import com.jason.notifyline.monitor.session.SiteSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 後台「立即測試」（{@code POST /admin/api/monitors/test}）背後的試跑邏輯。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §10、§11：「沒有這個，設 JsonPointer 等於盲猜」。
 *
 * <h2>必須走跟 {@link ApiMonitorRunner} 完全相同的 guard + fetch 路徑</h2>
 *
 * <p>這是<strong>安全關鍵</strong>：試跑的網址是管理者當下在表單裡打的，可能還沒存檔、
 * 也可能是編輯中還沒送出的內容——如果這裡跳過 {@link OutboundUrlGuard} 或用不同的
 * fetch 邏輯，一個已通過 session 認證的管理端點就變成一個完整的 SSRF 入口：
 * 比沒有 guard 更糟，因為它看起來像有防護。所以這裡的建構子直接注入跟
 * {@link ApiMonitorRunner} 同一顆 {@link OutboundUrlGuard} / {@link ApiFetcher} bean，
 * 不自己另外組一份 HttpClient。
 *
 * <h2>用「首次執行」語意重用 {@link ChangeDetector}，不重寫抽值邏輯</h2>
 *
 * <p>試跑沒有「上一次」可以比較——{@code previousFingerprint = null} 對
 * {@code detectByFingerprint} 來說剛好就是「首次執行」，回傳的
 * {@link ChangeResult.Unchanged} 帶著這次抽出的 {@code currentValues}，正是試跑要秀給
 * 使用者看的東西。{@code NEW_ITEMS} 模式同理：{@code seenKeys = Set.of()} +
 * {@code firstRun = true} 讓 {@code detectNewItems} 把陣列裡的每個元素都當成「新項目」
 * 回傳，用來預覽 {@code item_pointer} / {@code item_key_pointer} 抓不抓得到東西，而不是
 * 真的要拿去跟 {@code seen_item} 表比對。兩條路徑都不會寫入任何資料庫狀態，也不會呼叫
 * {@code ChangeDetector} 以外、需要 repository 的任何東西。
 *
 * <h2>絕不送出任何通知</h2>
 *
 * <p>這裡完全沒有注入 {@code NotificationService} 或 {@code ApiMonitorStore}——不是刻意
 * 「不呼叫」，而是連可以呼叫的依賴都不存在，杜絕日後不小心加一行 {@code submit(...)}
 * 就讓試跑變成真的發送。
 *
 * <h2>會附加 cookie jar，但絕不合併回寫</h2>
 *
 * <p>立即測試存在的唯一理由是預覽排程輪詢（{@link ApiMonitorRunner}）實際會打出去的
 * 請求——如果這裡不附加 {@link SiteSessionService} 存的登入狀態，一個對排程來說會成功
 * 的監控在試跑時會回 401，使用者會誤以為設定壞了。所以這裡注入同一顆
 * {@link SiteSessionService}，抓取前呼叫 {@link SiteSessionService#attachCookies}，
 * 跟 {@link ApiMonitorRunner#execute} 完全同一段邏輯。
 *
 * <p>但<strong>只呼叫附加，絕不呼叫 {@link SiteSessionService#mergeSetCookies}</strong>：
 * 試跑是「預覽」，不是「執行」，不能有任何副作用——道理跟上面「絕不送出任何通知」
 * 完全一樣，只是這次要防的不是發送，而是「點一次立即測試就把排程輪詢依賴的登入
 * session 換掉」。若目標端點在試跑當下剛好回一組新的 {@code Set-Cookie}（例如輪換
 * CSRF token），這裡就讓它被丟棄，不去動 jar；下一次排程輪詢仍然用同一份儲存的
 * 登入狀態，行為可預期。
 *
 * <h2>成功時回傳原始 body（截斷至 {@value #MAX_TEST_BODY_BYTES} 位元組）</h2>
 *
 * <p>見 {@code Docs/plan/12-API監控易用性升級.md} §4.2：後台要把試跑抓到的回應渲染成
 * 可展開的樹，點節點插入 JsonPointer，不再需要使用者手打一個「種子」pointer 才能展開。
 * 這代表 {@code Docs/plan/11-API監控輪詢設計.md} §10「回傳的 DTO 絕不可包含…」對持久化
 * 執行紀錄的限制，在這個完全不持久化、帶 {@code Cache-Control: no-store} 的端點上被
 * 有意識地放寬——見 {@link MonitorTestOutcome} 類別註解的完整理由。{@link #boundBody}
 * 只影響「要不要把整包 body 送回瀏覽器」這一步，{@code values} / {@code items} /
 * {@code renderedMessage} 一律用完整、未截斷的 {@code success.body()} 算出來。
 */
@Service
public class ApiMonitorTestRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiMonitorTestRunner.class);

    /** NEW_ITEMS 模式一則訊息最多列這麼多筆，其餘寫「還有 N 筆」——規則同 {@code ApiMonitorRunner}。 */
    private static final int MAX_ITEMS_PER_MESSAGE = 20;

    /**
     * 回傳給瀏覽器的原始 body 上限：256 KB。{@link ApiFetcher} 的
     * {@code app.monitor.max-body-bytes}（預設 1 MB）保護的是「抓取」這一步，避免無限或
     * 超大回應把伺服器記憶體吃光；這裡是<strong>第二層、更小的上限</strong>，收斂的是
     * 「要不要把整包 body 一起送回瀏覽器讓它畫成可展開的樹」——256 KB 已經能涵蓋絕大多數
     * 真實世界的 JSON API 回應，沒必要把抓取上限原封不動地丟給前端。
     */
    private static final int MAX_TEST_BODY_BYTES = 256 * 1024;

    private final OutboundUrlGuard guard;
    private final ApiFetcher fetcher;
    private final SiteSessionService siteSessionService;
    private final ChangeDetector changeDetector;
    private final MessageTemplate messageTemplate;

    public ApiMonitorTestRunner(OutboundUrlGuard guard,
                                ApiFetcher fetcher,
                                SiteSessionService siteSessionService,
                                ChangeDetector changeDetector,
                                MessageTemplate messageTemplate) {
        this.guard = guard;
        this.fetcher = fetcher;
        this.siteSessionService = siteSessionService;
        this.changeDetector = changeDetector;
        this.messageTemplate = messageTemplate;
    }

    public MonitorTestOutcome run(TestConfig config) {
        try {
            guard.check(config.uri());
        } catch (OutboundUrlGuard.BlockedException e) {
            return new MonitorTestOutcome.Blocked(e.getMessage());
        }

        // 抓取前：附加 jar cookie，理由見類別註解「會附加 cookie jar，但絕不合併回寫」。
        // decrypt/DB 若意外失敗，寧可當成「這次沒有登入狀態可用」繼續往下打，也不要讓
        // 一筆壞掉的 jar 擋住整次試跑——跟 ApiMonitorRunner.execute 的同一段邏輯一致。
        Map<String, String> headersWithCookies;
        try {
            headersWithCookies = siteSessionService.attachCookies(config.uri().getHost(), config.headers());
        } catch (RuntimeException e) {
            log.warn("附加 cookie jar 失敗，本次試跑不使用既有登入狀態：host={}", config.uri().getHost(), e);
            headersWithCookies = config.headers();
        }

        FetchResult fetchResult = fetcher.fetch(new ApiFetcher.FetchRequest(
                config.uri(), config.method(), config.requestBody(), headersWithCookies));
        if (fetchResult instanceof FetchResult.Failure failure) {
            return new MonitorTestOutcome.FetchFailed(
                    failure.reason().name(), failure.detail(), failure.httpStatus());
        }
        FetchResult.Success success = (FetchResult.Success) fetchResult;

        try {
            return config.compareMode() == CompareMode.NEW_ITEMS
                    ? previewNewItems(config, success)
                    : previewFingerprintMode(config, success);
        } catch (ApiException e) {
            // 理由同 ApiMonitorRunner.execute 的 PARSE_ERROR 分支：不可用 e.getMessage()，
            // Jackson 的解析例外訊息常夾帶原始 JSON 片段，那就是目標 API 的回應內容。
            return new MonitorTestOutcome.ParseFailed("body length=" + success.body().length());
        }
    }

    private MonitorTestOutcome.Success previewFingerprintMode(TestConfig config, FetchResult.Success success) {
        ChangeResult result = changeDetector.detectByFingerprint(
                config.compareMode(), success.body(), config.extractRules(), null, null);
        String message = messageTemplate.render(config.messageTemplate(), new MessageTemplate.RenderContext(
                config.name(), result.currentValues(), Map.of(), Map.of()));
        BoundedBody body = boundBody(success.body());
        return new MonitorTestOutcome.Success(success.httpStatus(), result.currentValues(), List.of(), message,
                body.text(), body.truncated(), body.originalLength());
    }

    private MonitorTestOutcome.Success previewNewItems(TestConfig config, FetchResult.Success success) {
        BoundedBody body = boundBody(success.body());

        // itemPointer / itemKeyPointer 還沒設定：這是「點選欄位」流程的第一步——使用者
        // 先按立即測試拿到 body，前端才有東西可以畫成樹，再從樹裡點節點回填這兩個
        // pointer。這時還沒有「哪個是陣列、哪個是鍵」的資訊，不能呼叫
        // ChangeDetector.detectNewItems（它要求兩者都指向真的存在的位置，否則會回
        // ParseFailed，讓使用者連 body 都看不到，等於卡死整個 picker 流程）。直接回傳
        // 空的項目預覽即可，body 已經夠前端把樹畫出來。
        if (isBlank(config.itemPointer()) || isBlank(config.itemKeyPointer())) {
            return new MonitorTestOutcome.Success(
                    success.httpStatus(), Map.of(), List.of(), "", body.text(), body.truncated(), body.originalLength());
        }

        ChangeResult result = changeDetector.detectNewItems(
                success.body(), config.itemPointer(), config.itemKeyPointer(),
                config.extractRules(), Set.of(), true);
        List<ChangeResult.NewItem> items = result.newItems();
        List<MonitorTestOutcome.ItemPreview> preview = items.stream()
                .map(item -> new MonitorTestOutcome.ItemPreview(item.itemKey(), item.fields()))
                .toList();
        return new MonitorTestOutcome.Success(
                success.httpStatus(), Map.of(), preview, buildNewItemsMessage(config, items),
                body.text(), body.truncated(), body.originalLength());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 把 {@code rawBody} 截到 {@link #MAX_TEST_BODY_BYTES} 位元組以內，供
     * {@link MonitorTestOutcome.Success#body()} 使用。截斷點以 UTF-8 位元組計算，可能切在
     * 多位元組字元中間——{@link String#String(byte[], int, int, java.nio.charset.Charset)}
     * 對畸形序列的預設處理是替換成 U+FFFD，不會拋例外；前端要靠 {@code truncated} 旗標
     * 明確提示使用者「這棵樹可能不完整」，而不是假裝截斷沒發生過。
     */
    private static BoundedBody boundBody(String rawBody) {
        byte[] bytes = rawBody.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_TEST_BODY_BYTES) {
            return new BoundedBody(rawBody, false, bytes.length);
        }
        String truncated = new String(bytes, 0, MAX_TEST_BODY_BYTES, StandardCharsets.UTF_8);
        return new BoundedBody(truncated, true, bytes.length);
    }

    /** {@link #boundBody} 的回傳形狀：截斷後的文字、是否被截斷、截斷前的原始位元組數。 */
    private record BoundedBody(String text, boolean truncated, int originalLength) {
    }

    /** 組裝規則抄 {@code ApiMonitorRunner.buildNewItemsMessage}：最多 20 筆，其餘寫「還有 N 筆」。 */
    private String buildNewItemsMessage(TestConfig config, List<ChangeResult.NewItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        int included = Math.min(items.size(), MAX_ITEMS_PER_MESSAGE);
        List<String> lines = new ArrayList<>(included);
        for (int i = 0; i < included; i++) {
            ChangeResult.NewItem item = items.get(i);
            lines.add(messageTemplate.render(config.messageTemplate(),
                    new MessageTemplate.RenderContext(config.name(), Map.of(), Map.of(), item.fields())));
        }
        StringBuilder text = new StringBuilder(String.join("\n", lines));
        int remaining = items.size() - included;
        if (remaining > 0) {
            text.append("\n還有 ").append(remaining).append(" 筆");
        }
        return text.toString();
    }

    /**
     * 試跑用的設定快照，形狀類似 {@link ClaimedMonitor} 但刻意是獨立型別——這份設定
     * 可能根本沒存過檔（{@code POST /monitors/test} 的整個重點），硬塞進
     * {@link ClaimedMonitor}（語意是「已從資料庫取件」）只會誤導讀者。
     *
     * @param name 供 {@code {{monitor.name}}} 使用；未命名的草稿也要能測試，呼叫端
     *             （{@code AdminService}）在名稱空白時代入一個佔位字串
     */
    public record TestConfig(
            String name,
            URI uri,
            String method,
            String requestBody,
            Map<String, String> headers,
            CompareMode compareMode,
            List<ExtractRule> extractRules,
            String itemPointer,
            String itemKeyPointer,
            String messageTemplate) {

        public TestConfig {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            extractRules = extractRules == null ? List.of() : List.copyOf(extractRules);
        }
    }
}
