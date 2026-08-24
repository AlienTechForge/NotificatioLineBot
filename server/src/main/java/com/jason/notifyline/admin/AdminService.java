package com.jason.notifyline.admin;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.ClientStatus;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.lineuser.LineUserService;
import com.jason.notifyline.lineuser.LineUserStatus;
import com.jason.notifyline.monitor.ApiMonitorTestRunner;
import com.jason.notifyline.monitor.MonitorProperties;
import com.jason.notifyline.monitor.domain.ApiMonitor;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.domain.ApiMonitorRun;
import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.monitor.importer.ImportedRequest;
import com.jason.notifyline.monitor.importer.RequestImporter;
import com.jason.notifyline.monitor.request.RequestTemplate;
import com.jason.notifyline.notification.NotificationService;
import com.jason.notifyline.notification.api.NotificationAccepted;
import com.jason.notifyline.notification.api.NotificationDetail;
import com.jason.notifyline.notification.api.NotificationRequest;
import com.jason.notifyline.notification.dispatch.DeliveryStore;
import com.jason.notifyline.notification.domain.Notification;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import com.jason.notifyline.notification.domain.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理台的所有操作。集中在一個 service，因為它們共用同一組資料庫依賴，
 * 且都在 session 認證之後（見 {@link AdminSecurityConfig}）。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** 近期發送列表的上限。夠看趨勢，又不會一次拉爆。 */
    private static final int RECENT_LIMIT = 100;

    /** 儀表板的統計窗口。 */
    private static final Duration STATS_WINDOW = Duration.ofHours(24);

    /** 排程頁列出的上限。 */
    private static final int SCHEDULED_LIMIT = 200;

    /**
     * 排程時間的下限緩衝。小於這個值就直接當「立即發送」處理更誠實 ——
     * 使用者選了「30 秒後」跟選「現在」實務上沒有差別，卻要多一套排程 UI 狀態。
     */
    private static final Duration MIN_SCHEDULE_LEAD = Duration.ofSeconds(30);

    /** 排程時間的上限，抓明顯打錯的年份（例如選單誤觸成 2099）。 */
    private static final Duration MAX_SCHEDULE_LEAD = Duration.ofDays(366);

    /** 監控最近執行紀錄列表的上限。見 {@code Docs/plan/11-API監控輪詢設計.md} §10。 */
    private static final int MONITOR_RUNS_LIMIT = 50;

    /** {@code extract_rules[].name}，模板用 {@code {{value.NAME}}} 引用，規則見 migration 欄位註解。 */
    private static final Pattern EXTRACT_RULE_NAME = Pattern.compile("[A-Za-z0-9_]{1,32}");

    /** header 密文的 AAD 前綴，需跟 {@code ApiMonitorStore} 用同一個值才解得開。 */
    private static final String HEADERS_AAD_PREFIX = "monitor:";

    /** 匯入端點的輸入長度上限。見 {@code Docs/plan/12-API監控易用性升級.md} §2.6。 */
    private static final int IMPORT_MAX_BYTES = 64 * 1024;

    private final ClientRepository clients;
    private final ClientService clientService;
    private final LineUserRepository lineUsers;
    private final LineUserService lineUserService;
    private final NotificationService notificationService;
    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final DeliveryStore deliveryStore;
    private final LineQuotaClient lineQuotaClient;
    private final ApiMonitorRepository monitors;
    private final ApiMonitorRunRepository monitorRunRepository;
    private final ApiMonitorTestRunner monitorTestRunner;
    private final SecretCipher secretCipher;
    private final MonitorProperties monitorProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final OutboundUrlGuard outboundUrlGuard;
    private final RequestImporter requestImporter;
    private final RequestTemplate requestTemplate;

    public AdminService(ClientRepository clients,
                        ClientService clientService,
                        LineUserRepository lineUsers,
                        LineUserService lineUserService,
                        NotificationService notificationService,
                        NotificationRepository notifications,
                        NotificationDeliveryRepository deliveries,
                        DeliveryStore deliveryStore,
                        LineQuotaClient lineQuotaClient,
                        ApiMonitorRepository monitors,
                        ApiMonitorRunRepository monitorRunRepository,
                        ApiMonitorTestRunner monitorTestRunner,
                        SecretCipher secretCipher,
                        MonitorProperties monitorProperties,
                        ObjectMapper objectMapper,
                        Clock clock,
                        OutboundUrlGuard outboundUrlGuard,
                        RequestImporter requestImporter,
                        RequestTemplate requestTemplate) {
        this.clients = clients;
        this.clientService = clientService;
        this.lineUsers = lineUsers;
        this.lineUserService = lineUserService;
        this.notificationService = notificationService;
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.deliveryStore = deliveryStore;
        this.lineQuotaClient = lineQuotaClient;
        this.monitors = monitors;
        this.monitorRunRepository = monitorRunRepository;
        this.monitorTestRunner = monitorTestRunner;
        this.secretCipher = secretCipher;
        this.monitorProperties = monitorProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.outboundUrlGuard = outboundUrlGuard;
        this.requestImporter = requestImporter;
        this.requestTemplate = requestTemplate;
    }

    // ------------------------------------------------------------ 儀表板

    @Transactional(readOnly = true)
    public AdminDto.Stats stats() {
        Instant since = clock.instant().minus(STATS_WINDOW);
        List<Client> all = clients.findAll();
        long active = all.stream().filter(c -> c.getStatus() == ClientStatus.ACTIVE).count();

        return new AdminDto.Stats(
                all.size(),
                active,
                lineUsers.countByStatus(LineUserStatus.ACTIVE),
                lineUsers.countByOwnerTrueAndStatus(LineUserStatus.ACTIVE),
                notifications.countByCreatedAtGreaterThanEqual(since),
                notifications.countByStatusAndCreatedAtGreaterThanEqual(NotificationStatus.SUCCEEDED, since),
                notifications.countByStatusAndCreatedAtGreaterThanEqual(NotificationStatus.PARTIAL, since),
                notifications.countByStatusAndCreatedAtGreaterThanEqual(NotificationStatus.FAILED, since));
    }

    /** LINE 官方帳號的月配額用量。獨立於 {@link #stats()}，失敗不影響其餘卡片。 */
    public AdminDto.LineQuota lineQuota() {
        return lineQuotaClient.current();
    }

    // -------------------------------------------------------------- 憑證

    /** 依建立時間排序，讓清單順序穩定 —— 每次重整都跳動的列表沒辦法用。 */
    @Transactional(readOnly = true)
    public List<AdminDto.ClientSummary> listClients() {
        return clients.findAll().stream()
                .sorted(Comparator.comparing(Client::getCreatedAt))
                .map(AdminDto.ClientSummary::from)
                .toList();
    }

    /**
     * 建立憑證。<strong>回傳含明文 secret，只此一次。</strong>
     *
     * @throws ApiException 參數不合法（400），例如 OWNER 沒給 lineUserId
     */
    @Transactional
    public AdminDto.CreatedClient createClient(AdminDto.CreateClientRequest request) {
        AdminDto.ClientKind kind = request.kind() == null
                ? AdminDto.ClientKind.SERVICE
                : request.kind();

        ClientService.CreateClientCommand command;
        try {
            command = switch (kind) {
                case SERVICE -> ClientService.CreateClientCommand.forService(
                        request.name(), request.dailyQuota());
                case OWNER -> {
                    if (request.lineUserId() == null || request.lineUserId().isBlank()) {
                        throw new IllegalArgumentException(
                                "OWNER client requires a bound LINE user id.");
                    }
                    requireActiveUser(request.lineUserId());
                    yield ClientService.CreateClientCommand.forOwner(
                            request.name(), request.lineUserId());
                }
            };
            AdminDto.CreatedClient created = AdminDto.CreatedClient.from(clientService.create(command));
            log.info("後台建立憑證：kind={} name={}", kind, request.name());
            return created;
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    /**
     * 作廢憑證（不可回復）。
     *
     * @throws ApiException client 不存在（404）
     */
    @Transactional
    public void revokeClient(String clientId) {
        requireClient(clientId);
        clientService.revoke(clientId, "revoked from admin console");
    }

    @Transactional
    public AdminDto.ClientSummary setDefaultTarget(String clientId,
                                                   TargetType type,
                                                   List<String> userIds) {
        Client client = requireClient(clientId);

        List<String> normalised = type == TargetType.USER
                ? validateRecipients(userIds)
                : List.of();

        try {
            client.setDefaultTarget(type, normalised, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        log.info("設定預設通知對象：client={} type={} recipients={}",
                clientId, type, normalised.size());
        return AdminDto.ClientSummary.from(client);
    }

    // ------------------------------------------------------------ 使用者

    /** 只列 ACTIVE 的 —— 已封鎖的人選了也送不到，讓他出現在選單只會製造誤會。 */
    @Transactional(readOnly = true)
    public List<AdminDto.LineUserSummary> listActiveLineUsers() {
        return lineUsers.findByStatus(LineUserStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(LineUser::isOwner).reversed()
                        .thenComparing(u -> u.getDisplayName() == null
                                ? u.getLineUserId() : u.getDisplayName()))
                .map(AdminDto.LineUserSummary::from)
                .toList();
    }

    /**
     * 切換某使用者的 owner 標記。
     *
     * @throws ApiException 使用者不存在或非 ACTIVE（400）
     */
    @Transactional
    public AdminDto.LineUserSummary setOwner(String lineUserId, boolean owner) {
        requireActiveUser(lineUserId);
        lineUserService.setOwner(lineUserId, owner);
        LineUser updated = lineUsers.findById(lineUserId).orElseThrow();
        log.info("後台切換 owner：lineUserId={} owner={}", lineUserId, owner);
        return AdminDto.LineUserSummary.from(updated);
    }

    // -------------------------------------------------------------- 發送

    /**
     * 從後台直接發一則通知，用指定 client 的身分。{@code request.scheduledAt} 非
     * null 時改為排程，不會立刻送。
     *
     * <p>重用 {@link NotificationService#submit}，因此 scope 檢查、預設對象、
     * 連結白名單、切批全部一致 —— 後台不是另一條發送路徑，只是換個地方觸發。
     *
     * @throws ApiException client 不存在或非 ACTIVE（400）、排程時間不合理（400），
     *                       或發送被拒（原樣往上拋）
     */
    @Transactional
    public NotificationAccepted sendTest(AdminDto.SendTestRequest request) {
        Client client = requireActiveClient(request.clientId());

        Instant scheduledAt = validateScheduledAt(request.scheduledAt());

        NotificationRequest.Target target = request.type() == null
                ? null
                : new NotificationRequest.Target(request.type(), request.userIds());
        NotificationRequest notificationRequest = new NotificationRequest(
                target,
                new NotificationRequest.Message(emptyToNull(request.title()), request.text()),
                null,
                null);

        // rawBody 只用來算 payload hash；用序列化後的位元組即可，管理台不比對簽章
        byte[] rawBody = objectMapper.writeValueAsString(notificationRequest)
                .getBytes(StandardCharsets.UTF_8);

        return notificationService.submit(
                ClientPrincipal.from(client),
                notificationRequest,
                rawBody,
                null,
                "admin-" + UUID.randomUUID(),
                scheduledAt);
    }

    /**
     * @return null（立即發送），或驗證過、確定在未來的排程時間
     * @throws ApiException 排在過去，或排得離譜地遠（很可能是選錯年份）
     */
    private Instant validateScheduledAt(Instant requested) {
        if (requested == null) {
            return null;
        }
        Instant now = clock.instant();
        if (requested.isBefore(now.plus(MIN_SCHEDULE_LEAD))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "scheduledAt must be at least " + MIN_SCHEDULE_LEAD.toSeconds()
                            + " seconds in the future.");
        }
        if (requested.isAfter(now.plus(MAX_SCHEDULE_LEAD))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "scheduledAt is more than " + MAX_SCHEDULE_LEAD.toDays()
                            + " days out — double-check the date.");
        }
        return requested;
    }

    // ------------------------------------------------------------ 排程

    /** 還沒到派送時間的排程通知，最快到期的排前面。 */
    @Transactional(readOnly = true)
    public List<AdminDto.ScheduledNotification> listScheduled() {
        Map<Long, String> clientNames = clients.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Client::getId, Client::getName));

        return notifications
                .findByScheduledAtIsNotNullAndStatusOrderByScheduledAtAsc(
                        NotificationStatus.QUEUED, Limit.of(SCHEDULED_LIMIT))
                .stream()
                .map(n -> AdminDto.ScheduledNotification.from(
                        n, clientNames.getOrDefault(n.getClientId(), "(unknown)")))
                .toList();
    }

    /**
     * 取消一則排程。已經開始送（或已結束）的取消不到。
     *
     * @throws ApiException 找不到該筆通知（404），或已經不是「還沒開始送」的
     *                       狀態（400）—— 兩者分開回，前端才能顯示對的訊息
     */
    @Transactional
    public void cancelScheduled(UUID notificationId) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification not found."));

        boolean cancelled = deliveryStore.cancel(notificationId);
        if (!cancelled) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cannot cancel: status is already " + notification.getStatus()
                            + " (has started sending or finished).");
        }
        log.info("後台取消排程：notificationId={}", notificationId);
    }

    // ------------------------------------------------------------ 發送紀錄

    @Transactional(readOnly = true)
    public List<AdminDto.NotificationSummary> recentNotifications() {
        Map<Long, String> clientNames = clients.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Client::getId, Client::getName));

        return notifications.findAllByOrderByCreatedAtDesc(Limit.of(RECENT_LIMIT)).stream()
                .map(n -> AdminDto.NotificationSummary.from(
                        n, clientNames.getOrDefault(n.getClientId(), "(unknown)")))
                .toList();
    }

    @Transactional(readOnly = true)
    public NotificationDetail notificationDetail(UUID id) {
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification not found."));
        return NotificationDetail.from(
                notification, deliveries.findByNotificationIdOrderByBatchNo(id));
    }

    // ------------------------------------------------------------------ 監控

    /** 依建立時間排序，理由同 {@link #listClients}。 */
    @Transactional(readOnly = true)
    public List<AdminDto.MonitorSummary> listMonitors() {
        Map<Long, Client> clientById = clients.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Client::getId, c -> c));
        return monitors.findAll().stream()
                .sorted(Comparator.comparing(ApiMonitor::getCreatedAt))
                .map(m -> toMonitorSummary(m, clientById.get(m.getClientId())))
                .toList();
    }

    /**
     * 建立監控。
     *
     * <p><strong>AAD 陷阱</strong>：header 密文的 AAD 是 {@code monitor:{id}}，
     * {@code id} 是 {@code BIGSERIAL}，建立當下還不存在。這裡先不帶 header 存一次
     * 拿到 id（{@code IDENTITY} 產生策略下，{@code save()} 當下就是真的 INSERT，
     * 交易還沒 commit 也讀得到 id），再用這個 id 加密、存第二次。順序顛倒的話，
     * 用還是 {@code null} 的 id 當 AAD 加密出來的密文永遠解不開。
     *
     * @throws ApiException client 不存在或非 ACTIVE、interval 低於下限、
     *                       extract rule / pointer 格式不合法、請求模板含未知佔位符或
     *                       畸形 {@code now.format} pattern（均 400）
     */
    @Transactional
    public AdminDto.MonitorSummary createMonitor(AdminDto.CreateMonitorRequest request) {
        Client client = requireActiveClient(request.clientId());
        int intervalSeconds = requireValidInterval(request.intervalSeconds());
        validateExtractRules(request.extractRules());
        validateItemPointers(request.compareMode(), request.itemPointer(), request.itemKeyPointer());
        validateRequestTemplate(request.url(), request.headers(), request.requestBody());

        Instant now = clock.instant();
        ApiMonitor monitor;
        try {
            monitor = new ApiMonitor(
                    request.name(), client.getId(), request.url(), request.method(), request.requestBody(),
                    null, null, null,
                    intervalSeconds, request.enabled() == null || request.enabled(),
                    request.compareMode(), writeExtractRulesJson(request.extractRules()),
                    request.itemPointer(), request.itemKeyPointer(), request.messageTemplate(),
                    request.notifyOnFailure() == null || request.notifyOnFailure(),
                    request.cooldownSeconds() == null ? 0 : request.cooldownSeconds(),
                    request.maxNotificationsPerDay(), now);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        monitor = monitors.save(monitor); // 第一次寫入：拿到 id，此時尚未帶 header

        if (request.headers() != null && !request.headers().isEmpty()) {
            applyEncryptedHeaders(monitor, request.headers(), now);
            monitor = monitors.save(monitor); // 第二次寫入：用剛拿到的 id 當 AAD 加密後回寫
        }

        log.info("後台建立監控：id={} name={} clientId={}", monitor.getId(), monitor.getName(), client.getClientId());
        return toMonitorSummary(monitor, client);
    }

    /**
     * 更新監控（PUT，整份取代）。執行狀態（排程時間、fingerprint、失敗計數）不受影響，
     * 見 {@code ApiMonitor#applyUpdate} 的說明。
     *
     * @throws ApiException 監控不存在（404）；client 不存在或非 ACTIVE、interval 低於下限、
     *                       extract rule / pointer 格式不合法、請求模板含未知佔位符或
     *                       畸形 {@code now.format} pattern（均 400）
     */
    @Transactional
    public AdminDto.MonitorSummary updateMonitor(Long id, AdminDto.UpdateMonitorRequest request) {
        ApiMonitor monitor = requireMonitor(id);
        Client client = requireActiveClient(request.clientId());
        int intervalSeconds = requireValidInterval(request.intervalSeconds());
        validateExtractRules(request.extractRules());
        validateItemPointers(request.compareMode(), request.itemPointer(), request.itemKeyPointer());
        validateRequestTemplate(request.url(), request.headers(), request.requestBody());

        Instant now = clock.instant();
        try {
            monitor.applyUpdate(
                    request.name(), client.getId(), request.url(), request.method(), request.requestBody(),
                    intervalSeconds, request.enabled() == null || request.enabled(),
                    request.compareMode(), writeExtractRulesJson(request.extractRules()),
                    request.itemPointer(), request.itemKeyPointer(), request.messageTemplate(),
                    request.notifyOnFailure() == null || request.notifyOnFailure(),
                    request.cooldownSeconds() == null ? 0 : request.cooldownSeconds(),
                    request.maxNotificationsPerDay(), now);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        // 留空 = 不變更既有 header；非空 = 整份覆寫（見 AdminDto.UpdateMonitorRequest 的說明）。
        // 這個 monitor 的 id 在編輯時早就存在，不會遇到建立時的 AAD 陷阱。
        if (request.headers() != null && !request.headers().isEmpty()) {
            applyEncryptedHeaders(monitor, request.headers(), now);
        }

        monitor = monitors.save(monitor);
        log.info("後台更新監控：id={} name={}", monitor.getId(), monitor.getName());
        return toMonitorSummary(monitor, client);
    }

    /**
     * 刪除監控（不可回復）。{@code api_monitor_run} / {@code api_monitor_seen_item}
     * 透過 {@code ON DELETE CASCADE} 一併清掉，見 migration。
     *
     * @throws ApiException 監控不存在（404）
     */
    @Transactional
    public void deleteMonitor(Long id) {
        requireMonitor(id);
        monitors.deleteById(id);
        log.info("後台刪除監控：id={}", id);
    }

    /**
     * 啟用／停用。不動排程狀態——{@code next_run_at} 維持原值，見
     * {@code ApiMonitor#setEnabled} 的說明。
     *
     * @throws ApiException 監控不存在（404）
     */
    @Transactional
    public AdminDto.MonitorSummary setMonitorEnabled(Long id, boolean enabled) {
        ApiMonitor monitor = requireMonitor(id);
        monitor.setEnabled(enabled, clock.instant());
        Client client = clients.findById(monitor.getClientId()).orElse(null);
        log.info("後台切換監控啟用狀態：id={} enabled={}", id, enabled);
        return toMonitorSummary(monitor, client);
    }

    /**
     * 試跑：抓一次、回傳抽出的值與渲染後的訊息，<strong>不發送、不寫入任何狀態</strong>。
     *
     * <p>安全關鍵：實際的 guard + fetch 邏輯全部在 {@link ApiMonitorTestRunner}
     * 裡——跟 {@code ApiMonitorRunner} 共用同一顆 {@code OutboundUrlGuard} bean，
     * 這裡不能也不會自己另外組一條路徑繞過去。見該類別的類別註解。
     *
     * <p>試跑一樣套用 {@link RequestTemplate}——URL／header／body 裡的
     * {@code {{ ... }}} 佔位符在送出前先替換，跟排程輪詢（{@code ApiMonitorRunner}）
     * 是同一份替換邏輯，讓「立即測試」看到的結果跟實際排程會打出去的請求一致。
     *
     * @throws ApiException extract rule / pointer 格式不合法、請求模板含未知佔位符或
     *                       畸形 {@code now.format} pattern，或替換後的 url 不是合法
     *                       URI（均 400）
     */
    public AdminDto.MonitorTestResult testMonitor(AdminDto.MonitorTestRequest request) {
        validateExtractRules(request.extractRules());
        validateItemPointers(request.compareMode(), request.itemPointer(), request.itemKeyPointer());

        URI uri = parseTestUrl(requestTemplate.render(request.url()));
        Map<String, String> renderedHeaders = requestTemplate.renderHeaders(request.headers());
        String renderedBody = requestTemplate.render(request.requestBody());

        String name = emptyToNull(request.name());
        ApiMonitorTestRunner.TestConfig config = new ApiMonitorTestRunner.TestConfig(
                name == null ? "(測試)" : name, uri, request.method(), renderedBody, renderedHeaders,
                request.compareMode(), request.extractRules(),
                request.itemPointer(), request.itemKeyPointer(), request.messageTemplate());

        return AdminDto.MonitorTestResult.from(monitorTestRunner.run(config));
    }

    // -------------------------------------------------------------- 監控：匯入（W5）

    /**
     * 匯入解析：把貼上的 cURL / {@code fetch(...)} / 自訂 JSON 解析成
     * {@link ImportedRequest}，<strong>不存檔</strong>。見
     * {@code Docs/plan/12-API監控易用性升級.md} §2.6。
     *
     * <p><strong>安全關鍵</strong>：解析出的 URL 立刻過 {@link OutboundUrlGuard}，
     * 擋掉就丟例外、<strong>不回傳解析結果</strong>——否則這個端點就變成一個「先幫你
     * 把 cookie/token 解出來，再告訴你打不到」的資訊外洩管道，guard 擋下時什麼都
     * 不該回。
     *
     * <p><strong>絕不記錄</strong>：這個方法、{@link RequestImporter} 與它底下的三個
     * 解析器都不寫任何 log（含 debug）——貼上的內容含 cookie 與 API token。輸入大小
     * 上限在這裡以<strong>位元組數</strong>檢查，不是字元數：貼上內容常帶中文
     * header 值，用字元數當上限會低估實際傳輸位元組數。
     *
     * @throws ApiException 輸入超過 64 KB（{@code PAYLOAD_TOO_LARGE}）；格式無法辨識、
     *                       解析失敗，或解析出的 URL 被 guard 擋下（均
     *                       {@code VALIDATION_ERROR}）
     */
    public ImportedRequest importMonitorRequest(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > IMPORT_MAX_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Pasted content exceeds the 64 KB limit.");
        }

        ImportedRequest imported = requestImporter.importRequest(raw);

        URI uri;
        try {
            uri = URI.create(imported.url());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Parsed URL is not a valid URI.");
        }
        try {
            outboundUrlGuard.check(uri);
        } catch (OutboundUrlGuard.BlockedException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        return imported;
    }

    /** 單一監控最近 50 筆執行紀錄，最新在前。 */
    @Transactional(readOnly = true)
    public List<AdminDto.MonitorRunSummary> monitorRuns(Long id) {
        requireMonitor(id);
        return monitorRunRepository.findByMonitorIdOrderByStartedAtDesc(id, Limit.of(MONITOR_RUNS_LIMIT)).stream()
                .map(AdminDto.MonitorRunSummary::from)
                .toList();
    }

    // -------------------------------------------------------------- 監控：共用

    private ApiMonitor requireMonitor(Long id) {
        return monitors.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Monitor not found."));
    }

    private AdminDto.MonitorSummary toMonitorSummary(ApiMonitor monitor, Client client) {
        return AdminDto.MonitorSummary.from(
                monitor,
                client == null ? null : client.getClientId(),
                client == null ? "(unknown)" : client.getName(),
                parseExtractRules(monitor.getExtractRules()));
    }

    private List<ExtractRule> parseExtractRules(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return List.of(objectMapper.readValue(json, ExtractRule[].class));
    }

    private String writeExtractRulesJson(List<ExtractRule> rules) {
        return objectMapper.writeValueAsString(rules == null ? List.of() : rules);
    }

    /**
     * 服務層生效的排程間隔下限：{@code app.monitor.min-interval}（預設 60 秒），
     * 高於 DB 的 {@code interval_seconds >= 30} 約束。W3 只在重新排程時套用這個下限，
     * 這裡把它提前到建立／更新當下檢查，讓後台立刻給出 400 而不是悄悄被覆寫成下限值。
     */
    private int requireValidInterval(int intervalSeconds) {
        int floorSeconds = (int) monitorProperties.minInterval().toSeconds();
        if (intervalSeconds < floorSeconds) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "intervalSeconds must be at least " + floorSeconds
                            + " seconds (app.monitor.min-interval).");
        }
        return intervalSeconds;
    }

    private void validateExtractRules(List<ExtractRule> rules) {
        if (rules == null) {
            return;
        }
        for (ExtractRule rule : rules) {
            if (rule.name() == null || !EXTRACT_RULE_NAME.matcher(rule.name()).matches()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "extract rule name must match [A-Za-z0-9_]{1,32}: " + rule.name());
            }
            validatePointerSyntax(rule.pointer(), "extract rule \"" + rule.name() + "\" pointer");
        }
    }

    private void validateItemPointers(CompareMode compareMode, String itemPointer, String itemKeyPointer) {
        if (compareMode != CompareMode.NEW_ITEMS) {
            return;
        }
        // NEW_ITEMS 缺兩個 pointer 其中一個時，ApiMonitor 建構子／applyUpdate 自己會擋
        // （抄 api_monitor_newitems_chk），這裡只驗證「有給的話語法對不對」。
        if (itemPointer != null) {
            validatePointerSyntax(itemPointer, "itemPointer");
        }
        if (itemKeyPointer != null) {
            validatePointerSyntax(itemKeyPointer, "itemKeyPointer");
        }
    }

    private void validatePointerSyntax(String pointer, String fieldLabel) {
        if (pointer == null || pointer.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, fieldLabel + " must not be blank.");
        }
        try {
            tools.jackson.core.JsonPointer.compile(pointer);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "invalid JsonPointer syntax (" + fieldLabel + "): " + pointer);
        }
    }

    /**
     * 加密 header 並回寫到 entity 上（不落地）。呼叫端負責在正確的時機呼叫——
     * 建立時必須先 {@code save()} 拿到 id 才能呼叫這個方法，見 {@link #createMonitor}
     * 的說明。
     */
    private void applyEncryptedHeaders(ApiMonitor monitor, Map<String, String> headers, Instant now) {
        String plaintext = objectMapper.writeValueAsString(headers);
        EncryptedSecret encrypted = secretCipher.encrypt(plaintext, HEADERS_AAD_PREFIX + monitor.getId());
        monitor.applyHeaders(encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), now);
    }

    private URI parseTestUrl(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "url is not a valid URI.");
        }
    }

    /**
     * 存檔前驗證請求模板（{@code Docs/plan/12-API監控易用性升級.md} §2.5）：URL、每個
     * header value、body 都要能通過 {@link RequestTemplate#render}——未知佔位符或
     * 畸形 {@code now.format} pattern 在這裡就拒絕存檔，不要等到排程真的執行時才發現
     * 「每一輪都打到錯的網址」。替換後的 URL 也要能被 {@link URI#create} 解析（例如
     * pattern 若刻意產生空白等非法字元，會在這裡就被抓到，而不是等實際輪詢時）。
     *
     * <p>只丟棄渲染結果，不使用——這裡只是借用 {@code render()} 的驗證副作用，
     * 真正的替換要等到實際送出前才做（{@code {{now...}}} 的值本來就不該在存檔當下
     * 就固定下來）。
     *
     * @param headers 編輯時可能是 {@code null}（= 不變更既有 header，見
     *                {@code AdminDto.UpdateMonitorRequest} 的說明）——這種情況下沒有
     *                新內容需要驗證，既有 header 早在它自己存檔的當下就驗證過了
     */
    private void validateRequestTemplate(String url, Map<String, String> headers, String requestBody) {
        String renderedUrl = requestTemplate.render(url);
        try {
            URI.create(renderedUrl);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "url is not a valid URI after applying template variables.");
        }
        if (headers != null && !headers.isEmpty()) {
            requestTemplate.renderHeaders(headers);
        }
        if (requestBody != null) {
            requestTemplate.render(requestBody);
        }
    }

    // ------------------------------------------------------------------ 共用

    private Client requireClient(String clientId) {
        return clients.findByClientId(clientId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Client not found."));
    }

    /**
     * 憑證存在且是 ACTIVE。{@link #sendTest} 與監控的建立／更新都要這道檢查——
     * 一個監控指向已作廢或不存在的憑證，偵測到變更也永遠發不出通知，等於悄悄失效。
     *
     * @throws ApiException 不存在或非 ACTIVE（400，訊息與 {@link #sendTest} 原本的寫法一致）
     */
    private Client requireActiveClient(String clientId) {
        Client client = clients.findByClientId(clientId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Client not found: " + clientId));
        if (client.getStatus() != ClientStatus.ACTIVE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Client is not active: " + clientId);
        }
        return client;
    }

    private void requireActiveUser(String lineUserId) {
        boolean active = lineUsers.findById(lineUserId)
                .map(u -> u.getStatus() == LineUserStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "LINE user is unknown or not active: " + lineUserId);
        }
    }

    private List<String> validateRecipients(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "userIds is required when type is USER.");
        }
        List<String> unique = requested.stream().distinct().toList();
        List<String> active = lineUsers
                .findByLineUserIdInAndStatus(unique, LineUserStatus.ACTIVE).stream()
                .map(LineUser::getLineUserId)
                .toList();

        List<String> missing = unique.stream().filter(id -> !active.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "These LINE users are unknown or not active: " + String.join(", ", missing));
        }
        return unique;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
