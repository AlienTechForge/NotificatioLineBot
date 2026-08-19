package com.jason.notifyline.notification;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.notification.api.NotificationAccepted;
import com.jason.notifyline.notification.api.NotificationDetail;
import com.jason.notifyline.notification.api.NotificationRequest;
import com.jason.notifyline.notification.dispatch.BatchSplitter;
import com.jason.notifyline.notification.dispatch.DispatchKicker;
import com.jason.notifyline.notification.dispatch.MessageAssembler;
import com.jason.notifyline.notification.dispatch.PayloadEnvelope;
import com.jason.notifyline.notification.dispatch.TargetResolver;
import com.jason.notifyline.notification.domain.Notification;
import com.jason.notifyline.notification.domain.NotificationDelivery;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import com.jason.notifyline.notification.domain.NotificationStatus;
import com.jason.notifyline.common.TargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 受理發送請求：驗證 → 解析收件人 → 記帳 → 寫入 outbox。
 *
 * <p><strong>這裡不呼叫 LINE。</strong> 交易只涵蓋資料庫寫入，送出由
 * {@code notification_delivery} 這個 outbox 表驅動（見 ADR-0007）。這條界線是刻意的：
 * 交易一旦跨越外部 API 呼叫，一個變慢的 LINE 端就會把 Hikari 的 10 條連線佔滿，
 * 讓整個服務連健康檢查都回不了。
 *
 * <p>回 202 時工作已經<strong>持久化</strong>，即使程序下一秒被 kill 也不會掉單。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 每日配額的計算窗口。用滾動 24 小時而非自然日，避免午夜瞬間額度重置被打爆。 */
    private static final Duration QUOTA_WINDOW = Duration.ofDays(1);

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final ClientRepository clients;
    private final TargetResolver targetResolver;
    private final MessageAssembler messageAssembler;
    private final DispatchKicker dispatchKicker;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications,
                               NotificationDeliveryRepository deliveries,
                               ClientRepository clients,
                               TargetResolver targetResolver,
                               MessageAssembler messageAssembler,
                               DispatchKicker dispatchKicker,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.clients = clients;
        this.targetResolver = targetResolver;
        this.messageAssembler = messageAssembler;
        this.dispatchKicker = dispatchKicker;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param rawBody        呼叫端<strong>實際簽章的那串位元組</strong>，用來算 payload hash
     * @param idempotencyKey 可為 null
     * @param requestId      correlation id，串起同步段與非同步段的日誌
     */
    @Transactional
    public NotificationAccepted submit(ClientPrincipal principal,
                                       NotificationRequest request,
                                       byte[] rawBody,
                                       String idempotencyKey,
                                       String requestId) {

        byte[] payloadHash = sha256(rawBody);

        // 冪等的快路徑。放在最前面是因為重播「不應該再扣一次配額」——
        // 呼叫端因為逾時而重送，本質上還是同一次發送。
        if (idempotencyKey != null) {
            var existing = notifications.findByClientIdAndIdempotencyKey(principal.id(), idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), payloadHash, idempotencyKey);
            }
        }

        // 內容檢查先做，不佔資料庫鎖
        List<Map<String, Object>> messages = messageAssembler.assemble(request);
        // 算一次就好。target 可能來自請求，也可能來自 client 的預設對象 ——
        // 兩處各算一次遲早會分岔，而分岔的結果是「記錄下來的型別」與
        // 「實際送給誰」不一致，事後查紀錄會查到錯的結論。
        TargetType effectiveType = targetResolver.effectiveType(principal, request);
        List<String> recipients = targetResolver.resolve(principal, request);
        List<List<String>> batches = BatchSplitter.split(recipients);

        Instant now = clock.instant();
        int recipientCount = batches.stream().mapToInt(List::size).sum();

        // 配額檢查與寫入必須在同一段鎖內，見 ClientRepository#lockById
        Client client = clients.lockById(principal.id())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_CLIENT_DISABLED,
                        "Client no longer exists."));
        enforceDailyQuota(client, recipientCount, now);

        UUID id = UUID.randomUUID();
        int inserted = notifications.insertIfAbsent(
                id,
                principal.id(),
                idempotencyKey,
                requestId,
                effectiveType.name(),
                objectMapper.writeValueAsString(PayloadEnvelope.build(
                        messages,
                        request.options().notificationDisabledOrDefault(),
                        request.options().persistPayloadOrDefault())),
                payloadHash,
                NotificationStatus.QUEUED.name(),
                recipientCount,
                now);

        if (inserted == 0) {
            // 另一個併發請求帶著同一把 key 先寫進去了。ON CONFLICT DO NOTHING
            // 讓交易保持乾淨，所以這裡可以直接讀出那一筆。
            Notification winner = notifications
                    .findByClientIdAndIdempotencyKey(principal.id(), idempotencyKey)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "Idempotent insert conflicted but no existing record was found."));
            return replay(winner, payloadHash, idempotencyKey);
        }

        persistBatches(id, batches, effectiveType, now);
        kickAfterCommit();

        log.info("受理通知：notificationId={} target={} recipients={} batches={} client={}",
                id, effectiveType, recipientCount, batches.size(), principal.clientId());

        return new NotificationAccepted(
                id, NotificationStatus.QUEUED.name(), recipientCount, batches.size());
    }

    @Transactional(readOnly = true)
    public NotificationDetail find(ClientPrincipal principal, UUID id) {
        // 查不到與「不是你的」回同一個 404 —— 否則呼叫端可以用狀態碼
        // 試出哪些 notification id 存在
        Notification notification = notifications.findByIdAndClientId(id, principal.id())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification not found."));
        return NotificationDetail.from(notification, deliveries.findByNotificationIdOrderByBatchNo(id));
    }

    // ------------------------------------------------------------------ 內部

    /**
     * 重播：內容相同就回原本的結果，內容不同就是 409。
     *
     * <p>後者代表呼叫端用同一把 key 送了不同內容 —— 幾乎都是它自己產 key 的邏輯有 bug。
     * 悄悄回傳舊結果會讓那個 bug 隱形，直到某天有人問「為什麼那則通知沒發出去」。
     */
    private NotificationAccepted replay(Notification existing, byte[] payloadHash, String key) {
        if (!MessageDigest.isEqual(existing.getPayloadHash(), payloadHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key " + key + " was already used with a different request body.");
        }
        int batchCount = deliveries.findByNotificationIdOrderByBatchNo(existing.getId()).size();
        log.info("冪等重播：notificationId={} key={}", existing.getId(), key);
        return NotificationAccepted.from(existing, batchCount);
    }

    /**
     * 每日配額（缺口 G2）。
     *
     * <p>以<strong>收件人數</strong>計，不是請求數 —— 一個 1200 人的 ALL 發送對 LINE
     * 月配額的消耗是 1200，對一個 100 請求/日的限制卻只算 1。用請求數計等於沒有限制。
     */
    private void enforceDailyQuota(Client client, int recipientCount, Instant now) {
        Integer quota = client.getDailyMessageQuota();
        if (quota == null) {
            return;
        }
        long used = notifications.sumRecipientsSince(client.getId(), now.minus(QUOTA_WINDOW));
        if (used + recipientCount > quota) {
            // 訊息帶上數字，呼叫端才能自己判斷是要等還是要調整批量
            throw new ApiException(ErrorCode.CLIENT_QUOTA_EXCEEDED,
                    "Daily quota exceeded: " + used + " of " + quota
                            + " recipients used in the last 24 hours, this request needs "
                            + recipientCount + ".");
        }
    }

    /**
     * 提交後才踢派送。
     *
     * <p><strong>提交「前」踢是個很難查的 bug</strong>：派送器在另一條執行緒開自己的
     * 交易，讀不到還沒提交的那幾列，於是空手而回 —— 這則通知就要等到下一輪排程。
     * 只在系統忙碌時偶爾發生，看起來像「有時候特別慢」。
     *
     * <p>{@code afterCommit} 跑在這條 HTTP 執行緒上，所以裡面<strong>只能</strong>
     * 呼叫另一個 bean 的 {@code @Async} 方法，而且要包 try/catch ——
     * 這裡拋出的例外會讓一個已經提交的請求對外變成 500。
     */
    private void kickAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    dispatchKicker.kick();
                } catch (Exception e) {
                    log.warn("無法觸發即時派送，交由排程處理", e);
                }
            }
        });
    }

    private void persistBatches(UUID notificationId,
                                List<List<String>> batches,
                                TargetType targetType,
                                Instant now) {
        List<NotificationDelivery> rows = new ArrayList<>(batches.size());
        for (int i = 0; i < batches.size(); i++) {
            rows.add(new NotificationDelivery(
                    notificationId,
                    i,
                    batches.get(i),
                    // 每批一把固定的 retry key，重試沿用。不同批必須不同把 ——
                    // 它們對 LINE 而言是不同的訊息。
                    UUID.randomUUID(),
                    targetType.priority(),
                    now));
        }
        deliveries.saveAll(rows);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
