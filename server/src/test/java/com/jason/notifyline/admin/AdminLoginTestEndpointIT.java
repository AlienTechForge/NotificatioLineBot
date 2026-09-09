package com.jason.notifyline.admin;

import com.jason.notifyline.monitor.login.AuthTokens;
import com.jason.notifyline.monitor.login.CognitoAuthException;
import com.jason.notifyline.monitor.login.CognitoClient;
import com.jason.notifyline.monitor.login.CognitoEndpoint;
import com.jason.notifyline.monitor.login.MonitorLoginRepository;
import com.jason.notifyline.monitor.login.SrpChallenge;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「測試登入」端點（{@code POST /admin/api/logins/{id}/test}）的整合測試。
 *
 * <h2>為什麼要獨立一個 context</h2>
 *
 * <p>這裡把 {@link CognitoClient} 換成可編排的 stub —— 真的打 Cognito 需要真實帳密，
 * CI 沒有也不該有。分開放的理由同 {@code AdminMonitorTestEndpointIT}：不要讓一個
 * 被換掉的 bean 污染 {@link AdminLoginIT} 那些驗證真實行為的案例。
 *
 * <h2>這組測試在防哪個回歸</h2>
 *
 * <p>登入失敗時，失敗處理要能<strong>真的寫進資料庫</strong>，而端點要回 200 +
 * {@code success=false}（讓使用者看到原因），不是 500。
 *
 * <p>第一版實作兩件都做不到，而且是同一個原因：{@code SiteLoginService.verify()} 與
 * {@code MonitorLoginAdminService.test()} 都掛 {@code @Transactional}，內層拋出
 * {@code CognitoAuthException} 時 Spring 把<strong>共用的</strong>交易標記成
 * rollback-only。外層攔下例外、正常回傳，提交時炸 {@code UnexpectedRollbackException}
 * → 500，把真正的失敗原因蓋掉；而 {@code recordFailure()} 寫入的停用也一併被回滾
 * ——「密碼錯就停用」這條保護等於沒有。
 */
@DisplayName("測試登入端點（整合）")
@AutoConfigureMockMvc
class AdminLoginTestEndpointIT extends PostgresIntegrationTest {

    private static final String ADMIN_USER = "admin";

    /** 讓 stub 每個案例各自決定行為。 */
    static volatile CognitoAuthException initiateFailure;
    static volatile CognitoAuthException verifierFailure;
    static volatile AuthTokens tokens;

    @TestConfiguration
    static class StubCognitoConfig {

        @Bean
        @Primary
        CognitoClient stubCognitoClient() {
            return new CognitoClient() {
                @Override
                public SrpChallenge initiateSrp(CognitoEndpoint endpoint, String username, String srpAHex) {
                    if (initiateFailure != null) {
                        throw initiateFailure;
                    }
                    // salt 開頭不是 00 → 不具歧義 → 不會觸發第二次嘗試
                    return new SrpChallenge(
                            "ee2d3f1b9eafc63ae7ff2b60cdd1f8c8",
                            "8200cf0ce11447bf6353cbac964d07d1c390d61d07e6c5d0214450b3add6449b",
                            Base64.getEncoder().encodeToString("secret-block".getBytes(StandardCharsets.UTF_8)),
                            "1a2b3c4d-0000-4444-8888-abcdefabcdef");
                }

                @Override
                public AuthTokens respondToPasswordVerifier(CognitoEndpoint endpoint, String userIdForSrp,
                                                            String secretBlock, String signature, String timestamp) {
                    if (verifierFailure != null) {
                        throw verifierFailure;
                    }
                    return tokens;
                }

                @Override
                public AuthTokens refresh(CognitoEndpoint endpoint, String refreshToken) {
                    return tokens;
                }
            };
        }
    }

    @DynamicPropertySource
    static void adminCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.admin.username", () -> ADMIN_USER);
        registry.add("app.admin.password", () -> "integration-test-password");
        registry.add("app.dispatch.enabled", () -> "false");
        registry.add("app.monitor.enabled", () -> "false");
        registry.add("app.line.api-base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MonitorLoginRepository logins;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        initiateFailure = null;
        verifierFailure = null;
        tokens = null;
        logins.deleteAll();
    }

    @AfterEach
    void tearDown() {
        logins.deleteAll();
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    private Long createLogin() throws Exception {
        String body = objectMapper.writeValueAsString(new AdminDto.CreateLoginRequest(
                "cas", null, "eu-west-2", "eu-west-2_FhQHPoX2z", "1h3khfsa958g8qa0gge2dnqvka",
                "student@example.com", "hunter2", "Authorization", "{token}"));
        String response = mockMvc.perform(post("/admin/api/logins").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("id").asLong();
    }

    private static String jwtExpiringIn(long seconds) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"exp\":" + (Instant.now().getEpochSecond() + seconds) + "}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    @Test
    @DisplayName("密碼錯 → 200 + success=false，而且停用真的寫進資料庫")
    void permanentFailureIsReportedAndPersisted() throws Exception {
        Long id = createLogin();
        verifierFailure = new CognitoAuthException(
                CognitoAuthException.Reason.INVALID_CREDENTIALS, "Incorrect username or password");

        mockMvc.perform(post("/admin/api/logins/" + id + "/test").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.error").value(
                        org.hamcrest.Matchers.containsString("Incorrect username or password")));

        // 這一行才是重點：停用必須存活下來，否則下一輪輪詢又會去撞一次密碼，
        // 反覆下去就是把使用者的帳號鎖死——正是 §5.1 要避免的事。
        assertThat(logins.findById(id).orElseThrow().isEnabled()).isFalse();
        assertThat(logins.findById(id).orElseThrow().getLastError()).contains("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("限流 → 200 + success=false，保持啟用但失敗計數有寫進去")
    void transientFailureIsReportedAndPersisted() throws Exception {
        Long id = createLogin();
        initiateFailure = new CognitoAuthException(
                CognitoAuthException.Reason.RATE_LIMITED, "TooManyRequestsException");

        mockMvc.perform(post("/admin/api/logins/" + id + "/test").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false));

        var stored = logins.findById(id).orElseThrow();
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("成功 → 200 + success=true，token 與到期時間都存下來")
    void successStoresToken() throws Exception {
        Long id = createLogin();
        tokens = new AuthTokens(jwtExpiringIn(3600), "refresh-abc", 3600);

        mockMvc.perform(post("/admin/api/logins/" + id + "/test").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.tokenExpiresAt").isNotEmpty());

        var stored = logins.findById(id).orElseThrow();
        assertThat(stored.getTokenCiphertext()).isNotNull();
        assertThat(stored.getTokenExpiresAt()).isNotNull();
        assertThat(stored.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("回應不含 token —— 使用者要知道的是能不能登入，不是 token 本身")
    void responseNeverContainsToken() throws Exception {
        Long id = createLogin();
        String idToken = jwtExpiringIn(3600);
        tokens = new AuthTokens(idToken, "refresh-abc", 3600);

        String body = mockMvc.perform(post("/admin/api/logins/" + id + "/test").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(idToken).doesNotContain("refresh-abc");
    }
}
