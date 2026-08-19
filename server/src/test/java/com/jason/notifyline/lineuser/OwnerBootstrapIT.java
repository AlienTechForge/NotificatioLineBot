package com.jason.notifyline.lineuser;

import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 啟動時把設定中的 LINE user 標記為 owner。
 *
 * <p>沒有任何 owner 的話 {@code target: OWNER} 會解析出空收件人，
 * 服務類的通知（最常見的用途）就完全送不出去。
 *
 * <p>放在應用啟動而不是 Flyway migration：migration 應該與環境無關，
 * 而 {@code APP_OWNER_LINE_USER_ID} 是環境設定。寫進 migration 會讓它只在
 * 第一次建置時生效，日後改環境變數不會反映。
 */
@DisplayName("OwnerBootstrap（整合）")
class OwnerBootstrapIT extends PostgresIntegrationTest {

    // 必須是合法的 LINE user id 格式（U + 32 個十六進位字元），
    // 否則會被 OwnerBootstrap 的格式檢查擋掉 —— 那正是它該做的事。
    private static final String USER_A = "U000000000000000000000000000000a1";
    private static final String USER_B = "U000000000000000000000000000000b2";

    @Autowired
    private OwnerBootstrap ownerBootstrap;
    @Autowired
    private LineUserRepository lineUserRepository;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        lineUserRepository.deleteAll();
    }

    @Test
    @DisplayName("使用者已存在：標記為 owner，不動其他欄位")
    void existingUser_isMarkedOwner() {
        LineUser user = new LineUser(USER_A, clock.instant());
        user.applyProfile("Jason", null, null, "zh-TW", clock.instant());
        lineUserRepository.save(user);

        ownerBootstrap.apply(USER_A);

        LineUser after = lineUserRepository.findById(USER_A).orElseThrow();
        assertThat(after.isOwner()).isTrue();
        assertThat(after.isActive()).isTrue();
        // 既有的 profile 不該被洗掉
        assertThat(after.getDisplayName()).isEqualTo("Jason");
    }

    @Test
    @DisplayName("使用者不存在：建立並標記 owner，讓 target=OWNER 立刻可用")
    void missingUser_isCreatedAsOwner() {
        ownerBootstrap.apply(USER_A);

        assertThat(lineUserRepository.findById(USER_A)).hasValueSatisfying(user -> {
            assertThat(user.isOwner()).isTrue();
            assertThat(user.isActive()).isTrue();
        });
    }

    @Test
    @DisplayName("重複執行是冪等的 —— 每次啟動都會跑")
    void repeatedRuns_areIdempotent() {
        ownerBootstrap.apply(USER_A);
        ownerBootstrap.apply(USER_A);
        ownerBootstrap.apply(USER_A);

        assertThat(lineUserRepository.count()).isEqualTo(1);
        assertThat(lineUserRepository.findById(USER_A).orElseThrow().isOwner()).isTrue();
    }

    @Test
    @DisplayName("支援多個 owner，以逗號分隔")
    void multipleOwners_allMarked() {
        ownerBootstrap.applyAll(USER_A + "," + USER_B);

        assertThat(lineUserRepository.findByOwnerTrueAndStatus(LineUserStatus.ACTIVE))
                .extracting(LineUser::getLineUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B);
    }

    @Test
    @DisplayName("設定為空：什麼都不做，不會建立空白使用者")
    void blankConfig_doesNothing() {
        ownerBootstrap.applyAll("");
        ownerBootstrap.applyAll(null);
        ownerBootstrap.applyAll("  ,  ,");

        assertThat(lineUserRepository.count()).isZero();
    }

    @Test
    @DisplayName("格式不合法的 user id 被略過，不影響其他有效的")
    void invalidIds_areSkipped() {
        ownerBootstrap.applyAll("not-a-line-id," + USER_A + ",U123");

        assertThat(lineUserRepository.findAll())
                .extracting(LineUser::getLineUserId)
                .containsExactly(USER_A);
    }

    @Test
    @DisplayName("已被封鎖的使用者：標記 owner 但「不」強制改回 ACTIVE")
    void blockedUser_keepsBlockedStatus() {
        LineUser user = new LineUser(USER_A, clock.instant());
        user.block(clock.instant());
        lineUserRepository.save(user);

        ownerBootstrap.apply(USER_A);

        LineUser after = lineUserRepository.findById(USER_A).orElseThrow();
        assertThat(after.isOwner()).isTrue();
        // 封鎖是使用者的意願，設定檔不該覆寫它。
        // 對已封鎖者發送會被 LINE 靜默丟棄，強行改回 ACTIVE 只會讓名單說謊。
        assertThat(after.isActive()).isFalse();
    }
}
