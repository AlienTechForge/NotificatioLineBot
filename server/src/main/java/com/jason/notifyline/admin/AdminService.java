package com.jason.notifyline.admin;

import com.jason.notifyline.auth.ClientPrincipal;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private final ClientRepository clients;
    private final ClientService clientService;
    private final LineUserRepository lineUsers;
    private final LineUserService lineUserService;
    private final NotificationService notificationService;
    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final DeliveryStore deliveryStore;
    private final LineQuotaClient lineQuotaClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminService(ClientRepository clients,
                        ClientService clientService,
                        LineUserRepository lineUsers,
                        LineUserService lineUserService,
                        NotificationService notificationService,
                        NotificationRepository notifications,
                        NotificationDeliveryRepository deliveries,
                        DeliveryStore deliveryStore,
                        LineQuotaClient lineQuotaClient,
                        ObjectMapper objectMapper,
                        Clock clock) {
        this.clients = clients;
        this.clientService = clientService;
        this.lineUsers = lineUsers;
        this.lineUserService = lineUserService;
        this.notificationService = notificationService;
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.deliveryStore = deliveryStore;
        this.lineQuotaClient = lineQuotaClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
        Client client = clients.findByClientId(request.clientId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Client not found: " + request.clientId()));
        if (client.getStatus() != ClientStatus.ACTIVE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Client is not active: " + request.clientId());
        }

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

    // ------------------------------------------------------------------ 共用

    private Client requireClient(String clientId) {
        return clients.findByClientId(clientId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Client not found."));
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
