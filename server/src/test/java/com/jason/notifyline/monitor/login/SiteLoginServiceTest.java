package com.jason.notifyline.monitor.login;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SiteLoginService} 的快取、退路與停用規則。見
 * {@code Docs/plan/15-監控站台登入設計.md} §5。
 *
 * <p>SRP 的數學正確性不在這裡驗（那是 {@link CognitoSrpTest} 的職責，用獨立實作對拍）。
 * 這個類別驗的是<strong>編排</strong>：什麼時候用快取、什麼時候 refresh、什麼時候
 * 完整登入、失敗時停不停用——那些才是這一層最容易寫錯的部分。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteLoginService")
class SiteLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final String CONFIG = """
            {"region":"eu-west-2","userPoolId":"eu-west-2_FhQHPoX2z","clientId":"1h3khfsa958g8qa0gge2dnqvka"}
            """;

    @Mock
    private MonitorLoginRepository repository;

    private FakeCognitoClient cognitoClient;
    private SecretCipher secretCipher;
    private SiteLoginService service;

    @BeforeEach
    void setUp() {
        cognitoClient = new FakeCognitoClient();
        secretCipher = new SecretCipher(Map.of(1, KEY), 1);
        service = new SiteLoginService(repository, cognitoClient, secretCipher,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * 建一筆已存好密碼的登入設定。id 用反射塞——正式流程是 {@code save()} 之後由 JPA
     * 填上，測試不想為此拉一整個 Spring context。
     */
    private MonitorLogin login(String headerTemplate) {
        MonitorLogin login = new MonitorLogin("cas", LoginType.COGNITO_SRP, CONFIG,
                "student@example.com", "Authorization", headerTemplate, NOW);
        setId(login, 7L);
        EncryptedSecret password = secretCipher.encrypt("hunter2", "monitor_login:7:password");
        login.applyPassword(password.ciphertext(), password.iv(), password.keyVersion(), NOW);
        return login;
    }

    private static void setId(MonitorLogin login, Long id) {
        try {
            Field field = MonitorLogin.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(login, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 產生一個 exp 在指定時間的 JWT（不簽章——{@link JwtExpiry} 本來就不驗章）。 */
    private static String jwtExpiringAt(Instant expiry) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"exp\":" + expiry.getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private void storeFreshToken(MonitorLogin login, String idToken, Instant expiresAt) {
        EncryptedSecret token = secretCipher.encrypt(idToken, "monitor_login:7:token");
        login.applyTokens(token.ciphertext(), token.iv(), token.keyVersion(), expiresAt,
                null, null, null, NOW);
    }

    private void storeRefreshToken(MonitorLogin login, String refreshToken) {
        EncryptedSecret token = secretCipher.encrypt("stale-id-token", "monitor_login:7:token");
        EncryptedSecret refresh = secretCipher.encrypt(refreshToken, "monitor_login:7:refresh");
        login.applyTokens(token.ciphertext(), token.iv(), token.keyVersion(),
                NOW.minusSeconds(60), refresh.ciphertext(), refresh.iv(), refresh.keyVersion(), NOW);
    }

    @Nested
    @DisplayName("快取")
    class Caching {

        @Test
        @DisplayName("token 還新鮮時完全不呼叫 Cognito")
        void usesCachedToken() {
            MonitorLogin login = login("{token}");
            storeFreshToken(login, "cached-token", NOW.plusSeconds(3600));
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));

            ResolvedLoginHeader header = service.resolve(7L);

            assertThat(header.value()).isEqualTo("cached-token");
            assertThat(cognitoClient.calls).isEmpty();
        }

        @Test
        @DisplayName("token 只剩不到安全邊際就當作已過期")
        void expiresWithinSkew() {
            MonitorLogin login = login("{token}");
            // 還有 60 秒，但邊際是 120 秒 —— 送到對方手上可能已經過期
            storeFreshToken(login, "almost-expired", NOW.plusSeconds(60));
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            cognitoClient.nextTokens(new AuthTokens(
                    jwtExpiringAt(NOW.plusSeconds(3600)), "new-refresh", 3600));

            ResolvedLoginHeader header = service.resolve(7L);

            assertThat(cognitoClient.calls).contains("initiateSrp");
            assertThat(header.value()).isNotEqualTo("almost-expired");
        }
    }

    @Nested
    @DisplayName("refresh 與完整登入的分工")
    class RefreshPath {

        @Test
        @DisplayName("有 refresh token 時優先用它，不跑 SRP")
        void prefersRefresh() {
            MonitorLogin login = login("{token}");
            storeRefreshToken(login, "refresh-abc");
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            cognitoClient.nextTokens(new AuthTokens(
                    jwtExpiringAt(NOW.plusSeconds(3600)), null, 3600));

            service.resolve(7L);

            assertThat(cognitoClient.calls).containsExactly("refresh");
        }

        @Test
        @DisplayName("refresh 過期（NotAuthorized）退回完整登入，不停用")
        void refreshExpiredFallsBackToFullLogin() {
            MonitorLogin login = login("{token}");
            storeRefreshToken(login, "expired-refresh");
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            cognitoClient.failRefreshWith(new CognitoAuthException(
                    CognitoAuthException.Reason.INVALID_CREDENTIALS, "Refresh Token has expired"));
            cognitoClient.nextTokens(new AuthTokens(
                    jwtExpiringAt(NOW.plusSeconds(3600)), "brand-new-refresh", 3600));

            service.resolve(7L);

            assertThat(cognitoClient.calls)
                    .containsExactly("refresh", "initiateSrp", "respondToPasswordVerifier");
            assertThat(login.isEnabled())
                    .as("refresh 過期是正常汰換，不是帳密錯，絕不可停用")
                    .isTrue();
        }

        @Test
        @DisplayName("refresh 遇到非帳密類錯誤時直接往外拋，不浪費一次完整登入")
        void refreshTransientDoesNotFallBack() {
            MonitorLogin login = login("{token}");
            storeRefreshToken(login, "refresh-abc");
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            cognitoClient.failRefreshWith(new CognitoAuthException(
                    CognitoAuthException.Reason.RATE_LIMITED, "TooManyRequestsException"));

            assertThatThrownBy(() -> service.resolve(7L))
                    .isInstanceOf(CognitoAuthException.class);

            assertThat(cognitoClient.calls).containsExactly("refresh");
        }
    }

    @Nested
    @DisplayName("失敗處理（§5.1）")
    class FailureHandling {

        @Test
        @DisplayName("密碼錯 → 停用、清掉 token、記錄原因")
        void permanentFailureDisables() {
            MonitorLogin login = login("{token}");
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            cognitoClient.failVerifierWith(new CognitoAuthException(
                    CognitoAuthException.Reason.INVALID_CREDENTIALS, "Incorrect username or password"));

            assertThatThrownBy(() -> service.resolve(7L))
                    .isInstanceOf(CognitoAuthException.class);

            assertThat(login.isEnabled()).isFalse();
            assertThat(login.getTokenCiphertext()).isNull();
            assertThat(login.getLastError()).contains("INVALID_CREDENTIALS");
        }

        @Test
        @DisplayName("限流 → 保持啟用，只累加失敗計數")
        void transientFailureKeepsEnabled() {
            MonitorLogin login = login("{token}");
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            cognitoClient.failInitiateWith(new CognitoAuthException(
                    CognitoAuthException.Reason.RATE_LIMITED, "TooManyRequestsException"));

            assertThatThrownBy(() -> service.resolve(7L))
                    .isInstanceOf(CognitoAuthException.class);

            assertThat(login.isEnabled()).isTrue();
            assertThat(login.getConsecutiveFailures()).isEqualTo(1);
        }

        @Test
        @DisplayName("已停用的登入直接拒絕，不再打 Cognito")
        void disabledLoginIsRejected() {
            MonitorLogin login = login("{token}");
            login.disableAfterPermanentFailure("先前失敗", NOW);
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));

            assertThatThrownBy(() -> service.resolve(7L))
                    .isInstanceOf(CognitoAuthException.class)
                    .hasMessageContaining("已停用");

            assertThat(cognitoClient.calls)
                    .as("停用後還去打，正是會把帳號鎖死的行為")
                    .isEmpty();
        }

        @Test
        @DisplayName("找不到設定時不會 NPE，而是明確的設定錯誤")
        void missingLogin() {
            when(repository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolve(99L))
                    .isInstanceOf(CognitoAuthException.class)
                    .hasMessageContaining("不存在");
        }
    }

    @Nested
    @DisplayName("header 注入格式")
    class HeaderRendering {

        @Test
        @DisplayName("裸 token（這個站台的怪癖）")
        void bareToken() {
            MonitorLogin login = login("{token}");
            storeFreshToken(login, "abc123", NOW.plusSeconds(3600));
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));

            assertThat(service.resolve(7L))
                    .isEqualTo(new ResolvedLoginHeader("Authorization", "abc123"));
        }

        @Test
        @DisplayName("Bearer 前綴（一般站台）")
        void bearerToken() {
            MonitorLogin login = login("Bearer {token}");
            storeFreshToken(login, "abc123", NOW.plusSeconds(3600));
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));

            assertThat(service.resolve(7L).value()).isEqualTo("Bearer abc123");
        }
    }

    @Nested
    @DisplayName("到期時間")
    class Expiry {

        @Test
        @DisplayName("優先用 JWT 的 exp")
        void usesJwtExp() {
            MonitorLogin login = login("{token}");
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            Instant exp = NOW.plusSeconds(1234);
            cognitoClient.nextTokens(new AuthTokens(jwtExpiringAt(exp), "r", 3600));

            service.resolve(7L);

            assertThat(login.getTokenExpiresAt()).isEqualTo(exp);
        }

        @Test
        @DisplayName("JWT 解不開時退回 ExpiresIn")
        void fallsBackToExpiresIn() {
            MonitorLogin login = login("{token}");
            when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(login));
            cognitoClient.nextTokens(new AuthTokens("not-a-jwt", "r", 900));

            service.resolve(7L);

            assertThat(login.getTokenExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        }
    }

    /**
     * 可編排的 {@link CognitoClient}。記錄呼叫順序，讓測試可以斷言「有沒有多打一次」
     * ——那正是會鎖帳號的行為。
     */
    private static final class FakeCognitoClient implements CognitoClient {

        private final java.util.List<String> calls = new java.util.ArrayList<>();
        private final Deque<AuthTokens> tokens = new ArrayDeque<>();
        private CognitoAuthException initiateFailure;
        private CognitoAuthException verifierFailure;
        private CognitoAuthException refreshFailure;

        void nextTokens(AuthTokens next) {
            tokens.add(next);
        }

        void failInitiateWith(CognitoAuthException e) {
            this.initiateFailure = e;
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
            if (initiateFailure != null) {
                throw initiateFailure;
            }
            // salt 不具歧義（開頭不是 00），所以不會觸發第二次嘗試
            return new SrpChallenge(
                    "ee2d3f1b9eafc63ae7ff2b60cdd1f8c8",
                    "8200cf0ce11447bf6353cbac964d07d1",
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
            AuthTokens next = tokens.poll();
            if (next == null) {
                throw new IllegalStateException("測試沒有安排這一次呼叫的回應");
            }
            return next;
        }
    }
}
