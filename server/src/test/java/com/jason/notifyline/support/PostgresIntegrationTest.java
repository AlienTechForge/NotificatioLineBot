package com.jason.notifyline.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 整合測試共用基底：真的 PostgreSQL + 完整 Flyway migration。
 *
 * <p>用 Testcontainers 而不是 H2 —— 專案用到 {@code TEXT[]}、{@code JSONB}、
 * {@code INET}、partial index 與 {@code FOR UPDATE SKIP LOCKED}，這些在替代資料庫上
 * 行為不同甚至不存在。「本機測試都過、上線才爆」多半就是這樣來的。
 *
 * <p><strong>刻意不用 {@code @Testcontainers} + {@code @Container}</strong>：
 * 那組註解會以「測試類別」為單位管理生命週期。當容器宣告在共用的抽象基底類別上時，
 * 第一個測試類別跑完就會把它停掉，後續類別拿到的是已死的容器，症狀是
 * {@code HikariPool - Connection is not available (total=0)} —— 看起來像連線池設定問題，
 * 其實是容器已經關了。
 *
 * <p>改用 singleton container：整個 JVM 只啟動一次，由 Testcontainers 的 Ryuk
 * 在 JVM 結束時回收。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("notifyline")
                    .withUsername("notifyline")
                    .withPassword("test-only");

    static {
        POSTGRES.start();
    }
}
