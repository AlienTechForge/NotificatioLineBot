package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.lineuser.LineUserService;
import com.jason.notifyline.notification.api.NotificationRequest;
import com.jason.notifyline.notification.domain.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * target × scope 的允許矩陣。見 {@code Docs/plan/03-權限與認證設計.md} §1.3。
 *
 * <p>權限矩陣是這個服務裡出錯代價最高的一塊 —— 錯一格就是「某個服務可以發訊息給
 * 所有使用者」。所以四種 target 逐一列舉，不用迴圈掃過去。
 */
@DisplayName("TargetResolver")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TargetResolverTest {

    private static final String BOUND = "U" + "a".repeat(32);
    private static final String OWNER_ID = "U" + "b".repeat(32);
    private static final String OTHER = "U" + "c".repeat(32);

    @Mock
    private LineUserService lineUserService;

    private TargetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TargetResolver(lineUserService);
        lenient().when(lineUserService.isActive(BOUND)).thenReturn(true);
        lenient().when(lineUserService.activeOwnerIds()).thenReturn(List.of(OWNER_ID));
        lenient().when(lineUserService.activeUserIds()).thenReturn(List.of(BOUND, OWNER_ID, OTHER));
        lenient().when(lineUserService.activeAmong(List.of(OTHER))).thenReturn(List.of(OTHER));
    }

    private static ClientPrincipal principal(String bound, Scope... scopes) {
        Set<Scope> set = scopes.length == 0 ? EnumSet.noneOf(Scope.class) : Set.of(scopes);
        return new ClientPrincipal(1L, "cli_test", bound, set, null, null);
    }

    private static NotificationRequest request(TargetType type, List<String> userIds) {
        return new NotificationRequest(
                new NotificationRequest.Target(type, userIds),
                new NotificationRequest.Message(null, "x"), null, null);
    }

    // ---------------------------------------------------------------- 放行

    @Test
    @DisplayName("SELF 解析成綁定的那一個使用者")
    void self() {
        assertThat(resolver.resolve(principal(BOUND, Scope.NOTIFY_SELF),
                request(TargetType.SELF, null)))
                .containsExactly(BOUND);
    }

    @Test
    @DisplayName("OWNER 解析成所有 ACTIVE 的 owner")
    void owner() {
        assertThat(resolver.resolve(principal(null, Scope.NOTIFY_OWNER),
                request(TargetType.OWNER, null)))
                .containsExactly(OWNER_ID);
    }

    @Test
    @DisplayName("USER 只保留仍然 ACTIVE 的收件人")
    void user() {
        assertThat(resolver.resolve(principal(null, Scope.NOTIFY_USER),
                request(TargetType.USER, List.of(OTHER))))
                .containsExactly(OTHER);
    }

    @Test
    @DisplayName("ALL 解析成所有 ACTIVE 使用者")
    void all() {
        assertThat(resolver.resolve(principal(null, Scope.NOTIFY_ALL),
                request(TargetType.ALL, null)))
                .containsExactly(BOUND, OWNER_ID, OTHER);
    }

    // ---------------------------------------------------------------- 拒絕

    @ParameterizedTest(name = "{0} 缺少對應 scope → 403")
    @CsvSource({
            "SELF,  NOTIFY_OWNER",
            "OWNER, NOTIFY_SELF",
            "USER,  NOTIFY_ALL",
            "ALL,   NOTIFY_USER",
    })
    @DisplayName("持有「別的」scope 不能代替對應的那一個")
    void wrongScopeDenied(TargetType type, Scope held) {
        assertThatThrownBy(() -> resolver.resolve(
                principal(BOUND, held), request(type, List.of(OTHER))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SCOPE_DENIED);
    }

    @Test
    @DisplayName("沒有 notify:user 就一律 403，即使 userIds 只填自己")
    void userTargetDeniedEvenForSelf() {
        // 開這個後門會讓規則從「不准出現 userIds」變成「只能是自己、長度為 1、
        // 且要比對綁定的 id」—— 權限檢查的正確性與它的分支數成反比
        assertThatThrownBy(() -> resolver.resolve(
                principal(BOUND, Scope.NOTIFY_SELF), request(TargetType.USER, List.of(BOUND))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SCOPE_DENIED);
    }

    @Test
    @DisplayName("沒有綁定使用者卻發 SELF → 400 CLIENT_NOT_BOUND，訊息要指出正確做法")
    void selfWithoutBinding() {
        assertThatThrownBy(() -> resolver.resolve(
                principal(null, Scope.NOTIFY_SELF), request(TargetType.SELF, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("OWNER")
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_BOUND);
    }

    @Test
    @DisplayName("綁定的使用者已封鎖 → 400 NO_RECIPIENT，不送出 0 人的批次")
    void boundUserBlocked() {
        lenient().when(lineUserService.isActive(BOUND)).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolve(
                principal(BOUND, Scope.NOTIFY_SELF), request(TargetType.SELF, null)))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.NO_RECIPIENT);
    }

    @Test
    @DisplayName("指定的 userIds 全部不是 ACTIVE → 400 NO_RECIPIENT")
    void allRequestedUsersInactive() {
        lenient().when(lineUserService.activeAmong(List.of(OTHER))).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(
                principal(null, Scope.NOTIFY_USER), request(TargetType.USER, List.of(OTHER))))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.NO_RECIPIENT);
    }

    @Test
    @DisplayName("type=USER 但沒給 userIds → 400")
    void userTargetWithoutIds() {
        assertThatThrownBy(() -> resolver.resolve(
                principal(null, Scope.NOTIFY_USER), request(TargetType.USER, null)))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ------------------------------------------------------------ notify:raw

    @Test
    @DisplayName("lineMessages 需要 notify:raw，即使 target 的 scope 已具備")
    void rawRequiresOwnScope() {
        NotificationRequest raw = new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null),
                null, List.of(Map.of("type", "text", "text", "x")), null);

        assertThatThrownBy(() -> resolver.resolve(principal(null, Scope.NOTIFY_OWNER), raw))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("notify:raw")
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SCOPE_DENIED);
    }

    @Test
    @DisplayName("同時具備 notify:owner 與 notify:raw 才放行")
    void rawAllowedWithBothScopes() {
        NotificationRequest raw = new NotificationRequest(
                new NotificationRequest.Target(TargetType.OWNER, null),
                null, List.of(Map.of("type", "text", "text", "x")), null);

        assertThat(resolver.resolve(
                principal(null, Scope.NOTIFY_OWNER, Scope.NOTIFY_RAW), raw))
                .containsExactly(OWNER_ID);
    }

    @Test
    @DisplayName("完全沒有 scope 的 client 什麼都發不了")
    void noScopeAtAll() {
        assertThatThrownBy(() -> resolver.resolve(
                principal(BOUND), request(TargetType.SELF, null)))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SCOPE_DENIED);
    }
}
