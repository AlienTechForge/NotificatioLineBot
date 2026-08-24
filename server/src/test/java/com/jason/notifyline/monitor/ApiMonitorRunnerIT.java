package com.jason.notifyline.monitor;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.monitor.domain.ApiMonitor;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.domain.ApiMonitorRun;
import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.RunOutcome;
import com.jason.notifyline.monitor.fetch.DnsResolver;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.monitor.secret.MonitorSecretService;
import com.jason.notifyline.notification.domain.Notification;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：{@link ApiMonitorScheduler} 觸發的完整鏈路（不含排程本身的計時器）——
 * {@link ApiMonitorRunner#runOnce()} 真的呼叫 {@link ApiMonitorStore}、真的解析、
 * 真的比對、真的走 {@code NotificationService.submit()}，只有
 * {@link OutboundUrlGuard} 換成不擋的版本（見 {@link NoopGuardConfig}）——SSRF
 * 檢查本身的行為已經被 {@code OutboundUrlGuardTest} 窮舉覆蓋，這裡要驗證的是
 * 「一路接起來會不會動」，讓它去解析 MockWebServer 的 loopback 位址、要求 https
 * 都只會製造測試基礎設施的複雜度，不會增加信心。
 */
@DisplayName("ApiMonitorRunner（端到端）")
class ApiMonitorRunnerIT extends PostgresIntegrationTest {

    private static final MockWebServer TARGET = new MockWebServer();

    static {
        try {
            TARGET.start();
        } catch (IOException e) {
            throw new IllegalStateException("無法啟動打樁伺服器", e);
        }
    }

    @AfterAll
    static void stopTarget() throws IOException {
        TARGET.close();
    }

    @AfterEach
    void drainRequestLog() throws InterruptedException {
        while (TARGET.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 丟掉，避免佇列殘留影響下一個測試
        }
    }

    @DynamicPropertySource
    static void disableRealSchedulers(DynamicPropertyRegistry registry) {
        registry.add("app.monitor.enabled", () -> "false");
        registry.add("app.dispatch.enabled", () -> "false");
    }

    /** 讓測試可以打 http + loopback 的 MockWebServer，不必為此另外處理 TLS 或內網位址。 */
    @TestConfiguration
    static class NoopGuardConfig {
        @Bean
        @Primary
        OutboundUrlGuard outboundUrlGuard(MonitorProperties properties, DnsResolver dnsResolver) {
            return new OutboundUrlGuard(properties, dnsResolver) {
                @Override
                public void check(URI uri) {
                    // 略過 SSRF 檢查——guard 本身的行為由 OutboundUrlGuardTest 覆蓋。
                }
            };
        }
    }

    @Autowired
    private ApiMonitorRunner runner;
    @Autowired
    private ApiMonitorRepository monitors;
    @Autowired
    private ApiMonitorRunRepository runs;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private ClientService clientService;
    @Autowired
    private LineUserRepository lineUserRepository;
    @Autowired
    private Clock clock;
    @Autowired
    private SecretCipher secretCipher;
    @Autowired
    private MonitorSecretService monitorSecretService;
    @Autowired
    private ObjectMapper objectMapper;

    private Long clientId;

    @BeforeEach
    void setUp() {
        cleanUp();

        LineUser owner = new LineUser("U%032d".formatted(1), clock.instant());
        owner.setOwner(true, clock.instant());
        lineUserRepository.save(owner);

        var issued = clientService.create(new ClientService.CreateClientCommand(
                "monitor client", "U%032d".formatted(1),
                Set.of(Scope.NOTIFY_SELF, Scope.NOTIFY_OWNER), null, null, true));
        Client client = clientRepository.findByClientId(issued.clientId()).orElseThrow();
        client.setDefaultTarget(TargetType.OWNER, null, clock.instant());
        clientRepository.save(client);
        clientId = client.getId();
    }

    /**
     * 也在測試結束後清一次——理由同 {@code ApiMonitorStoreIT}：{@code PostgresIntegrationTest}
     * 整個 JVM 共用同一個容器，這裡留下的 client/notification 若不清乾淨，會讓下一個
     * 測試類別自己的 {@code clientRepository.deleteAll()} 因為 FK 約束失敗。
     */
    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        runs.deleteAll();
        monitors.deleteAll();
        deliveries.deleteAll();
        notifications.deleteAll();
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private ApiMonitor createMonitor() {
        ApiMonitor monitor = new ApiMonitor(
                "e2e monitor", clientId, TARGET.url("/api").toString(), "GET", null,
                null, null, null,
                60, true, CompareMode.EXTRACTED,
                "[{\"name\":\"status\",\"pointer\":\"/status\"}]", null, null,
                "監控「{{monitor.name}}」狀態變成 {{value.status}}（原本 {{old.status}}）",
                true, 0, null, clock.instant());
        return monitors.save(monitor);
    }

    private void forceDue(Long monitorId) {
        ApiMonitor monitor = monitors.findById(monitorId).orElseThrow();
        monitor.lease(clock.instant().minusSeconds(1));
        monitors.save(monitor);
    }

    /** 加密並套用自訂 header，AAD 規則同 {@code AdminService.applyEncryptedHeaders}。 */
    private void applyHeaders(ApiMonitor monitor, Map<String, String> headers) {
        String plaintext = objectMapper.writeValueAsString(headers);
        EncryptedSecret encrypted = secretCipher.encrypt(plaintext, "monitor:" + monitor.getId());
        monitor.applyHeaders(encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), clock.instant());
    }

    private static String md5HexUpper(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ApiMonitorRun latestRun(Long monitorId) {
        return runs.findAll().stream()
                .filter(r -> r.getMonitorId().equals(monitorId))
                .max(Comparator.comparing(ApiMonitorRun::getId))
                .orElseThrow(() -> new AssertionError("monitor " + monitorId + " 沒有任何執行紀錄"));
    }

    @Test
    @DisplayName("首次執行：只記錄基準，不發通知")
    void firstRun_recordsBaselineWithoutNotifying() {
        ApiMonitor monitor = createMonitor();
        TARGET.enqueue(jsonResponse("{\"status\":\"OK\"}"));

        assertThat(runner.runOnce()).isEqualTo(1);

        ApiMonitorRun run = latestRun(monitor.getId());
        assertThat(run.getOutcome()).isEqualTo(RunOutcome.UNCHANGED);
        assertThat(run.getNotificationId()).isNull();
        assertThat(notifications.count()).isZero();
        assertThat(monitors.findById(monitor.getId()).orElseThrow().getLastFingerprint()).isNotNull();
    }

    @Test
    @DisplayName("值變更：偵測到變更、送出通知，api_monitor_run.outcome=CHANGED")
    void valueChange_createsNotificationAndMarksRunChanged() throws Exception {
        ApiMonitor monitor = createMonitor();
        TARGET.enqueue(jsonResponse("{\"status\":\"OK\"}"));
        assertThat(runner.runOnce()).isEqualTo(1); // 基準

        TARGET.enqueue(jsonResponse("{\"status\":\"BAD\"}"));
        forceDue(monitor.getId());
        assertThat(runner.runOnce()).isEqualTo(1);

        ApiMonitorRun run = latestRun(monitor.getId());
        assertThat(run.getOutcome()).isEqualTo(RunOutcome.CHANGED);
        assertThat(run.getHttpStatus()).isEqualTo(200);
        assertThat(run.getNotificationId()).isNotNull();

        List<Notification> all = notifications.findAll();
        assertThat(all).hasSize(1);
        Notification notification = all.get(0);
        assertThat(notification.getId()).isEqualTo(run.getNotificationId());
        assertThat(notification.getPayload())
                .contains("狀態變成 BAD").contains("原本 OK");
    }

    @Test
    @DisplayName("值未變：outcome=UNCHANGED，不發通知")
    void noChange_recordsUnchangedWithoutNotifying() {
        ApiMonitor monitor = createMonitor();
        TARGET.enqueue(jsonResponse("{\"status\":\"OK\"}"));
        assertThat(runner.runOnce()).isEqualTo(1);

        TARGET.enqueue(jsonResponse("{\"status\":\"OK\"}"));
        forceDue(monitor.getId());
        assertThat(runner.runOnce()).isEqualTo(1);

        assertThat(latestRun(monitor.getId()).getOutcome()).isEqualTo(RunOutcome.UNCHANGED);
        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName("沒有到期的監控時是零成本的一輪")
    void idlePoll_doesNothing() {
        assertThat(runner.runOnce()).isZero();
    }

    /**
     * 端到端驗收：凍結時間戳（Docs/plan/13-監控計算欄位設計.md §3，本波次必修的 bug）＋
     * 雙重 MD5 計算欄位（§1 的真實案例）。真的送出 HTTP 請求，用 MockWebServer 的
     * {@link RecordedRequest} 檢查<strong>實際送出去的</strong> URL 與 header——
     * 不是只測 {@code RequestTemplate}/{@code ComputedFieldEvaluator} 的單元行為，而是
     * 證明 {@link ApiMonitorRunner} 把兩者接起來、共用同一個
     * {@code RequestTemplate.Session} 之後，最終真的送出去的請求是自洽的：URL 裡的
     * {@code ts} 與 header 的 {@code sign} 是用同一個瞬間算出來的，換算出的 sign 也跟
     * §1 真實案例同一套演算法算出來的值一致。
     */
    @Test
    @DisplayName("凍結時間戳＋雙重 MD5 計算欄位：URL 的 ts 與 header 的 sign 用同一個瞬間算出且自洽")
    void frozenTimestampAndComputedSign_endToEnd() throws Exception {
        String appsecret = "YWHZ@&mxZge1A@";
        String deviceid = "2b34aabc-6d14-490e-b76a-254097095055";

        ApiMonitor monitor = new ApiMonitor(
                "signed monitor", clientId, TARGET.url("/api").toString() + "?ts={{now.epochSeconds}}", "GET", null,
                null, null, null,
                60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                "{{value.status}}", true, 0, null,
                "[{\"name\":\"sign\",\"input\":\"{{secret.appsecret}}{{now.epochSeconds}}{{secret.deviceid}}\","
                        + "\"steps\":[{\"algorithm\":\"MD5\",\"encoding\":\"HEX_UPPER\"},"
                        + "{\"algorithm\":\"MD5\",\"encoding\":\"HEX_UPPER\"}]}]",
                clock.instant());
        monitor = monitors.save(monitor);
        monitorSecretService.upsert(monitor.getId(), Map.of("appsecret", appsecret, "deviceid", deviceid));
        applyHeaders(monitor, Map.of("sign", "{{computed.sign}}"));
        monitors.save(monitor);

        TARGET.enqueue(jsonResponse("{\"status\":\"OK\"}"));

        assertThat(runner.runOnce()).isEqualTo(1);

        RecordedRequest recorded = TARGET.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        String ts = recorded.getUrl().queryParameter("ts");
        assertThat(ts).isNotNull();
        String actualSign = recorded.getHeaders().get("sign");
        String expectedSign = md5HexUpper(md5HexUpper(appsecret + ts + deviceid));
        assertThat(actualSign)
                .as("header 的 sign 必須是用跟 URL 的 ts 同一個瞬間算出來的（凍結時間戳）")
                .isEqualTo(expectedSign);
    }
}
