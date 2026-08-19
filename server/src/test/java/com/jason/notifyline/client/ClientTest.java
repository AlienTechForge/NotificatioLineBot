package com.jason.notifyline.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Client / Scope / ClientStatus")
class ClientTest {

    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-18T01:00:00Z");
    private static final String USER_ID = "U0000000000000000000000000000000a";

    private static Client newClient(String boundUserId, Set<Scope> scopes) {
        return new Client("cli_test0000000000000", "test",
                new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, 1,
                boundUserId, scopes, null, null, T0);
    }

    // ---------------------------------------------------------------- Client

    @Test
    @DisplayName("新建立的 client 是 ACTIVE 且尚未使用過")
    void newClient_isActive() {
        Client client = newClient(USER_ID, Set.of(Scope.NOTIFY_SELF));

        assertThat(client.getStatus()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(client.isUsable()).isTrue();
        assertThat(client.getLastUsedAt()).isNull();
        assertThat(client.getCreatedAt()).isEqualTo(T0);
        assertThat(client.getUpdatedAt()).isEqualTo(T0);
        assertThat(client.getName()).isEqualTo("test");
        assertThat(client.getSecretKeyVersion()).isEqualTo(1);
        assertThat(client.getRateLimitPerMin()).isNull();
        assertThat(client.getDailyMessageQuota()).isNull();
        assertThat(client.getId()).isNull();
    }

    @Test
    @DisplayName("停用與撤銷都讓 client 不可用，但狀態語意不同")
    void disableAndRevoke_bothUnusable() {
        Client disabled = newClient(USER_ID, Set.of(Scope.NOTIFY_SELF));
        disabled.disable(T1);
        assertThat(disabled.getStatus()).isEqualTo(ClientStatus.DISABLED);
        assertThat(disabled.isUsable()).isFalse();
        assertThat(disabled.getUpdatedAt()).isEqualTo(T1);

        Client revoked = newClient(USER_ID, Set.of(Scope.NOTIFY_SELF));
        revoked.revoke(T1);
        assertThat(revoked.getStatus()).isEqualTo(ClientStatus.REVOKED);
        assertThat(revoked.isUsable()).isFalse();
    }

    @Test
    @DisplayName("markUsed 記錄最後使用時間但不動 updated_at")
    void markUsed_onlyTouchesLastUsedAt() {
        Client client = newClient(null, Set.of(Scope.NOTIFY_OWNER));

        client.markUsed(T1);

        assertThat(client.getLastUsedAt()).isEqualTo(T1);
        assertThat(client.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("無綁定使用者即 SERVICE client")
    void unboundClient_isService() {
        assertThat(newClient(null, Set.of(Scope.NOTIFY_OWNER)).isBound()).isFalse();
        assertThat(newClient(USER_ID, Set.of(Scope.NOTIFY_SELF)).isBound()).isTrue();
    }

    @Test
    @DisplayName("hasScope 是單純的集合包含判斷，沒有條件分支")
    void hasScope_isPlainSetMembership() {
        Client client = newClient(USER_ID, Set.of(Scope.NOTIFY_SELF, Scope.NOTIFY_OWNER));

        assertThat(client.hasScope(Scope.NOTIFY_SELF)).isTrue();
        assertThat(client.hasScope(Scope.NOTIFY_OWNER)).isTrue();
        assertThat(client.hasScope(Scope.NOTIFY_ALL)).isFalse();
        assertThat(client.hasScope(Scope.NOTIFY_RAW)).isFalse();
    }

    @Test
    @DisplayName("scopes 對外唯讀，密文與 IV 對外複製")
    void collectionsAndArrays_areDefensive() {
        Client client = newClient(USER_ID, Set.of(Scope.NOTIFY_SELF));

        assertThatThrownBy(() -> client.getScopes().add(Scope.NOTIFY_ALL))
                .isInstanceOf(UnsupportedOperationException.class);

        byte[] cipher = client.getSecretCiphertext();
        cipher[0] = 99;
        assertThat(client.getSecretCiphertext()).containsExactly(1, 2, 3);

        byte[] iv = client.getSecretIv();
        iv[0] = 99;
        assertThat(client.getSecretIv()).containsExactly(4, 5, 6);
    }

    @Test
    @DisplayName("toString 不輸出密文或 IV")
    void toString_doesNotLeakSecretMaterial() {
        String text = newClient(USER_ID, Set.of(Scope.NOTIFY_SELF)).toString();

        assertThat(text).contains("cli_test0000000000000").contains("ACTIVE");
        assertThat(text).doesNotContain("[1, 2, 3]").doesNotContain("[4, 5, 6]");
    }

    // ----------------------------------------------------------------- Scope

    @Test
    @DisplayName("Scope：資料庫字串形式與 enum 名稱不同，必須用 value()")
    void scope_valueDiffersFromEnumName() {
        assertThat(Scope.NOTIFY_SELF.value()).isEqualTo("notify:self");
        assertThat(Scope.NOTIFY_RAW.value()).isEqualTo("notify:raw");
        assertThat(Scope.NOTIFY_SELF.name()).isNotEqualTo(Scope.NOTIFY_SELF.value());
    }

    @Test
    @DisplayName("Scope.fromValue：雙向轉換一致，未知值明確失敗")
    void scope_fromValue_roundTrips() {
        for (Scope scope : Scope.values()) {
            assertThat(Scope.fromValue(scope.value())).isEqualTo(scope);
        }
        assertThatThrownBy(() -> Scope.fromValue("notify:everything"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notify:everything");
        // enum 名稱不是合法輸入
        assertThatThrownBy(() -> Scope.fromValue("NOTIFY_SELF"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Scope：只有 user / all / raw 是 OWNER 專屬")
    void scope_ownerOnlySet() {
        assertThat(Scope.ownerOnly())
                .containsExactlyInAnyOrder(Scope.NOTIFY_USER, Scope.NOTIFY_ALL, Scope.NOTIFY_RAW);

        assertThat(Scope.NOTIFY_SELF.isOwnerOnly()).isFalse();
        assertThat(Scope.NOTIFY_OWNER.isOwnerOnly()).isFalse();
        assertThat(Scope.NOTIFY_USER.isOwnerOnly()).isTrue();
        assertThat(Scope.NOTIFY_ALL.isOwnerOnly()).isTrue();
        assertThat(Scope.NOTIFY_RAW.isOwnerOnly()).isTrue();
    }

    @Test
    @DisplayName("Scope.ownerOnly() 不可被外部修改")
    void scope_ownerOnly_isImmutable() {
        assertThatThrownBy(() -> Scope.ownerOnly().add(Scope.NOTIFY_SELF))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------- ClientStatus

    @Test
    @DisplayName("ClientStatus：只有 ACTIVE 可用")
    void clientStatus_onlyActiveIsUsable() {
        assertThat(ClientStatus.ACTIVE.isUsable()).isTrue();
        assertThat(ClientStatus.DISABLED.isUsable()).isFalse();
        assertThat(ClientStatus.REVOKED.isUsable()).isFalse();
    }

    // -------------------------------------------------------- ScopeConverter

    @Test
    @DisplayName("ScopeConverter：雙向轉換，null 安全")
    void scopeConverter_roundTripsAndHandlesNull() {
        ScopeConverter converter = new ScopeConverter();

        for (Scope scope : Scope.values()) {
            String db = converter.convertToDatabaseColumn(scope);
            assertThat(db).isEqualTo(scope.value());
            assertThat(converter.convertToEntityAttribute(db)).isEqualTo(scope);
        }
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
