package com.jason.notifyline.monitor.domain;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 監控 domain 的整合測試（真的 PostgreSQL）。
 *
 * <p>重點驗證兩件 {@code ddl-auto: validate} 檢查不到的事：schema 驗證只比對欄位
 * 型別/nullable，不會真的下 SQL，所以下面兩件事必須靠實際 save / find 才能確認：
 * <ul>
 *   <li>{@link SeenItem} 用 record 當 {@code @EmbeddedId} 能不能真的完成
 *       save / findById 的往返，以及 {@code ON DELETE CASCADE} 是否生效</li>
 *   <li>{@link ApiMonitorRepository#lockDue} 的查詢條件（{@code enabled AND next_run_at <= now}）
 *       是否真的照 {@code idx_api_monitor_due} 的邏輯篩選</li>
 * </ul>
 *
 * <p>本波次 {@link ApiMonitor} 刻意不提供狀態轉換方法（見該類別註解），所以這裡用
 * 建構子的 {@code now} 參數直接控制 {@code next_run_at}，不繞道新增測試專用的
 * mutation 方法。
 */
@DisplayName("監控 domain（整合）")
class ApiMonitorRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private ApiMonitorRepository monitorRepository;
    @Autowired
    private ApiMonitorRunRepository runRepository;
    @Autowired
    private SeenItemRepository seenItemRepository;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private Clock clock;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long clientId;

    @BeforeEach
    void setUp() {
        seenItemRepository.deleteAll();
        runRepository.deleteAll();
        monitorRepository.deleteAll();
        clientRepository.deleteAll();

        Client client = clientRepository.save(new Client(
                "cli_monitor_test", "monitor test",
                new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, 1,
                null, Set.of(), null, null, clock.instant()));
        clientId = client.getId();
    }

    /** {@code nextRunAt} 是建構子的 {@code now} 參數，直接決定這筆監控是否到期。 */
    private ApiMonitor newMonitor(Instant nextRunAt, boolean enabled) {
        ApiMonitor monitor = new ApiMonitor(
                "test monitor", clientId, "https://example.com/api", "GET", null,
                null, null, null,
                60, enabled, CompareMode.WHOLE_BODY, "[]", null, null,
                "{{value.status}}", true, 0, null, nextRunAt);
        return monitorRepository.save(monitor);
    }

    /**
     * {@code @Lock(PESSIMISTIC_WRITE)} 的 {@code SELECT ... FOR UPDATE} 一定要在交易內執行
     * ——直接呼叫 repository 方法不會自動開交易（那是留給呼叫端，例如未來的
     * {@code ApiMonitorStore}，用 {@code @Transactional} 包住取件 + 上租約）。
     * 這裡用 {@code TransactionTemplate} 模擬那層交易邊界。
     */
    private List<ApiMonitor> lockDue(Instant now) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> monitorRepository.lockDue(now, Limit.of(10)));
    }

    // ---------------------------------------------------------------- ApiMonitor

    @Test
    @DisplayName("save / findById 往返：JSONB 與 byte[] 欄位都正確保存")
    void saveAndReload_roundTripsJsonAndBinaryColumns() {
        ApiMonitor monitor = new ApiMonitor(
                "monitor A", clientId, "https://example.com/a", "POST", "{\"q\":1}",
                new byte[]{9, 9, 9}, new byte[]{8, 8, 8}, 1,
                60, true, CompareMode.EXTRACTED,
                "[{\"name\":\"status\",\"pointer\":\"/status\"}]", null, null,
                "{{value.status}}", true, 30, 10, clock.instant());

        Long id = monitorRepository.save(monitor).getId();

        ApiMonitor reloaded = monitorRepository.findById(id).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("monitor A");
        assertThat(reloaded.getMethod()).isEqualTo("POST");
        assertThat(reloaded.getRequestBody()).isEqualTo("{\"q\":1}");
        assertThat(reloaded.getHeadersCiphertext()).containsExactly(9, 9, 9);
        assertThat(reloaded.getHeadersIv()).containsExactly(8, 8, 8);
        assertThat(reloaded.getCompareMode()).isEqualTo(CompareMode.EXTRACTED);
        assertThat(reloaded.getExtractRules()).contains("\"status\"");
        assertThat(reloaded.getMaxNotificationsPerDay()).isEqualTo(10);
    }

    @Test
    @DisplayName("lockDue：只取 enabled 且到期的，未到期與已停用的都不取")
    void lockDue_onlyReturnsEnabledAndDueMonitors() {
        Instant now = clock.instant();
        ApiMonitor due = newMonitor(now.minusSeconds(5), true);
        newMonitor(now.plusSeconds(3600), true); // 還沒到期
        newMonitor(now.minusSeconds(5), false);  // 已停用

        List<ApiMonitor> claimed = lockDue(now);

        assertThat(claimed).extracting(ApiMonitor::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("lockDue：多筆到期時依 next_run_at 由舊到新排序")
    void lockDue_ordersByNextRunAtAscending() {
        Instant now = clock.instant();
        ApiMonitor newer = newMonitor(now.minusSeconds(5), true);
        ApiMonitor older = newMonitor(now.minusSeconds(50), true);

        List<ApiMonitor> claimed = lockDue(now);

        assertThat(claimed).extracting(ApiMonitor::getId)
                .containsExactly(older.getId(), newer.getId());
    }

    // ------------------------------------------------------------- ApiMonitorRun

    @Test
    @DisplayName("ApiMonitorRun：save / findById 往返，nullable 欄位可以是 null")
    void runEntity_roundTrips_withNullableFields() {
        ApiMonitor monitor = newMonitor(clock.instant(), true);
        UUID notificationId = UUID.randomUUID();

        ApiMonitorRun run = new ApiMonitorRun(
                monitor.getId(), clock.instant(), 120, RunOutcome.CHANGED, 200,
                null, notificationId);
        Long runId = runRepository.save(run).getId();

        ApiMonitorRun reloaded = runRepository.findById(runId).orElseThrow();
        assertThat(reloaded.getMonitorId()).isEqualTo(monitor.getId());
        assertThat(reloaded.getOutcome()).isEqualTo(RunOutcome.CHANGED);
        assertThat(reloaded.getHttpStatus()).isEqualTo(200);
        assertThat(reloaded.getNotificationId()).isEqualTo(notificationId);
        assertThat(reloaded.getErrorMessage()).isNull();

        ApiMonitorRun failedRun = new ApiMonitorRun(
                monitor.getId(), clock.instant(), null, RunOutcome.FAILED, null,
                "TIMEOUT (12 chars)", null);
        ApiMonitorRun reloadedFailed = runRepository.findById(runRepository.save(failedRun).getId())
                .orElseThrow();
        assertThat(reloadedFailed.getDurationMs()).isNull();
        assertThat(reloadedFailed.getHttpStatus()).isNull();
        assertThat(reloadedFailed.getNotificationId()).isNull();
    }

    // ----------------------------------------------------------------- SeenItem

    @Test
    @DisplayName("SeenItem：複合主鍵（@EmbeddedId record）save / findById 往返")
    void seenItem_compositeKey_roundTrips() {
        ApiMonitor monitor = newMonitor(clock.instant(), true);
        Instant firstSeen = clock.instant();

        seenItemRepository.save(new SeenItem(monitor.getId(), "item-1", firstSeen));

        SeenItemId id = new SeenItemId(monitor.getId(), "item-1");
        SeenItem reloaded = seenItemRepository.findById(id).orElseThrow();

        assertThat(reloaded.getMonitorId()).isEqualTo(monitor.getId());
        assertThat(reloaded.getItemKey()).isEqualTo("item-1");
        // TIMESTAMPTZ 只有微秒精度，PostgreSQL 會四捨五入 Java 的奈秒值 —— 不能用
        // isEqualTo 比對到奈秒，1ms 容差足以確認「是同一個時間點」而不是真的丟失資訊。
        assertThat(reloaded.getFirstSeenAt()).isCloseTo(firstSeen, within(1, ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("SeenItem：item_key 的唯一範圍是單一 monitor，不同 monitor 可用同一把鍵")
    void seenItem_keyIsScopedPerMonitor() {
        ApiMonitor monitorA = newMonitor(clock.instant(), true);
        ApiMonitor monitorB = newMonitor(clock.instant(), true);

        seenItemRepository.save(new SeenItem(monitorA.getId(), "same-key", clock.instant()));
        seenItemRepository.save(new SeenItem(monitorB.getId(), "same-key", clock.instant()));

        assertThat(seenItemRepository.findById(new SeenItemId(monitorA.getId(), "same-key")))
                .isPresent();
        assertThat(seenItemRepository.findById(new SeenItemId(monitorB.getId(), "same-key")))
                .isPresent();
    }

    @Test
    @DisplayName("刪除 api_monitor 會連帶刪除它的 seen_item（ON DELETE CASCADE）")
    void deletingMonitor_cascadesToSeenItems() {
        ApiMonitor monitor = newMonitor(clock.instant(), true);
        SeenItemId id = new SeenItemId(monitor.getId(), "item-1");
        seenItemRepository.save(new SeenItem(monitor.getId(), "item-1", clock.instant()));

        monitorRepository.deleteById(monitor.getId());

        assertThat(seenItemRepository.findById(id)).isEmpty();
    }
}
