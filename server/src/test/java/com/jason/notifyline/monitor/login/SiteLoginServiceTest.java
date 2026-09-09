package com.jason.notifyline.monitor.login;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SiteLoginService} 的<strong>編排</strong>：什麼時候用快取、什麼時候 refresh、
 * 什麼時候完整登入、失敗時記不記錄。見 {@code Docs/plan/15-監控站台登入設計.md} §5。
 *
 * <p>兩件事不在這裡驗：
 * <ul>
 *   <li>SRP 的數學正確性 → {@link CognitoSrpTest}，用獨立實作對拍</li>
 *   <li>失敗記錄有沒有<strong>真的寫進資料庫</strong> → {@code AdminLoginTestEndpointIT}。
 *       那是交易邊界的問題，mock 出來的 store 永遠不會告訴你交易被標成 rollback-only</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteLoginService")
class SiteLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final Long LOGIN_ID = 7L;

    private static final CognitoEndpoint ENDPOINT =
            new CognitoEndpoint("eu-west-2", "eu-west-2_FhQHPoX2z", "1h3khfsa958g8qa0gge2dnqvka");

    @Mock
    private MonitorLoginStore store;

    private FakeCognitoClient cognitoClient;
    private SiteLoginService service;

    @BeforeEach
    void setUp() {
        cognitoClient = new FakeCognitoClient();
        service = new SiteLoginService(store, cognitoClient, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(store.loadPassword(LOGIN_ID)).thenReturn("hunter2");
        lenient().when(store.storeTokens(eq(LOGIN_ID), any())).thenReturn(NOW.plusSeconds(3600));
    }

    private LoginSnapshot snapshot(String idToken, Instant expiresAt, String refreshToken) {
        return snapshot(idToken, expiresAt, refreshToken, "{token}", true);
    }

    private LoginSnapshot snapshot(String idToken, Instant expiresAt, String refreshToken,
                                   String template, boolean enabled) {
        return new LoginSnapshot(LOGIN_ID, "cas", enabled, ENDPOINT, "student@example.com",
                idToken, expiresAt, refreshToken, "Authorization", template);
    }

    private static AuthTokens tokens(String refreshToken) {
        return new AuthTokens("new-id-token", refreshToken, 3600);
    }

    @Nested
    @DisplayName("快取")
    class Caching {

        @Test
        @DisplayName("token 還新鮮時完全不呼叫 Cognito")
        void usesCachedToken() {
            when(store.loadForUse(LOGIN_ID))
                    .thenReturn(snapshot("cached-token", NOW.plusSeconds(3600), null));

            ResolvedLoginHeader header = service.resolve(LOGIN_ID);

            assertThat(header.value()).isEqualTo("cached-token");
            assertThat(cognitoClient.calls).isEmpty();
        }

        @Test
        @DisplayName("token 只剩不到安全邊際就當作已過期")
        void expiresWithinSkew() {
            // 還有 60 秒，但邊際是 120 秒 —— 送到對方手上可能已經過期
            when(store.loadForUse(LOGIN_ID))
                    .thenReturn(snapshot("almost-expired", NOW.plusSeconds(60), null));
            cognitoClient.nextTokens(tokens("r"));

            ResolvedLoginHeader header = service.resolve(LOGIN_ID);

            assertThat(cognitoClient.calls).contains("initiateSrp");
            assertThat(header.value()).isEqualTo("new-id-token");
        }
    }

    @Nested
    @DisplayName("refresh 與完整登入的分工")
    class RefreshPath {

        @Test
        @DisplayName("有 refresh token 時優先用它，不跑 SRP")
        void prefersRefresh() {
            when(store.loadForUse(LOGIN_ID)).thenReturn(snapshot(null, null, "refresh-abc"));
            cognitoClient.nextTokens(tokens(null));

            service.resolve(LOGIN_ID);

            assertThat(cognitoClient.calls).containsExactly("refresh");
            verify(store, never()).loadPassword(any());
        }

        @Test
        @DisplayName("refresh 過期（NotAuthorized）退回完整登入，不記為失敗")
        void refreshExpiredFallsBackToFullLogin() {
            when(store.loadForUse(LOGIN_ID)).thenReturn(snapshot(null, null, "expired-refresh"));
            cognitoClient.failRefreshWith(new CognitoAuthException(
                    CognitoAuthException.Reason.INVALID_CREDENTIALS, "Refresh Token has expired"));
            cognitoClient.nextTokens(tokens("brand-new-refresh"));

            service.resolve(LOGIN_ID);

            assertThat(cognitoClient.calls)
                    .containsExactly("refresh", "initiateSrp", "respondToPasswordVerifier");
            verify(store, never()).recordFailure(any(), any());
        }

        @Test
        @DisplayName("refresh 遇到非帳密類錯誤時直接往外拋，不浪費一次完整登入")
        void refreshTransientDoesNotFallBack() {
            when(store.loadForUse(LOGIN_ID)).thenReturn(snapshot(null, null, "refresh-abc"));
            cognitoClient.failRefreshWith(new CognitoAuthException(
                    CognitoAuthException.Reason.RATE_LIMITED, "TooManyRequestsException"));

            assertThatThrownBy(() -> service.resolve(LOGIN_ID))
                    .isInstanceOf(CognitoAuthException.class);

            assertThat(cognitoClient.calls).containsExactly("refresh");
            verify(store).recordFailure(eq(LOGIN_ID), any());
        }
    }

    @Nested
    @DisplayName("失敗處理（§5.1）")
    class FailureHandling {

        @Test
        @DisplayName("密碼錯 → 記錄失敗並往外拋")
        void permanentFailureIsRecorded() {
            when(store.loadForUse(LOGIN_ID)).thenReturn(snapshot(null, null, null));
            cognitoClient.failVerifierWith(new CognitoAuthException(
                    CognitoAuthException.Reason.INVALID_CREDENTIALS, "Incorrect username or password"));

            assertThatThrownBy(() -> service.resolve(LOGIN_ID))
                    .isInstanceOf(CognitoAuthException.class);

            ArgumentCaptor<CognitoAuthException> captured =
                    ArgumentCaptor.forClass(CognitoAuthException.class);
            verify(store).recordFailure(eq(LOGIN_ID), captured.capture());
            assertThat(captured.getValue().reason())
                    .isEqualTo(CognitoAuthException.Reason.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("salt 不具歧義時不會多送一次錯誤密碼")
        void doesNotRetryWhenSaltIsUnambiguous() {
            when(store.loadForUse(LOGIN_ID)).thenReturn(snapshot(null, null, null));
            cognitoClient.failVerifierWith(new CognitoAuthException(
                    CognitoAuthException.Reason.INVALID_CREDENTIALS, "Incorrect username or password"));

            assertThatThrownBy(() -> service.resolve(LOGIN_ID))
                    .isInstanceOf(CognitoAuthException.class);

            // 第二次 initiateSrp 是為了拿新的挑戰來判斷 salt 有沒有歧義；判斷出沒有之後
            // 就停手，不會再送第二次 respondToPasswordVerifier —— 那才是會累加
            // 帳號失敗計數的動作。
            assertThat(cognitoClient.calls.stream().filter("respondToPasswordVerifier"::equals))
                    .hasSize(1);
        }

        @Test
        @DisplayName("已停用的登入直接拒絕，不再打 Cognito")
        void disabledLoginIsRejected() {
            when(store.loadForUse(LOGIN_ID))
                    .thenReturn(snapshot(null, null, null, "{token}", false));

            assertThatThrownBy(() -> service.resolve(LOGIN_ID))
                    .isInstanceOf(CognitoAuthException.class)
                    .hasMessageContaining("已停用");

            assertThat(cognitoClient.calls)
                    .as("停用後還去打，正是會把帳號鎖死的行為")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("測試登入（verify）")
    class Verify {

        @Test
        @DisplayName("不吃快取，強制走一次真的登入")
        void ignoresCache() {
            when(store.loadForUse(LOGIN_ID))
                    .thenReturn(snapshot("perfectly-fresh", NOW.plusSeconds(3600), "r"));
            cognitoClient.nextTokens(tokens("r2"));

            service.verify(LOGIN_ID);

            assertThat(cognitoClient.calls)
                    .containsExactly("initiateSrp", "respondToPasswordVerifier");
        }

        @Test
        @DisplayName("已停用也能測 —— 使用者就是要靠它確認密碼修好了沒")
        void worksOnDisabledLogin() {
            when(store.loadForUse(LOGIN_ID))
                    .thenReturn(snapshot(null, null, null, "{token}", false));
            cognitoClient.nextTokens(tokens("r"));

            assertThat(service.verify(LOGIN_ID)).isEqualTo(NOW.plusSeconds(3600));
        }
    }

    @Nested
    @DisplayName("header 注入格式")
    class HeaderRendering {

        @Test
        @DisplayName("裸 token（這個站台的怪癖）")
        void bareToken() {
            when(store.loadForUse(LOGIN_ID))
                    .thenReturn(snapshot("abc123", NOW.plusSeconds(3600), null));

            assertThat(service.resolve(LOGIN_ID))
                    .isEqualTo(new ResolvedLoginHeader("Authorization", "abc123"));
        }

        @Test
        @DisplayName("Bearer 前綴（一般站台）")
        void bearerToken() {
            when(store.loadForUse(LOGIN_ID))
                    .thenReturn(snapshot("abc123", NOW.plusSeconds(3600), null, "Bearer {token}", true));

            assertThat(service.resolve(LOGIN_ID).value()).isEqualTo("Bearer abc123");
        }
    }

    /**
     * 可編排的 {@link CognitoClient}。記錄呼叫順序，讓測試可以斷言「有沒有多打一次」
     * ——那正是會累加帳號失敗計數的行為。
     */
    private static final class FakeCognitoClient implements CognitoClient {

        private final List<String> calls = new ArrayList<>();
        private final Deque<AuthTokens> queued = new ArrayDeque<>();
        private CognitoAuthException verifierFailure;
        private CognitoAuthException refreshFailure;

        void nextTokens(AuthTokens next) {
            queued.add(next);
        }

        void failVerifierWith(CognitoAuthException e) {
            this.verifierFailure = e;
        }

        void failRefreshWith(CognitoAuthException e) {
            this.refreshFailure = e;
        }

        @Override
        public SrpChallenge initiateSrp(CognitoEndpoint endpoint, String username, String srpAHex) {
            calls.add("initiateSrp");
            // salt 開頭不是 00 → 不具歧義 → 不會觸發第二次 respondToPasswordVerifier
            return new SrpChallenge(
                    "ee2d3f1b9eafc63ae7ff2b60cdd1f8c8",
                    "8200cf0ce11447bf6353cbac964d07d1c390d61d07e6c5d0214450b3add6449b",
                    Base64.getEncoder().encodeToString("secret-block".getBytes(StandardCharsets.UTF_8)),
                    "1a2b3c4d-0000-4444-8888-abcdefabcdef");
        }

        @Override
        public AuthTokens respondToPasswordVerifier(CognitoEndpoint endpoint, String userIdForSrp,
                                                    String secretBlock, String signature, String timestamp) {
            calls.add("respondToPasswordVerifier");
            if (verifierFailure != null) {
                throw verifierFailure;
            }
            return take();
        }

        @Override
        public AuthTokens refresh(CognitoEndpoint endpoint, String refreshToken) {
            calls.add("refresh");
            if (refreshFailure != null) {
                throw refreshFailure;
            }
            return take();
        }

        private AuthTokens take() {
            AuthTokens next = queued.poll();
            if (next == null) {
                throw new IllegalStateException("測試沒有安排這一次呼叫的回應");
            }
            return next;
        }
    }
}
