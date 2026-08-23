package com.jason.notifyline.monitor;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientStatus;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.monitor.domain.ApiMonitor;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.domain.ApiMonitorRun;
import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.monitor.domain.RunOutcome;
import com.jason.notifyline.monitor.domain.SeenItem;
import com.jason.notifyline.monitor.domain.SeenItemRepository;
import com.jason.notifyline.monitor.parse.ChangeResult;
import com.jason.notifyline.monitor.parse.MessageTemplate;
import com.jason.notifyline.notification.NotificationService;
import com.jason.notifyline.notification.api.NotificationAccepted;
import com.jason.notifyline.notification.api.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 監控輪詢中所有<strong>需要交易</strong>的資料庫動作。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §7、§8。
 *
 * <p>與 {@link ApiMonitorRunner} 分成兩個 bean 不是為了整潔，是<strong>必要的</strong>：
 * {@code @Transactional} 靠 Spring 的代理生效，同一個 bean 內部呼叫自己的方法不會經過
 * 代理，交易註解會安靜地失效——抄 {@code DeliveryStore} 類別註解的同一個警告。
 *
 * <p>三個公開方法對應 §7 的三段交易邊界：{@link #claim} 取件上租約、
 * {@link #recordSuccess} / {@link #recordFailure} 回寫結果。中間的 HTTP 呼叫
 * （{@link ApiMonitorRunner}）完全不在這個類別裡，也完全沒有交易。
 *
 * <h2>防洗版的兩條鐵律（§8）</h2>
 * <ol>
 *   <li>冷卻中或已達每日上限而<strong>跳過通知</strong>時，{@code last_fingerprint} /
 *       {@code last_state} / {@code seen_item} 一律<strong>不更新</strong>——這次的
 *       變更仍然「待處理」，等冷卻結束或隔天上限重置後會再被偵測到並補發。
 *       更新了 fingerprint 卻沒發通知，等於把這次變更悄悄吃掉，永遠不會有人知道。</li>
 *   <li>{@code notified_count} / {@code notified_day} / {@code last_notified_at} 只在
 *       「變更」通知（受冷卻與每日上限管制的那一種）成功送出時才更新。失敗通知與
 *       恢復通知走 {@code failure_notified} 這條獨立的「每次故障最多一則」規則，
 *       不佔用、也不受變更通知的每日上限影響——兩者是不同種類的洗版風險，見
 *       {@code RunOutcome.SKIPPED} 的類別註解。</li>
 * </ol>
 */
@Service
public class ApiMonitorStore {

    private static final Logger log = LoggerFactory.getLogger(ApiMonitorStore.class);

    /**
     * {@code notified_day} 的換日依據。跟 {@link MessageTemplate} 的 {@code {{now}}}
     * 用同一個時區，理由相同：這是給台灣的單人／小團隊系統用的，「今天」該是
     * 台灣時間的今天，不是 UTC 的今天。
     */
    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    private static final String FAILURE_TEMPLATE =
            "⚠️ 監控「{{monitor.name}}」連續失敗，將持續重試並在恢復時通知。（{{now}}）";
    private static final String RECOVERY_TEMPLATE =
            "✅ 監控「{{monitor.name}}」已恢復正常。（{{now}}）";

    /** {@code api_monitor_run.error_message} 的防禦性上限，見類別註解與 migration 的欄位註解。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private static final String HEADERS_AAD_PREFIX = "monitor:";

    private final ApiMonitorRepository monitors;
    private final ApiMonitorRunRepository runs;
    private final SeenItemRepository seenItems;
    private final ClientRepository clients;
    private final NotificationService notificationService;
    private final SecretCipher secretCipher;
    private final MessageTemplate messageTemplate;
    private final MonitorProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiMonitorStore(ApiMonitorRepository monitors,
                           ApiMonitorRunRepository runs,
                           SeenItemRepository seenItems,
                           ClientRepository clients,
                           NotificationService notificationService,
                           SecretCipher secretCipher,
                           MessageTemplate messageTemplate,
                           MonitorProperties properties,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.monitors = monitors;
        this.runs = runs;
        this.seenItems = seenItems;
        this.clients = clients;
        this.notificationService = notificationService;
        this.secretCipher = secretCipher;
        this.messageTemplate = messageTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // -------------------------------------------------------------- 取件

    /**
     * 取件並上租約。抄 {@code DeliveryStore.claim} 的做法：
     * {@code FOR UPDATE SKIP LOCKED}（{@code ApiMonitorRepository.lockDue}）管「兩個
     * 工作者同時取件」，租約管「這一輪還沒回寫、下一輪又取到同一筆」——兩者解決不同
     * 問題，缺一不可。
     */
    @Transactional
    public List<ClaimedMonitor> claim(int limit) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.lease());

        List<ApiMonitor> due = monitors.lockDue(now, Limit.of(limit));
        List<ClaimedMonitor> claimed = new ArrayList<>(due.size());

        for (ApiMonitor monitor : due) {
            // firstRun 必須在這裡、用 lease() 之前的 lastRunAt 判斷——lastRunAt
            // 只在 recordSuccess/recordFailure 才會被寫入，取件當下讀到的值就是
            // 「上一次完整跑完的時間」，不會被這一輪的租約動作影響。
            boolean firstRun = monitor.getLastRunAt() == null;
            monitor.lease(leaseUntil);

            claimed.add(new ClaimedMonitor(
                    monitor.getId(),
                    monitor.getName(),
                    URI.create(monitor.getUrl()),
                    monitor.getMethod(),
                    monitor.getRequestBody(),
                    decryptHeaders(monitor),
                    monitor.getCompareMode(),
                    parseExtractRules(monitor.getExtractRules()),
                    monitor.getItemPointer(),
                    monitor.getItemKeyPointer(),
                    monitor.getMessageTemplate(),
                    monitor.getLastFingerprint(),
                    parseState(monitor.getLastState()),
                    firstRun,
                    loadSeenKeys(monitor)));
        }
        return claimed;
    }

    private Map<String, String> decryptHeaders(ApiMonitor monitor) {
        byte[] ciphertext = monitor.getHeadersCiphertext();
        if (ciphertext == null) {
            return Map.of();
        }
        String plaintext = secretCipher.decrypt(
                ciphertext, monitor.getHeadersIv(), monitor.getHeadersKeyVersion(),
                HEADERS_AAD_PREFIX + monitor.getId());
        return parseHeaders(plaintext);
    }

    private Set<String> loadSeenKeys(ApiMonitor monitor) {
        if (monitor.getCompareMode() != CompareMode.NEW_ITEMS) {
            return Set.of();
        }
        // 這張表沒有「依 monitor 查全部」的衍生查詢方法可用（W3 不可修改
        // monitor/domain 底下的 repository），用 JpaRepository 內建的 findAllById
        // 需要知道 id，這裡索性直接掃全表用 stream 篩選——seen_item 的規模是
        // 「單一監控見過的項目數」，對單人／小團隊系統而言不會大到需要另外建查詢方法。
        return seenItems.findAll().stream()
                .filter(item -> item.getMonitorId().equals(monitor.getId()))
                .map(SeenItem::getItemKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    // -------------------------------------------------------------- 回寫：成功

    /**
     * 抓取與比對都成功的回寫。是否真的送出「變更」通知由這裡的防洗版判斷決定，
     * 是否補發「恢復」通知由這裡讀到的 {@code failure_notified} 舊值決定。
     */
    @Transactional
    public void recordSuccess(ClaimedMonitor snapshot, RunAttempt.Success attempt) {
        ApiMonitor monitor = monitors.findById(snapshot.id()).orElse(null);
        if (monitor == null) {
            // 執行期間監控被刪除（後台操作，Wave 4）。沒有列可以回寫，直接放棄這一輪。
            log.warn("回寫成功結果時找不到監控，可能已被刪除：monitorId={}", snapshot.id());
            return;
        }
        Instant now = clock.instant();
        boolean fingerprintMode = snapshot.compareMode() != CompareMode.NEW_ITEMS;
        ChangeResult changeResult = attempt.changeResult();

        RunOutcome outcome;
        UUID notificationId = null;

        if (changeResult instanceof ChangeResult.Changed changed) {
            notificationId = tryNotifyChange(monitor, changed, attempt.renderedMessage(), now);
            if (notificationId != null) {
                outcome = RunOutcome.CHANGED;
                if (fingerprintMode) {
                    monitor.applyFingerprint(changed.fingerprint(), serializeValues(changed.currentValues()));
                } else {
                    persistSeenItems(monitor.getId(), changed.newItems(), now);
                }
            } else {
                // 防洗版擋下或送出失敗：指紋與 seen_item 都不能動，見類別註解的第一條鐵律。
                outcome = RunOutcome.SKIPPED;
            }
        } else {
            outcome = RunOutcome.UNCHANGED;
            if (fingerprintMode) {
                monitor.applyFingerprint(changeResult.fingerprint(), serializeValues(changeResult.currentValues()));
            } else if (snapshot.firstRun()) {
                // NEW_ITEMS 首次執行：Unchanged.newItems() 是「本次看到的全部項目」，
                // 整批寫入當基準，不通知——見 ChangeDetector.detectNewItems 的說明。
                persistSeenItems(monitor.getId(), changeResult.newItems(), now);
            }
        }

        boolean wasFailureNotified = monitor.isFailureNotified();
        int effectiveInterval = effectiveIntervalSeconds(monitor);
        monitor.recordSuccess(now, effectiveInterval);

        UUID recoveryNotificationId = null;
        if (wasFailureNotified) {
            recoveryNotificationId = sendRecoveryNotification(monitor, now);
        }

        // 一列 api_monitor_run 只有一個 notification_id 欄位，但同一次成功執行理論上
        // 可能同時符合「變更通知」與「恢復通知」兩種條件（例如目標故障了幾輪、
        // 復原時剛好抓到的內容也變了）。變更通知優先——它是這次執行「抓到什麼」的
        // 直接結果，恢復通知只是附帶的故障狀態通報；CHANGED 時 notificationId 恆非
        // null，兩者不會真的互相覆蓋掉彼此的資訊（恢復通知本身仍然送出去了，
        // 只是這一列 run 紀錄不是它的主要歸屬）。
        UUID runNotificationId = outcome == RunOutcome.CHANGED ? notificationId : recoveryNotificationId;

        runs.save(new ApiMonitorRun(monitor.getId(), attempt.startedAt(), attempt.durationMs(),
                outcome, attempt.httpStatus(), null, runNotificationId));
    }

    /**
     * 變更通知的防洗版判斷 + 實際送出。
     *
     * @return 送出成功時的 notification id；{@code null} 代表被冷卻／每日上限擋下，
     *         或 client 不可用，或 {@code submit()} 本身失敗（例如 client 每日配額）——
     *         三種情況呼叫端都要當成「這次沒送出去」處理，不可用 {@code null} 以外的
     *         值誤判成功（{@code NotificationAccepted.notificationId()} 恆非
     *         {@code null}，所以這裡用 {@code null} 代表「沒送」不會與真正送出的
     *         結果混淆）
     */
    private UUID tryNotifyChange(ApiMonitor monitor,
                                 ChangeResult.Changed changed,
                                 String renderedMessage,
                                 Instant now) {
        // 換日判斷要在冷卻/上限檢查之前做，讓 hasReachedDailyCap() 反映的是「今天」
        // 的計數，而不是上一個有通知的日子留下的舊值。
        monitor.rolloverNotifiedDayIfNeeded(LocalDate.ofInstant(now, TAIPEI_ZONE));

        if (monitor.isCooldownActive(now)) {
            log.info("冷卻中，跳過變更通知：monitorId={} name={}", monitor.getId(), monitor.getName());
            return null;
        }
        if (monitor.hasReachedDailyCap()) {
            log.info("已達每日通知上限，跳過變更通知：monitorId={} name={} cap={}",
                    monitor.getId(), monitor.getName(), monitor.getMaxNotificationsPerDay());
            return null;
        }

        Optional<Client> client = activeClient(monitor.getClientId(), monitor.getId());
        if (client.isEmpty()) {
            return null;
        }

        String text = blankToPlaceholder(renderedMessage);
        UUID notificationId;
        try {
            notificationId = submit(client.get(), text, "monitor-" + monitor.getId());
        } catch (ApiException e) {
            // 例如 client 每日配額用罄。這次變更視同被防洗版擋下——指紋不更新，
            // 下一輪偵測到的仍是同一個「待通知」的變更，額度恢復後會補發。
            log.warn("變更通知送出失敗（{}），本輪視為跳過：monitorId={} message={}",
                    e.getCode(), monitor.getId(), e.getMessage());
            return null;
        }
        monitor.markNotified(now);
        return notificationId;
    }

    /** @return 送出成功的恢復通知 id；client 不可用或 {@code submit()} 失敗時為 {@code null}。 */
    private UUID sendRecoveryNotification(ApiMonitor monitor, Instant now) {
        Optional<Client> client = activeClient(monitor.getClientId(), monitor.getId());
        if (client.isEmpty()) {
            return null;
        }
        String text = messageTemplate.render(RECOVERY_TEMPLATE, MessageTemplate.RenderContext.of(monitor.getName()));
        try {
            return submit(client.get(), text, "monitor-recovery-" + monitor.getId());
        } catch (ApiException e) {
            log.warn("恢復通知送出失敗：monitorId={} code={}", monitor.getId(), e.getCode());
            return null;
        }
    }

    private void persistSeenItems(Long monitorId, List<ChangeResult.NewItem> items, Instant now) {
        if (items.isEmpty()) {
            return;
        }
        List<SeenItem> rows = items.stream()
                .map(item -> new SeenItem(monitorId, item.itemKey(), now))
                .toList();
        seenItems.saveAll(rows);
    }

    // -------------------------------------------------------------- 回寫：失敗

    /**
     * 抓取或解析失敗的回寫：退避排程、累加失敗次數，達門檻補發一則失敗通知
     * （每次故障最多一則，見 {@code ApiMonitor.shouldNotifyFailure}）。
     */
    @Transactional
    public void recordFailure(ClaimedMonitor snapshot, RunAttempt.Failure attempt) {
        ApiMonitor monitor = monitors.findById(snapshot.id()).orElse(null);
        if (monitor == null) {
            log.warn("回寫失敗結果時找不到監控，可能已被刪除：monitorId={}", snapshot.id());
            return;
        }
        Instant now = clock.instant();
        int effectiveInterval = effectiveIntervalSeconds(monitor);
        monitor.recordFailure(now, effectiveInterval);

        UUID failureNotificationId = null;
        if (monitor.isNotifyOnFailure() && monitor.shouldNotifyFailure(properties.failureNotifyThreshold())) {
            failureNotificationId = activeClient(monitor.getClientId(), monitor.getId())
                    .map(client -> {
                        String text = messageTemplate.render(
                                FAILURE_TEMPLATE, MessageTemplate.RenderContext.of(monitor.getName()));
                        try {
                            return submit(client, text, "monitor-failure-" + monitor.getId());
                        } catch (ApiException e) {
                            log.warn("失敗通知送出失敗：monitorId={} code={}", monitor.getId(), e.getCode());
                            return null;
                        }
                    })
                    .orElse(null);
            if (failureNotificationId != null) {
                monitor.markFailureNotified();
            }
        }

        String errorMessage = truncate(
                attempt.classification()
                        + (isBlank(attempt.detail()) ? "" : ": " + attempt.detail()),
                MAX_ERROR_MESSAGE_LENGTH);

        // notification_id：這一列本身就是「這次故障」的執行紀錄，達門檻送出的失敗通知
        // 就記在它自己這一列——不像 W3 原本以為的那樣要等到另一種「事件」才有值。
        // 見 migration 對這個欄位更新後的註解。
        runs.save(new ApiMonitorRun(monitor.getId(), attempt.startedAt(), attempt.durationMs(),
                RunOutcome.FAILED, attempt.httpStatus(), errorMessage, failureNotificationId));
    }

    // -------------------------------------------------------------- 共用

    /**
     * 服務層生效的排程間隔下限：{@code max(監控自己的 interval_seconds,
     * app.monitor.min-interval)}。監控建立當時或許滿足過更寬鬆的下限，但管理員事後
     * 把 {@code app.monitor.min-interval} 調高是合理的操作，排程應該立刻反映新下限，
     * 不必等每一筆監控被重新編輯過一次。
     */
    private int effectiveIntervalSeconds(ApiMonitor monitor) {
        return Math.max(monitor.getIntervalSeconds(), (int) properties.minInterval().toSeconds());
    }

    private Optional<Client> activeClient(Long clientId, Long monitorId) {
        Optional<Client> client = clients.findById(clientId);
        if (client.isEmpty() || client.get().getStatus() != ClientStatus.ACTIVE) {
            log.warn("監控綁定的 client 不存在或非 ACTIVE，略過通知：monitorId={} clientId={}",
                    monitorId, clientId);
            return Optional.empty();
        }
        return client;
    }

    /**
     * 走 {@code NotificationService.submit()}——這是整份設計裡最不可退讓的一條規則：
     * 監控功能絕不能繞過它自己發訊息，否則 scope 檢查、預設對象、client 每日配額、
     * 連結白名單（{@code UriHostValidator}）全部失效，而連結白名單正是擋住「目標
     * API 回應裡夾帶釣魚連結」這條攻擊路徑的唯一防線。
     */
    private UUID submit(Client client, String text, String requestId) {
        ClientPrincipal principal = ClientPrincipal.from(client);
        NotificationRequest request = new NotificationRequest(
                null, // target = null：套用 client 自己的預設通知對象，誰收全由 client 決定
                new NotificationRequest.Message(null, text),
                null,
                null);
        byte[] rawBody = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
        NotificationAccepted accepted = notificationService.submit(
                principal, request, rawBody, null, requestId + "-" + UUID.randomUUID());
        return accepted.notificationId();
    }

    private static String blankToPlaceholder(String text) {
        return isBlank(text) ? "（無內容）" : text;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private List<ExtractRule> parseExtractRules(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        ExtractRule[] rules = objectMapper.readValue(json, ExtractRule[].class);
        return List.of(rules);
    }

    /**
     * {@code last_state} 的格式是 {@code {"name": "value", ...}}，值一律是字串
     * （見 migration 欄位註解）。用 {@code Map.class} 讀成原始型別再逐一轉字串，
     * 避免對一個「儲存格式已經是字串」的欄位還要多帶一個 {@code TypeReference}。
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseState(String json) {
        if (isBlank(json)) {
            return Map.of();
        }
        Map<String, Object> raw = objectMapper.readValue(json, Map.class);
        Map<String, String> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> values.put(key, value == null ? null : String.valueOf(value)));
        return values;
    }

    /** 解密後的 header 明文格式：{@code {"Header-Name": "value", ...}}，理由同 {@link #parseState}。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(String json) {
        if (isBlank(json)) {
            return Map.of();
        }
        Map<String, Object> raw = objectMapper.readValue(json, Map.class);
        Map<String, String> headers = new LinkedHashMap<>();
        raw.forEach((key, value) -> headers.put(key, value == null ? "" : String.valueOf(value)));
        return headers;
    }

    private String serializeValues(Map<String, String> values) {
        return objectMapper.writeValueAsString(values);
    }
}
