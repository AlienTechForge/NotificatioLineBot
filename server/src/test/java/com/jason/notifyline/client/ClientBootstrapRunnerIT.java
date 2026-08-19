package com.jason.notifyline.client;

import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bootstrap 指令。
 *
 * <p>這是雞生蛋問題的解法：還沒有 client 就無法呼叫 API。所以這條路徑必須可靠，
 * 而且要在「使用者還沒加 Bot 好友」的情況下也能建立 OWNER 憑證。
 */
@DisplayName("ClientBootstrapRunner（整合）")
class ClientBootstrapRunnerIT extends PostgresIntegrationTest {

    private static final String OWNER_ID = "U0000000000000000000000000000000f";

    @Autowired
    private ClientBootstrapRunner runner;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private LineUserRepository lineUserRepository;

    @BeforeEach
    void setUp() {
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();
    }

    private void run(String... args) {
        runner.run(new DefaultApplicationArguments(args));
    }

    @Test
    @DisplayName("沒有 --create-client 時完全不動作（正常啟動不該建立任何東西）")
    void run_withoutFlag_doesNothing() {
        run("--spring.profiles.active=test");

        assertThat(clientRepository.count()).isZero();
        assertThat(lineUserRepository.count()).isZero();
    }

    @Test
    @DisplayName("--owner：使用者不存在時自動建立並標記 is_owner，讓 target=OWNER 立刻可用")
    void run_owner_createsLineUserAndMarksOwner() {
        run("--create-client", "--name=owner", "--owner", "--line-user-id=" + OWNER_ID);

        assertThat(lineUserRepository.findById(OWNER_ID)).hasValueSatisfying(user -> {
            assertThat(user.isOwner()).isTrue();
            assertThat(user.isActive()).isTrue();
        });

        Client client = clientRepository.findAll().getFirst();
        assertThat(client.getBoundLineUserId()).isEqualTo(OWNER_ID);
        assertThat(client.getScopes()).containsExactlyInAnyOrder(
                Scope.NOTIFY_SELF, Scope.NOTIFY_OWNER, Scope.NOTIFY_USER, Scope.NOTIFY_ALL);
    }

    @Test
    @DisplayName("--service：不需要 line-user-id，只拿到 notify:owner")
    void run_service_createsUnboundClient() {
        run("--create-client", "--name=backup-service", "--service", "--daily-quota=500");

        Client client = clientRepository.findAll().getFirst();
        assertThat(client.getBoundLineUserId()).isNull();
        assertThat(client.getScopes()).containsExactly(Scope.NOTIFY_OWNER);
        assertThat(client.getDailyMessageQuota()).isEqualTo(500);
        assertThat(lineUserRepository.count()).isZero();
    }

    @Test
    @DisplayName("預設模式：未指定 --scopes 時給 notify:self")
    void run_default_grantsSelfScope() {
        run("--create-client", "--name=jason", "--line-user-id=" + OWNER_ID);

        assertThat(clientRepository.findAll().getFirst().getScopes())
                .containsExactly(Scope.NOTIFY_SELF);
        // 一般使用者不會被標成 owner
        assertThat(lineUserRepository.findById(OWNER_ID).orElseThrow().isOwner()).isFalse();
    }

    @Test
    @DisplayName("--scopes 可指定多個，以逗號分隔")
    void run_explicitScopes_areParsed() {
        run("--create-client", "--name=multi", "--line-user-id=" + OWNER_ID,
                "--scopes=notify:self,notify:owner");

        assertThat(clientRepository.findAll().getFirst().getScopes())
                .containsExactlyInAnyOrder(Scope.NOTIFY_SELF, Scope.NOTIFY_OWNER);
    }

    @Test
    @DisplayName("指定 OWNER-only scope 但沒有 --grant-owner-scopes：拒絕")
    void run_ownerOnlyScopeWithoutGrant_rejected() {
        assertThatThrownBy(() -> run("--create-client", "--name=sneaky",
                "--line-user-id=" + OWNER_ID, "--scopes=notify:all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notify:all");

        assertThat(clientRepository.count()).isZero();
    }

    @Test
    @DisplayName("--grant-owner-scopes 才能授予 notify:raw 這類高風險權限")
    void run_withGrantFlag_allowsOwnerOnlyScopes() {
        assertThatCode(() -> run("--create-client", "--name=trusted",
                "--line-user-id=" + OWNER_ID,
                "--scopes=notify:self,notify:raw", "--grant-owner-scopes"))
                .doesNotThrowAnyException();

        assertThat(clientRepository.findAll().getFirst().getScopes())
                .contains(Scope.NOTIFY_RAW);
    }

    // ---------------------------------------------------------------- 參數防呆

    @Test
    @DisplayName("缺 --name：明確失敗")
    void run_missingName_throws() {
        assertThatThrownBy(() -> run("--create-client", "--service"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("--owner 缺 --line-user-id：訊息要指出怎麼取得")
    void run_ownerWithoutUserId_throwsWithHint() {
        assertThatThrownBy(() -> run("--create-client", "--name=owner", "--owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("我的ID");
    }

    @Test
    @DisplayName("--owner 與 --service 同時指定：拒絕")
    void run_ownerAndService_throws() {
        assertThatThrownBy(() ->
                run("--create-client", "--name=x", "--owner", "--service", "--line-user-id=" + OWNER_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("未知的 scope 名稱：明確失敗而不是靜默忽略")
    void run_unknownScope_throws() {
        assertThatThrownBy(() ->
                run("--create-client", "--name=x", "--line-user-id=" + OWNER_ID,
                        "--scopes=notify:everything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notify:everything");
    }

    @Test
    @DisplayName("--scopes 與 --service/--owner 併用：明確拒絕而非靜默忽略")
    void run_scopesWithPresetMode_rejected() {
        assertThatThrownBy(() ->
                run("--create-client", "--name=x", "--service", "--scopes=notify:owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--scopes");

        assertThatThrownBy(() ->
                run("--create-client", "--name=x", "--owner",
                        "--line-user-id=" + OWNER_ID, "--scopes=notify:all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--scopes");
    }

    @Test
    @DisplayName("重複對同一使用者建立：由資料庫唯一約束擋下")
    void run_duplicateForSameUser_rejected() {
        run("--create-client", "--name=first", "--line-user-id=" + OWNER_ID);

        assertThatThrownBy(() -> run("--create-client", "--name=second", "--line-user-id=" + OWNER_ID))
                .isInstanceOf(ClientAlreadyExistsException.class);
    }
}
