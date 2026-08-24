package com.jason.notifyline.monitor;

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
import com.jason.notifyline.monitor.domain.SeenItemId;
import com.jason.notifyline.monitor.domain.SeenItemRepository;
import com.jason.notifyline.monitor.parse.ChangeResult;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiMonitorStore} 的交易邊界與防洗版行為，真的 PostgreSQL。
 *
 * <p>直接呼叫 {@code store.claim} / {@code recordSuccess} / {@code recordFailure}，
 * 不經過 {@link ApiMonitorRunner}——HTTP 抓取與訊息組裝不是這裡要驗證的，見
 * {@link ApiMonitorRunnerTest}（純編排、Mockito）與 {@link ApiMonitorRunnerIT}
 * （端到端）。
 */
@DisplayName("ApiMonitorStore（整合）")
class ApiMonitorStoreIT extends PostgresIntegrationTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @DynamicPropertySource
    static void disableRealSchedulers(DynamicPropertyRegistry registry) {
        // 這個測試直接呼叫 store 的方法，不該有背景排程搶著取件或送出真的通知。
        registry.add("app.monitor.enabled", () -> "false");
        registry.add("app.dispatch.enabled", () -> "false");
    }

    @Autowired
    private ApiMonitorStore store;
    @Autowired
    private ApiMonitorRepository monitors;
    @Autowired
    private ApiMonitorRunRepository runs;
    @Autowired
    private SeenItemRepository seenItems;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private ClientService clientService;
    @Autowired
    private LineUserRepository lineUserRepository;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private MonitorProperties properties;
    @Autowired
    private Clock clock;

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
        // target = null 走 client 的預設對象（見 ApiMonitorStore.submit 的說明），
        // 所以測試用的 client 一定要設好預設對象，否則 submit() 會因為沒有收件人失敗。
        client.setDefaultTarget(TargetType.OWNER, null, clock.instant());
        clientRepository.save(client);
        clientId = client.getId();
    }

    /**
     * 也在測試結束後清一次，不只是開始前——{@code PostgresIntegrationTest} 是整個
     * JVM 共用同一個 Postgres 容器，這個類別最後一個測試留下的 client 若還被
     * {@code notification} 參照，會讓下一個測試類別（例如既有的
     * {@code ApiMonitorRepositoryIT}，它自己的 {@code clientRepository.deleteAll()}
     * 沒有先清 notification）在它自己的 {@code @BeforeEach} 就直接炸掉，而且看起來
     * 完全與這裡的改動無關，非常難查。
     */
    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        seenItems.deleteAll();
        runs.deleteAll();
        monitors.deleteAll();
        deliveries.deleteAll();
        notifications.deleteAll();
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();
    }

    // ---------------------------------------------------------------- helpers

    private ApiMonitor saveMonitor(String name, CompareMode mode, String itemPointer, String itemKeyPointer,
                                   int cooldownSeconds, Integer maxPerDay) {
        Instant now = clock.instant();
        ApiMonitor monitor = new ApiMonitor(
                name, clientId, "https://target.example/api", "GET", null,
                null, null, null,
                60, true, mode, "[]", itemPointer, itemKeyPointer,
                "{{value.status}}", true, cooldownSeconds, maxPerDay, now.minusSeconds(5));
        return monitors.save(monitor);
    }

    /** 把 {@code next_run_at} 推到過去，讓下一次 {@code claim} 撿得到它。抄 DeliveryDispatchIT 的 forceDue。 */
    private void forceDue(Long monitorId) {
        ApiMonitor monitor = monitors.findById(monitorId).orElseThrow();
        monitor.lease(clock.instant().minusSeconds(1));
        monitors.save(monitor);
    }

    private ClaimedMonitor claimOne(Long monitorId) {
        forceDue(monitorId);
        return store.claim(50).stream()
                .filter(c -> c.id().equals(monitorId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("monitor " + monitorId + " 沒有被取到"));
    }

    private ApiMonitorRun latestRun(Long monitorId) {
        return runs.findAll().stream()
                .filter(r -> r.getMonitorId().equals(monitorId))
                .max(Comparator.comparing(ApiMonitorRun::getId))
                .orElseThrow(() -> new AssertionError("monitor " + monitorId + " 沒有任何執行紀錄"));
    }

    private static RunAttempt.Success changedAttempt(String value) {
        ChangeResult.Changed changed = new ChangeResult.Changed(
                ("fp-" + value).getBytes(), Map.of("status", value), Map.of("status", "OK"), List.of());
        return new RunAttempt.Success(Instant.now(), 10, 200, changed, "狀態變成 " + value);
    }

    private static RunAttempt.Success unchangedAttempt() {
        ChangeResult.Unchanged unchanged = new ChangeResult.Unchanged(new byte[]{1}, Map.of("status", "OK"), List.of());
        return new RunAttempt.Success(Instant.now(), 10, 200, unchanged, null);
    }

    private static RunAttempt.Failure failureAttempt() {
        return new RunAttempt.Failure(Instant.now(), 10, "TIMEOUT", "request timed out", null);
    }

    /** monitor 的 url 固定是 {@code https://target.example/api}（見 {@link #saveMonitor}），host 因此是 target.example。 */
    private static RunAttempt.Failure sessionExpiryAttempt() {
        return new RunAttempt.Failure(
                Instant.now(), 10, "HTTP_ERROR", "HTTP 401", 401, "target.example");
    }

    // ---------------------------------------------------------------- claim 併發

    @Test
    @DisplayName("兩個工作者同時 claim：不會取到同一筆，且全部監控都被取走恰好一次")
    void claim_concurrentWorkers_neverClaimSameRow() throws Exception {
        List<Long> ids = IntStream.range(0, 10)
                .mapToObj(i -> saveMonitor("m" + i, CompareMode.WHOLE_BODY, null, null, 0, null).getId())
                .toList();
        ids.forEach(this::forceDue);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<List<ClaimedMonitor>> task = () -> store.claim(50);
            List<Future<List<ClaimedMonitor>>> futures = pool.invokeAll(List.of(task, task));

            Set<Long> claimedByA = idsOf(futures.get(0).get());
            Set<Long> claimedByB = idsOf(futures.get(1).get());

            Set<Long> overlap = new HashSet<>(claimedByA);
            overlap.retainAll(claimedByB);
            assertThat(overlap).as("兩個工作者不該取到同一筆").isEmpty();

            Set<Long> unionClaimed = new HashSet<>(claimedByA);
            unionClaimed.addAll(claimedByB);
            assertThat(unionClaimed).as("10 筆到期的監控要被取走恰好一次").containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            pool.shutdown();
        }
    }

    private static Set<Long> idsOf(List<ClaimedMonitor> claimed) {
        return claimed.stream().map(ClaimedMonitor::id).collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------- 冷卻

    @Test
    @DisplayName("冷卻中：outcome=SKIPPED，fingerprint 與 notified_count 都不變")
    void recordSuccess_cooldownActive_skipsAndKeepsFingerprintUnchanged() {
        ApiMonitor monitor = saveMonitor("cooldown", CompareMode.WHOLE_BODY, null, null, 300, null);
        // 先換日再標記通知，跟 ApiMonitorStore.tryNotifyChange 的呼叫順序一致——
        // 否則等一下 recordSuccess 內部第一次呼叫 rolloverNotifiedDayIfNeeded 時
        // 會把這裡剛設的 notifiedCount 當成「換日」重置掉，測試會誤判成 Store 有 bug。
        monitor.rolloverNotifiedDayIfNeeded(LocalDate.now(TAIPEI));
        monitor.markNotified(clock.instant().minusSeconds(10)); // 10 秒前才通知過，冷卻 300 秒還在生效
        monitors.save(monitor);
        long notificationsBefore = notifications.count();

        ClaimedMonitor claimed = claimOne(monitor.getId());
        store.recordSuccess(claimed, changedAttempt("v1"));

        assertThat(latestRun(monitor.getId()).getOutcome()).isEqualTo(RunOutcome.SKIPPED);
        ApiMonitor reloaded = monitors.findById(monitor.getId()).orElseThrow();
        assertThat(reloaded.getLastFingerprint()).as("冷卻中不可更新 fingerprint").isNull();
        assertThat(reloaded.getNotifiedCount()).isEqualTo(1); // 還是 markNotified 那一次，沒有再加
        assertThat(notifications.count()).as("不該多送出任何通知").isEqualTo(notificationsBefore);
    }

    // ---------------------------------------------------------------- 每日上限

    @Test
    @DisplayName("已達每日上限：outcome=SKIPPED，fingerprint 不變；換日後 rollover 歸零並恢復通知")
    void recordSuccess_dailyCap_reachedThenRolloverResets() {
        ApiMonitor monitor = saveMonitor("daily-cap", CompareMode.WHOLE_BODY, null, null, 0, 1);

        ClaimedMonitor first = claimOne(monitor.getId());
        store.recordSuccess(first, changedAttempt("v1"));
        assertThat(latestRun(monitor.getId()).getOutcome()).isEqualTo(RunOutcome.CHANGED);
        byte[] fingerprintAfterFirst = monitors.findById(monitor.getId()).orElseThrow().getLastFingerprint();
        assertThat(fingerprintAfterFirst).isNotNull();

        ClaimedMonitor second = claimOne(monitor.getId());
        store.recordSuccess(second, changedAttempt("v2"));
        assertThat(latestRun(monitor.getId()).getOutcome()).isEqualTo(RunOutcome.SKIPPED);
        ApiMonitor afterSecond = monitors.findById(monitor.getId()).orElseThrow();
        assertThat(afterSecond.getLastFingerprint()).as("達每日上限不可更新 fingerprint")
                .isEqualTo(fingerprintAfterFirst);
        assertThat(afterSecond.getNotifiedCount()).isEqualTo(1);

        // 模擬換日：把 notified_day 直接改成昨天，下一次 recordSuccess 換日歸零。
        ApiMonitor beforeRollover = monitors.findById(monitor.getId()).orElseThrow();
        beforeRollover.rolloverNotifiedDayIfNeeded(LocalDate.now(TAIPEI).minusDays(1));
        monitors.save(beforeRollover);

        ClaimedMonitor third = claimOne(monitor.getId());
        store.recordSuccess(third, changedAttempt("v3"));
        assertThat(latestRun(monitor.getId()).getOutcome()).as("換日後上限重置，應恢復通知")
                .isEqualTo(RunOutcome.CHANGED);
        ApiMonitor afterRollover = monitors.findById(monitor.getId()).orElseThrow();
        assertThat(afterRollover.getNotifiedCount()).isEqualTo(1);
        assertThat(afterRollover.getNotifiedDay()).isEqualTo(LocalDate.now(TAIPEI));
    }

    // ---------------------------------------------------------------- 失敗通知 + 恢復

    @Test
    @DisplayName("連續失敗只通知一次；下次成功時補發恢復通知；兩則通知的 id 都記在各自的 run 列上（修正 #3）")
    void recordFailure_notifiesOnceAcrossFailures_recoveryOnNextSuccess() {
        ApiMonitor monitor = saveMonitor("flaky", CompareMode.WHOLE_BODY, null, null, 0, null);
        int threshold = properties.failureNotifyThreshold();

        for (int i = 0; i < threshold; i++) {
            store.recordFailure(claimOne(monitor.getId()), failureAttempt());
        }
        assertThat(monitors.findById(monitor.getId()).orElseThrow().isFailureNotified()).isTrue();
        long afterThresholdCount = notifications.count();
        assertThat(afterThresholdCount).isEqualTo(1);
        // 修正 #3：達門檻那一輪的 FAILED run 要記下失敗通知的 id，不再是 null。
        ApiMonitorRun failureRun = latestRun(monitor.getId());
        assertThat(failureRun.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(failureRun.getNotificationId()).as("失敗通知的 id 要記在觸發它的那一列 run 上").isNotNull();

        // 再失敗幾次，不該再通知，這幾列 run 的 notification_id 也理當是 null
        store.recordFailure(claimOne(monitor.getId()), failureAttempt());
        store.recordFailure(claimOne(monitor.getId()), failureAttempt());
        assertThat(notifications.count()).as("同一次故障只通知一次").isEqualTo(afterThresholdCount);
        assertThat(latestRun(monitor.getId()).getNotificationId()).isNull();

        // 成功：failure_notified 歸零，且補發一則恢復通知
        store.recordSuccess(claimOne(monitor.getId()), unchangedAttempt());
        ApiMonitor recovered = monitors.findById(monitor.getId()).orElseThrow();
        assertThat(recovered.isFailureNotified()).isFalse();
        assertThat(recovered.getConsecutiveFailures()).isZero();
        assertThat(notifications.count()).as("失敗通知 + 恢復通知，總共兩則").isEqualTo(afterThresholdCount + 1);
        // 修正 #3：這一輪 outcome=UNCHANGED（沒有變更通知搶走欄位），恢復通知的 id 記在這裡。
        ApiMonitorRun recoveryRun = latestRun(monitor.getId());
        assertThat(recoveryRun.getOutcome()).isEqualTo(RunOutcome.UNCHANGED);
        assertThat(recoveryRun.getNotificationId()).as("恢復通知的 id 要記在補發它的那一列 run 上").isNotNull();
        assertThat(recoveryRun.getNotificationId()).isNotEqualTo(failureRun.getNotificationId());
    }

    @Test
    @DisplayName("失敗次數未達門檻前不通知，run 列的 notification_id 維持 null")
    void recordFailure_belowThreshold_doesNotNotify() {
        ApiMonitor monitor = saveMonitor("still-ok", CompareMode.WHOLE_BODY, null, null, 0, null);

        store.recordFailure(claimOne(monitor.getId()), failureAttempt());

        assertThat(monitors.findById(monitor.getId()).orElseThrow().isFailureNotified()).isFalse();
        assertThat(notifications.count()).isZero();
        ApiMonitorRun run = latestRun(monitor.getId());
        assertThat(run.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(run.getNotificationId()).isNull();
    }

    // ---------------------------------------------------------------- 登入過期偵測（W6）

    @Test
    @DisplayName("連續 401 達門檻：只發一則登入過期通知（不是一般失敗通知），且不會每輪重複")
    void recordFailure_consecutive401_notifiesExpiryOnceNotPerPoll() {
        ApiMonitor monitor = saveMonitor("needs-login", CompareMode.WHOLE_BODY, null, null, 0, null);
        int threshold = properties.failureNotifyThreshold();
        long notificationsBefore = notifications.count();

        for (int i = 0; i < threshold; i++) {
            store.recordFailure(claimOne(monitor.getId()), sessionExpiryAttempt());
        }

        assertThat(notifications.count()).as("達門檻只發一則").isEqualTo(notificationsBefore + 1);
        assertThat(monitors.findById(monitor.getId()).orElseThrow().isFailureNotified()).isTrue();

        var sent = notifications.findAll().stream()
                .max(java.util.Comparator.comparing(com.jason.notifyline.notification.domain.Notification::getCreatedAt))
                .orElseThrow();
        assertThat(sent.getPayload()).contains("target.example").contains("登入已過期");

        // 再連續失敗幾輪（同樣是 401），同一次故障期間不該再通知。
        store.recordFailure(claimOne(monitor.getId()), sessionExpiryAttempt());
        store.recordFailure(claimOne(monitor.getId()), sessionExpiryAttempt());
        assertThat(notifications.count()).as("同一次故障只通知一次，不是一輪一則")
                .isEqualTo(notificationsBefore + 1);
    }

    @Test
    @DisplayName("一般失敗（非 401/403）達門檻：走既有的通用失敗通知，不是登入過期訊息")
    void recordFailure_nonSessionFailure_usesGenericFailureMessage() {
        ApiMonitor monitor = saveMonitor("generic-failure", CompareMode.WHOLE_BODY, null, null, 0, null);
        int threshold = properties.failureNotifyThreshold();

        for (int i = 0; i < threshold; i++) {
            store.recordFailure(claimOne(monitor.getId()), failureAttempt());
        }

        var sent = notifications.findAll().stream()
                .max(java.util.Comparator.comparing(com.jason.notifyline.notification.domain.Notification::getCreatedAt))
                .orElseThrow();
        assertThat(sent.getPayload()).doesNotContain("登入已過期").contains("連續失敗");
    }

    // ---------------------------------------------------------------- NEW_ITEMS 首次執行

    @Test
    @DisplayName("NEW_ITEMS 首次執行：seen key 整批寫入，不發通知")
    void recordSuccess_newItemsFirstRun_persistsSeenKeys_noNotification() {
        ApiMonitor monitor = saveMonitor("feed", CompareMode.NEW_ITEMS, "/items", "/id", 0, null);
        ClaimedMonitor claimed = claimOne(monitor.getId());
        assertThat(claimed.firstRun()).as("從未執行過，claim() 讀到的 firstRun 應為 true").isTrue();

        List<ChangeResult.NewItem> firstRunItems = List.of(
                new ChangeResult.NewItem("k1", Map.of()), new ChangeResult.NewItem("k2", Map.of()));
        ChangeResult.Unchanged unchanged = new ChangeResult.Unchanged(null, Map.of(), firstRunItems);
        long notificationsBefore = notifications.count();

        store.recordSuccess(claimed, new RunAttempt.Success(Instant.now(), 5, 200, unchanged, null));

        assertThat(notifications.count()).as("首次執行不通知").isEqualTo(notificationsBefore);
        assertThat(latestRun(monitor.getId()).getOutcome()).isEqualTo(RunOutcome.UNCHANGED);
        assertThat(seenItems.findById(new SeenItemId(monitor.getId(), "k1"))).isPresent();
        assertThat(seenItems.findById(new SeenItemId(monitor.getId(), "k2"))).isPresent();
    }

    @Test
    @DisplayName("NEW_ITEMS 之後偵測到新項目：通知並寫入 seen_item")
    void recordSuccess_newItemsSubsequentRun_notifiesAndPersistsNewKeys() {
        ApiMonitor monitor = saveMonitor("feed2", CompareMode.NEW_ITEMS, "/items", "/id", 0, null);
        // 先跑一次首次執行，讓 last_run_at 非 null
        store.recordSuccess(claimOne(monitor.getId()),
                new RunAttempt.Success(Instant.now(), 5, 200,
                        new ChangeResult.Unchanged(null, Map.of(), List.of()), null));

        ClaimedMonitor claimed = claimOne(monitor.getId());
        assertThat(claimed.firstRun()).isFalse();

        List<ChangeResult.NewItem> newItems = List.of(new ChangeResult.NewItem("k3", Map.of("title", "hello")));
        ChangeResult.Changed changed = new ChangeResult.Changed(null, Map.of(), Map.of(), newItems);
        long notificationsBefore = notifications.count();

        store.recordSuccess(claimed, new RunAttempt.Success(Instant.now(), 5, 200, changed, "hello"));

        assertThat(notifications.count()).isEqualTo(notificationsBefore + 1);
        assertThat(latestRun(monitor.getId()).getOutcome()).isEqualTo(RunOutcome.CHANGED);
        assertThat(seenItems.findById(new SeenItemId(monitor.getId(), "k3"))).isPresent();
    }
}
