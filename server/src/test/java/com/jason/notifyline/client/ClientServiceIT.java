package com.jason.notifyline.client;

import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client 領域的整合測試（真的 PostgreSQL）。
 *
 * <p>重點是驗證「一人一把有效金鑰」由 <strong>資料庫約束</strong>保證，
 * 而不是靠應用程式記得先查再插 —— 後者在併發下兩個請求都會成功。
 */
@DisplayName("ClientService（整合）")
class ClientServiceIT extends PostgresIntegrationTest {

    private static final String USER_A = "U0000000000000000000000000000000a";
    private static final String USER_B = "U0000000000000000000000000000000b";

    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private LineUserRepository lineUserRepository;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();
        lineUserRepository.save(new LineUser(USER_A, clock.instant()));
        lineUserRepository.save(new LineUser(USER_B, clock.instant()));
    }

    // ------------------------------------------------------------------ 建立

    @Test
    @DisplayName("建立 USER client：回傳明文 secret，DB 內是密文且可正確解回")
    void create_userClient_secretIsEncryptedAtRestAndDecryptable() {
        ClientService.IssuedClient issued = clientService.create(
                ClientService.CreateClientCommand.forUser("jason", USER_A, 200));

        assertThat(issued.clientId()).startsWith("cli_");
        assertThat(issued.secret()).isNotBlank();
        assertThat(issued.scopes()).containsExactly(Scope.NOTIFY_SELF);

        Client stored = clientRepository.findByClientId(issued.clientId()).orElseThrow();

        // DB 內不得出現明文
        assertThat(new String(stored.getSecretCiphertext()))
                .doesNotContain(issued.secret());
        assertThat(stored.getSecretIv()).hasSize(12);
        assertThat(stored.getSecretKeyVersion()).isEqualTo(1);

        // 但伺服器必須解得回來 —— HMAC 驗簽需要明文
        assertThat(clientService.decryptSecret(stored)).isEqualTo(issued.secret());
    }

    @Test
    @DisplayName("建立 SERVICE client：無綁定使用者，預設只有 notify:owner")
    void create_serviceClient_hasOwnerScopeAndNoBinding() {
        ClientService.IssuedClient issued = clientService.create(
                ClientService.CreateClientCommand.forService("backup-service", 500));

        Client stored = clientRepository.findByClientId(issued.clientId()).orElseThrow();

        assertThat(stored.getBoundLineUserId()).isNull();
        assertThat(stored.isBound()).isFalse();
        assertThat(stored.getScopes()).containsExactly(Scope.NOTIFY_OWNER);
        assertThat(stored.getDailyMessageQuota()).isEqualTo(500);
    }

    @Test
    @DisplayName("建立 OWNER client：擁有四種發送 scope，但不含 notify:raw")
    void create_ownerClient_hasAllSendScopesButNotRaw() {
        ClientService.IssuedClient issued = clientService.create(
                ClientService.CreateClientCommand.forOwner("owner", USER_A));

        assertThat(issued.scopes()).containsExactlyInAnyOrder(
                Scope.NOTIFY_SELF, Scope.NOTIFY_OWNER, Scope.NOTIFY_USER, Scope.NOTIFY_ALL);
        assertThat(issued.scopes()).doesNotContain(Scope.NOTIFY_RAW);
    }

    @Test
    @DisplayName("每次建立的 secret 都不同")
    void create_twice_producesDifferentSecrets() {
        ClientService.IssuedClient a = clientService.create(
                ClientService.CreateClientCommand.forService("svc-a", null));
        ClientService.IssuedClient b = clientService.create(
                ClientService.CreateClientCommand.forService("svc-b", null));

        assertThat(a.secret()).isNotEqualTo(b.secret());
        assertThat(a.clientId()).isNotEqualTo(b.clientId());
    }

    // ------------------------------------------------------- 資料庫層唯一約束

    @Test
    @DisplayName("同一 LINE user 建第二組 ACTIVE client：由資料庫唯一約束擋下")
    void create_secondActiveClientForSameUser_rejectedByDatabase() {
        clientService.create(ClientService.CreateClientCommand.forUser("first", USER_A, null));

        assertThatThrownBy(() ->
                clientService.create(ClientService.CreateClientCommand.forUser("second", USER_A, null)))
                .isInstanceOf(ClientAlreadyExistsException.class)
                .hasMessageContaining(USER_A);
    }

    @Test
    @DisplayName("撤銷舊金鑰後可以再建新的 —— REVOKED 不佔用唯一性")
    void create_afterRevoke_isAllowed() {
        ClientService.IssuedClient first =
                clientService.create(ClientService.CreateClientCommand.forUser("first", USER_A, null));

        clientService.revokeActiveForLineUser(USER_A, "使用者要求重設");

        ClientService.IssuedClient second =
                clientService.create(ClientService.CreateClientCommand.forUser("second", USER_A, null));

        assertThat(second.clientId()).isNotEqualTo(first.clientId());
        assertThat(clientRepository.findByClientId(first.clientId()).orElseThrow().getStatus())
                .isEqualTo(ClientStatus.REVOKED);
        // 舊記錄仍保留供稽核
        assertThat(clientRepository.findByBoundLineUserId(USER_A)).hasSize(2);
    }

    @Test
    @DisplayName("不同使用者各自擁有金鑰，互不影響")
    void create_differentUsers_bothAllowed() {
        clientService.create(ClientService.CreateClientCommand.forUser("a", USER_A, null));
        clientService.create(ClientService.CreateClientCommand.forUser("b", USER_B, null));

        assertThat(clientRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------- 權限防呆

    @Test
    @DisplayName("非 OWNER 不得授予 notify:all / notify:user / notify:raw")
    void create_ownerOnlyScopeWithoutGrant_rejected() {
        for (Scope ownerOnly : Scope.ownerOnly()) {
            ClientService.CreateClientCommand command = new ClientService.CreateClientCommand(
                    "sneaky", null, Set.of(ownerOnly), null, null, false);

            assertThatThrownBy(() -> clientService.create(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ownerOnly.value());
        }
    }

    @Test
    @DisplayName("notify:self 必須有綁定使用者")
    void create_selfScopeWithoutBinding_rejected() {
        ClientService.CreateClientCommand command = new ClientService.CreateClientCommand(
                "unbound", null, Set.of(Scope.NOTIFY_SELF), null, null, false);

        assertThatThrownBy(() -> clientService.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notify:self");
    }

    @Test
    @DisplayName("沒有任何 scope 的 client 不予建立")
    void create_noScopes_rejected() {
        ClientService.CreateClientCommand command = new ClientService.CreateClientCommand(
                "empty", null, Set.of(), null, null, true);

        assertThatThrownBy(() -> clientService.create(command))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- 狀態轉換

    @Test
    @DisplayName("停用後查不到 ACTIVE，但記錄仍在")
    void disable_clientNoLongerActive() {
        ClientService.IssuedClient issued =
                clientService.create(ClientService.CreateClientCommand.forService("svc", null));

        clientService.disable(issued.clientId(), "失控洗版");

        assertThat(clientService.findActive(issued.clientId())).isEmpty();
        assertThat(clientRepository.findByClientId(issued.clientId()).orElseThrow().getStatus())
                .isEqualTo(ClientStatus.DISABLED);
    }

    @Test
    @DisplayName("使用者封鎖 Bot 時連帶停用其金鑰")
    void disableAllForLineUser_disablesBoundClient() {
        ClientService.IssuedClient issued =
                clientService.create(ClientService.CreateClientCommand.forUser("jason", USER_A, null));

        clientService.disableAllForLineUser(USER_A, "unfollow");

        assertThat(clientRepository.findByClientId(issued.clientId()).orElseThrow().getStatus())
                .isEqualTo(ClientStatus.DISABLED);
    }

    @Test
    @DisplayName("操作不存在的 client 會明確失敗")
    void disable_unknownClient_throws() {
        assertThatThrownBy(() -> clientService.disable("cli_doesnotexist00000", "x"))
                .isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    @DisplayName("markUsed 會寫入 last_used_at")
    void markUsed_updatesTimestamp() {
        ClientService.IssuedClient issued =
                clientService.create(ClientService.CreateClientCommand.forService("svc", null));
        assertThat(clientRepository.findByClientId(issued.clientId()).orElseThrow().getLastUsedAt())
                .isNull();

        clientService.markUsed(issued.clientId());

        assertThat(clientRepository.findByClientId(issued.clientId()).orElseThrow().getLastUsedAt())
                .isNotNull();
    }
}
