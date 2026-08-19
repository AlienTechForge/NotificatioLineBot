package com.jason.notifyline.notification;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.notification.api.NotificationAccepted;
import com.jason.notifyline.notification.api.NotificationRequest;
import com.jason.notifyline.notification.dispatch.DeliveryDispatcher;
import com.jason.notifyline.notification.domain.DeliveryStatus;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import com.jason.notifyline.notification.domain.NotificationStatus;
import com.jason.notifyline.notification.domain.TargetType;
import com.jason.notifyline.support.PostgresIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派送段的端到端行為：受理 → 呼叫 LINE → 回寫 → 結算。
 *
 * <p>LINE 用 MockWebServer 打樁，這樣才驗得到那些「只在特定回應下才發生」的分支：
 * 429 的兩種意思、5xx 的重試、retry key 在重試時不變。
 */
@DisplayName("派送（整合）")
class DeliveryDispatchIT extends PostgresIntegrationTest {

    private static final MockWebServer LINE = new MockWebServer();

    static {
        try {
            LINE.start();
        } catch (IOException e) {
            throw new IllegalStateException("無法啟動 LINE 打樁伺服器", e);
        }
    }

    @DynamicPropertySource
    static void lineEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.line.api-base-url", () -> LINE.url("/").toString().replaceAll("/+$", ""));
        // 排程器會與測試搶同一批工作，讓「跑了幾輪」變得不可預期
        registry.add("app.dispatch.enabled", () -> "false");
    }

    @AfterAll
    static void stopLine() throws IOException {
        LINE.close();
    }

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private DeliveryDispatcher dispatcher;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private LineUserRepository lineUserRepository;
    @Autowired
    private CircuitBreaker lineCircuitBreaker;
    @Autowired
    private Clock clock;

    private ClientPrincipal principal;

    @BeforeEach
    void setUp() {
        deliveries.deleteAll();
        notifications.deleteAll();
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();
        drainStub();
        // 斷路器是整個 context 共用的單例。失敗路徑的測試會把它推到開路，
        // 之後的測試就完全不會發出 HTTP 請求 —— 症狀是後面的測試莫名其妙地
        // 卡在 takeRequest() 上，看起來完全不像是前一個測試造成的。
        lineCircuitBreaker.reset();

        owner("U%032d".formatted(1));
        var issued = clientService.create(new ClientService.CreateClientCommand(
                "admin", null, Set.of(Scope.NOTIFY_OWNER, Scope.NOTIFY_ALL), null, null, true));
        principal = ClientPrincipal.from(
                clientRepository.findByClientId(issued.clientId()).orElseThrow());
    }

    private void owner(String lineUserId) {
        LineUser user = new LineUser(lineUserId, clock.instant());
        user.setOwner(true, clock.instant());
        lineUserRepository.save(user);
    }

    private void member(String lineUserId) {
        lineUserRepository.save(new LineUser(lineUserId, clock.instant()));
    }

    /** MockWebServer 的佇列會跨測試殘留，沒清會讓下一個測試拿到上一個排的回應。 */
    private void drainStub() {
        try {
            while (LINE.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
                // 丟掉
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MockResponse ok(String requestId) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("x-line-request-id", requestId)
                .body("{}")
                .build();
    }

    private static MockResponse error(int code, String body) {
        return new MockResponse.Builder().code(code).body(body).build();
    }

    private NotificationAccepted submit(TargetType type, String text) {
        NotificationRequest request = new NotificationRequest(
                new NotificationRequest.Target(type, null),
                new NotificationRequest.Message(null, text), null, null);
        return notificationService.submit(principal, request,
                text.getBytes(StandardCharsets.UTF_8), null, UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------ 成功路徑

    @Test
    @DisplayName("送出成功：批次 SENT、通知 SUCCEEDED、留下 x-line-request-id")
    void successfulSend() throws Exception {
        LINE.enqueue(ok("req-abc"));
        var accepted = submit(TargetType.OWNER, "備份完成");

        assertThat(dispatcher.runOnce()).isEqualTo(1);

        var delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(delivery.getLineRequestId()).isEqualTo("req-abc");
        assertThat(delivery.getAttemptCount()).isEqualTo(1);

        var notification = notifications.findById(accepted.notificationId()).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SUCCEEDED);
        assertThat(notification.getSuccessCount()).isEqualTo(1);
        assertThat(notification.getFailureCount()).isZero();
        assertThat(notification.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("送出的請求含 to、messages 與 X-Line-Retry-Key，且不含內部旗標")
    void requestShape() throws Exception {
        LINE.enqueue(ok("req-1"));
        submit(TargetType.OWNER, "內容");
        dispatcher.runOnce();

        RecordedRequest request = nextRequest();
        assertThat(request.getTarget()).isEqualTo("/v2/bot/message/multicast");
        assertThat(request.getHeaders().get("X-Line-Retry-Key")).isNotBlank();
        assertThat(request.getHeaders().get("Authorization")).startsWith("Bearer ");

        String body = request.getBody().utf8();
        assertThat(body).contains("\"to\"").contains("\"messages\"").contains("內容");
        // persistPayload 是我們自己的旗標，不該飛到 LINE 的 API
        assertThat(body).doesNotContain("persistPayload");
    }

    @Test
    @DisplayName("1200 人切 3 批，各自送出一次")
    void batchesAreSentIndividually() throws Exception {
        IntStream.range(2, 1202).forEach(i -> member("U%032d".formatted(i)));
        for (int i = 0; i < 3; i++) {
            LINE.enqueue(ok("req-" + i));
        }

        var accepted = submit(TargetType.ALL, "公告");
        assertThat(accepted.batchCount()).isEqualTo(3);
        assertThat(dispatcher.runOnce()).isEqualTo(3);

        assertThat(deliveries.findAll())
                .allMatch(d -> d.getStatus() == DeliveryStatus.SENT);
        assertThat(notifications.findById(accepted.notificationId()).orElseThrow())
                .satisfies(n -> {
                    assertThat(n.getStatus()).isEqualTo(NotificationStatus.SUCCEEDED);
                    assertThat(n.getSuccessCount()).isEqualTo(1201);
                });
    }

    // ------------------------------------------------------------ 失敗路徑

    @Test
    @DisplayName("5xx：排入重試，attempt_count 遞增，next_attempt_at 往後")
    void serverErrorSchedulesRetry() {
        LINE.enqueue(error(503, "unavailable"));
        submit(TargetType.OWNER, "x");
        var before = clock.instant();

        dispatcher.runOnce();

        var delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(before);
        assertThat(delivery.getErrorCode()).isEqualTo("LINE_SERVER_ERROR");
    }

    @Test
    @DisplayName("重試沿用同一把 retry key —— 讓 LINE 認出是同一則訊息而不重複發送")
    void retryReusesRetryKey() throws Exception {
        LINE.enqueue(error(503, "unavailable"));
        LINE.enqueue(ok("req-2"));
        submit(TargetType.OWNER, "x");

        dispatcher.runOnce();
        String firstKey = nextRequest().getHeaders().get("X-Line-Retry-Key");

        // 讓退避時間過去
        forceDue();
        dispatcher.runOnce();
        String secondKey = nextRequest().getHeaders().get("X-Line-Retry-Key");

        assertThat(secondKey).isEqualTo(firstKey);
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    @DisplayName("400：終局失敗，不重試，通知標 FAILED")
    void badRequestIsFatal() {
        LINE.enqueue(error(400, "{\"message\":\"invalid property\"}"));
        var accepted = submit(TargetType.OWNER, "x");

        dispatcher.runOnce();

        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(DeliveryStatus.FAILED);
        var notification = notifications.findById(accepted.notificationId()).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("429 月額度用罄：終局失敗，不浪費 4 次重試")
    void monthlyQuotaIsFatal() {
        LINE.enqueue(error(429, "{\"message\":\"You have reached your monthly limit.\"}"));
        submit(TargetType.OWNER, "x");

        dispatcher.runOnce();

        var delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorCode()).isEqualTo("LINE_MONTHLY_QUOTA");
    }

    @Test
    @DisplayName("用盡 5 次重試後標為 FAILED")
    void exhaustedRetriesFail() {
        submit(TargetType.OWNER, "x");

        for (int attempt = 0; attempt < 5; attempt++) {
            LINE.enqueue(error(503, "unavailable"));
            forceDue();
            dispatcher.runOnce();
        }

        var delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("部分批次失敗 → 通知是 PARTIAL，計數以收件人數為單位")
    void partialSuccess() {
        IntStream.range(2, 700).forEach(i -> member("U%032d".formatted(i)));
        LINE.enqueue(ok("req-1"));
        LINE.enqueue(error(400, "bad"));

        var accepted = submit(TargetType.ALL, "公告");
        dispatcher.runOnce();

        var notification = notifications.findById(accepted.notificationId()).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PARTIAL);
        assertThat(notification.getSuccessCount() + notification.getFailureCount()).isEqualTo(699);
        assertThat(notification.getSuccessCount()).isPositive();
        assertThat(notification.getFailureCount()).isPositive();
    }

    // ------------------------------------------------------------ 取件語意

    @Test
    @DisplayName("取件後上租約：同一批不會在同一輪被取兩次")
    void leasePreventsDoubleClaim() {
        LINE.enqueue(ok("req-1"));
        submit(TargetType.OWNER, "x");

        dispatcher.runOnce();
        // 已送出，第二輪不該再有工作
        assertThat(dispatcher.runOnce()).isZero();
    }

    @Test
    @DisplayName("沒有待送工作時是零成本的一輪")
    void idlePollDoesNothing() {
        assertThat(dispatcher.runOnce()).isZero();
    }

    @Test
    @DisplayName("payload 已被清空的批次標為失敗，不會永遠佔著 PENDING")
    void missingPayloadFailsFast() {
        var accepted = submit(TargetType.OWNER, "x");
        notifications.findById(accepted.notificationId()).ifPresent(n -> {
            n.clearPayload();
            notifications.save(n);
        });

        assertThat(dispatcher.runOnce()).isZero();

        var delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getErrorCode()).isEqualTo("PAYLOAD_GONE");
    }

    // ------------------------------------------------------- persistPayload

    @Test
    @DisplayName("persistPayload=false：送完就清空內容，但重試期間仍在")
    void payloadClearedAfterCompletion() {
        NotificationRequest request = new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null),
                new NotificationRequest.Message(null, "機密"), null,
                new NotificationRequest.Options(false, false));
        var accepted = notificationService.submit(principal, request,
                "機密".getBytes(StandardCharsets.UTF_8), null, "req-1");

        // 第一次失敗：內容必須還在，否則重試沒東西可送
        LINE.enqueue(error(503, "unavailable"));
        dispatcher.runOnce();
        assertThat(notifications.findById(accepted.notificationId()).orElseThrow().getPayload())
                .isNotNull();

        LINE.enqueue(ok("req-ok"));
        forceDue();
        dispatcher.runOnce();

        var notification = notifications.findById(accepted.notificationId()).orElseThrow();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SUCCEEDED);
        assertThat(notification.getPayload()).isNull();
        // 內容沒了，metadata 與 hash 仍在
        assertThat(notification.getPayloadHash()).isNotEmpty();
        assertThat(notification.getRecipientCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("預設保存內容")
    void payloadKeptByDefault() {
        LINE.enqueue(ok("req-1"));
        var accepted = submit(TargetType.OWNER, "一般內容");
        dispatcher.runOnce();

        assertThat(notifications.findById(accepted.notificationId()).orElseThrow().getPayload())
                .contains("一般內容");
    }

    /**
     * 取下一個送到打樁伺服器的請求。
     *
     * <p>一定要有逾時。無參數的 {@code takeRequest()} 在請求沒發出時會<strong>永遠
     * 阻塞</strong>，於是整個測試套件卡住而不是失敗 —— CI 上就是一次逾時的 job，
     * 沒有任何指出原因的訊息。
     */
    private static RecordedRequest nextRequest() throws InterruptedException {
        RecordedRequest request = LINE.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(request).as("預期有一個請求送到 LINE，但沒有").isNotNull();
        return request;
    }

    /** 把待送批次的退避時間拉到現在，免得測試真的等 1~16 秒。 */
    private void forceDue() {
        List<com.jason.notifyline.notification.domain.NotificationDelivery> pending =
                deliveries.findAll().stream()
                        .filter(d -> d.getStatus() == DeliveryStatus.PENDING)
                        .toList();
        pending.forEach(d -> d.lease(clock.instant().minusSeconds(1)));
        deliveries.saveAll(pending);
    }
}
