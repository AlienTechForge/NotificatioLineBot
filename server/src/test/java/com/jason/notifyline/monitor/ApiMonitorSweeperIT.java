package com.jason.notifyline.monitor;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.monitor.domain.ApiMonitor;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.domain.ApiMonitorRun;
import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.RunOutcome;
import com.jason.notifyline.monitor.domain.SeenItem;
import com.jason.notifyline.monitor.domain.SeenItemId;
import com.jason.notifyline.monitor.domain.SeenItemRepository;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiMonitorSweeper} 的保留期清理，真的 PostgreSQL。
 */
@DisplayName("ApiMonitorSweeper（整合）")
class ApiMonitorSweeperIT extends PostgresIntegrationTest {

    @Autowired
    private ApiMonitorSweeper sweeper;
    @Autowired
    private ApiMonitorRepository monitors;
    @Autowired
    private ApiMonitorRunRepository runs;
    @Autowired
    private SeenItemRepository seenItems;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private MonitorProperties properties;
    @Autowired
    private Clock clock;

    private Long monitorId;

    @BeforeEach
    void setUp() {
        cleanUp();

        Client client = clientRepository.save(new Client(
                "cli_sweeper_test", "sweeper test",
                new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, 1,
                null, Set.of(), null, null, clock.instant()));

        ApiMonitor monitor = new ApiMonitor(
                "sweep target", client.getId(), "https://example.com/api", "GET", null,
                null, null, null,
                60, true, CompareMode.WHOLE_BODY, "[]", null, null,
                "{{value.x}}", true, 0, null, clock.instant());
        monitorId = monitors.save(monitor).getId();
    }

    /** 理由同 {@code ApiMonitorStoreIT}：整個 JVM 共用同一個 Postgres 容器，不能留垃圾給下一個測試類別。 */
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
    }

    private ApiMonitorRun runAt(Instant startedAt) {
        return new ApiMonitorRun(monitorId, startedAt, 10, RunOutcome.UNCHANGED, 200, null, null);
    }

    @Test
    @DisplayName("api_monitor_run：超過保留期的刪除，保留期內的留著")
    void sweepsExpiredRunsOnly() {
        Instant now = clock.instant();
        ApiMonitorRun expired = runs.save(runAt(now.minus(properties.runRetention()).minusSeconds(3600)));
        ApiMonitorRun fresh = runs.save(runAt(now.minusSeconds(3600)));

        sweeper.runOnce();

        assertThat(runs.findById(expired.getId())).isEmpty();
        assertThat(runs.findById(fresh.getId())).isPresent();
    }

    @Test
    @DisplayName("api_monitor_seen_item：超過保留期的刪除，保留期內的留著")
    void sweepsExpiredSeenItemsOnly() {
        Instant now = clock.instant();
        seenItems.save(new SeenItem(monitorId, "expired-key",
                now.minus(properties.seenItemRetention()).minusSeconds(3600)));
        seenItems.save(new SeenItem(monitorId, "fresh-key", now.minusSeconds(3600)));

        sweeper.runOnce();

        assertThat(seenItems.findById(new SeenItemId(monitorId, "expired-key"))).isEmpty();
        assertThat(seenItems.findById(new SeenItemId(monitorId, "fresh-key"))).isPresent();
    }

    @Test
    @DisplayName("沒有過期資料時是零成本的一輪")
    void noExpiredData_doesNothing() {
        Instant now = clock.instant();
        ApiMonitorRun fresh = runs.save(runAt(now.minusSeconds(60)));

        sweeper.runOnce();

        assertThat(runs.findById(fresh.getId())).isPresent();
    }
}
