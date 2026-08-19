package com.jason.notifyline.auth;

import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HMAC 認證的完整驗證矩陣。見 {@code Docs/plan/08-測試計畫.md} §3.2。
 *
 * <p>用一個測試專用的端點，避免這組測試綁死在 T7 的通知 API 上。
 */
@DisplayName("HmacAuthFilter（整合）")
@AutoConfigureMockMvc
@Import(HmacAuthFilterIT.TestEndpoint.class)
class HmacAuthFilterIT extends PostgresIntegrationTest {

    static final String PATH = "/api/v1/echo";
    private static final String BODY = "{\"hello\":\"world\"}";

    /**
     * 測試專用端點。
     *
     * <p>{@code @RestController} 直接掛在 {@code @TestConfiguration} 上，
     * 不要再額外用 {@code @Bean} 註冊一份 —— 巢狀 {@code @Component} 會被自動
     * 註冊，加上手動的 {@code @Bean} 就變成兩個 handler 對同一路徑，
     * 啟動時報 {@code Ambiguous mapping}。
     */
    @TestConfiguration
    @RestController
    static class TestEndpoint {
        @PostMapping(PATH)
        public java.util.Map<String, Object> echo(@RequestBody(required = false) String body) {
            return java.util.Map.of("echo", body == null ? "" : body);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private LineUserRepository lineUserRepository;
    @Autowired
    private RequestNonceRepository nonceRepository;
    @Autowired
    private Clock clock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String clientId;
    private String secret;

    @BeforeEach
    void setUp() {
        nonceRepository.deleteAll();
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();

        ClientService.IssuedClient issued = clientService.create(
                ClientService.CreateClientCommand.forService("test-svc", null));
        clientId = issued.clientId();
        secret = issued.secret();
    }

    // ------------------------------------------------------------ 簽章工具

    private MockHttpServletRequestBuilder signed(String body, String secretToUse,
                                                 long timestamp, String nonce) {
        String canonical = CanonicalRequest
                .of("POST", PATH, String.valueOf(timestamp), nonce,
                        body.getBytes(StandardCharsets.UTF_8))
                .toCanonicalString();

        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HmacAuthFilter.HEADER_CLIENT_ID, clientId)
                .header(HmacAuthFilter.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(HmacAuthFilter.HEADER_NONCE, nonce)
                .header(HmacAuthFilter.HEADER_SIGNATURE, HmacSigner.sign(secretToUse, canonical))
                .content(body);
    }

    private MockHttpServletRequestBuilder validRequest() {
        return signed(BODY, secret, clock.instant().getEpochSecond(), UUID.randomUUID().toString());
    }

    /**
     * 覆寫單一 header 後重簽。
     *
     * <p>不能用 {@code builder.header(name, value)} 覆寫 —— MockMvc 的 header()
     * 是<strong>附加</strong>，結果會有兩個同名 header，而 getHeader() 取第一個，
     * 於是「壞掉的值」根本沒被讀到，測試會假性通過。
     */
    private MockHttpServletRequestBuilder withOverride(String headerName, String value) {
        long ts = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String canonical = CanonicalRequest
                .of("POST", PATH, String.valueOf(ts), nonce, BODY.getBytes(StandardCharsets.UTF_8))
                .toCanonicalString();

        String timestampHeader = HmacAuthFilter.HEADER_TIMESTAMP.equals(headerName)
                ? value : String.valueOf(ts);
        String nonceHeader = HmacAuthFilter.HEADER_NONCE.equals(headerName) ? value : nonce;
        String signatureHeader = HmacAuthFilter.HEADER_SIGNATURE.equals(headerName)
                ? value : HmacSigner.sign(secret, canonical);
        String clientIdHeader = HmacAuthFilter.HEADER_CLIENT_ID.equals(headerName) ? value : clientId;

        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HmacAuthFilter.HEADER_CLIENT_ID, clientIdHeader)
                .header(HmacAuthFilter.HEADER_TIMESTAMP, timestampHeader)
                .header(HmacAuthFilter.HEADER_NONCE, nonceHeader)
                .header(HmacAuthFilter.HEADER_SIGNATURE, signatureHeader)
                .content(BODY);
    }

    // ---------------------------------------------------------------- 正向

    @Test
    @DisplayName("正確簽章：通過，且回應帶 X-Request-Id")
    void correctSignature_passes() throws Exception {
        mockMvc.perform(validRequest())
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    @DisplayName("呼叫端自帶的 X-Request-Id 會被沿用並回傳")
    void providedRequestId_isEchoedBack() throws Exception {
        mockMvc.perform(validRequest().header("X-Request-Id", "my-trace-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "my-trace-123"));
    }

    @Test
    @DisplayName("時間戳偏移 299 秒仍在容忍範圍內")
    void timestampWithinSkew_passes() throws Exception {
        long ts = clock.instant().getEpochSecond() - 299;
        mockMvc.perform(signed(BODY, secret, ts, UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 負向

    @Test
    @DisplayName("竄改 body 任一字元：401 AUTH_INVALID_SIGNATURE")
    void tamperedBody_rejected() throws Exception {
        long ts = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String canonical = CanonicalRequest
                .of("POST", PATH, String.valueOf(ts), nonce, BODY.getBytes(StandardCharsets.UTF_8))
                .toCanonicalString();

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HmacAuthFilter.HEADER_CLIENT_ID, clientId)
                        .header(HmacAuthFilter.HEADER_TIMESTAMP, String.valueOf(ts))
                        .header(HmacAuthFilter.HEADER_NONCE, nonce)
                        .header(HmacAuthFilter.HEADER_SIGNATURE, HmacSigner.sign(secret, canonical))
                        .content("{\"hello\":\"WORLD\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_SIGNATURE"));
    }

    @Test
    @DisplayName("竄改簽章：401")
    void tamperedSignature_rejected() throws Exception {
        mockMvc.perform(withOverride(HmacAuthFilter.HEADER_SIGNATURE, "AAAA"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_SIGNATURE"));
    }

    @Test
    @DisplayName("錯誤的 secret：401")
    void wrongSecret_rejected() throws Exception {
        mockMvc.perform(signed(BODY, "not-the-real-secret",
                        clock.instant().getEpochSecond(), UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_SIGNATURE"));
    }

    @Test
    @DisplayName("缺任一必要 header：401 AUTH_MISSING_HEADER")
    void missingHeader_rejected() throws Exception {
        for (String header : new String[]{
                HmacAuthFilter.HEADER_CLIENT_ID, HmacAuthFilter.HEADER_TIMESTAMP,
                HmacAuthFilter.HEADER_NONCE, HmacAuthFilter.HEADER_SIGNATURE}) {

            var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY);
            long ts = clock.instant().getEpochSecond();
            String nonce = UUID.randomUUID().toString();
            String canonical = CanonicalRequest.of("POST", PATH, String.valueOf(ts), nonce,
                    BODY.getBytes(StandardCharsets.UTF_8)).toCanonicalString();

            if (!header.equals(HmacAuthFilter.HEADER_CLIENT_ID)) {
                request.header(HmacAuthFilter.HEADER_CLIENT_ID, clientId);
            }
            if (!header.equals(HmacAuthFilter.HEADER_TIMESTAMP)) {
                request.header(HmacAuthFilter.HEADER_TIMESTAMP, String.valueOf(ts));
            }
            if (!header.equals(HmacAuthFilter.HEADER_NONCE)) {
                request.header(HmacAuthFilter.HEADER_NONCE, nonce);
            }
            if (!header.equals(HmacAuthFilter.HEADER_SIGNATURE)) {
                request.header(HmacAuthFilter.HEADER_SIGNATURE, HmacSigner.sign(secret, canonical));
            }

            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("AUTH_MISSING_HEADER"));
        }
    }

    @Test
    @DisplayName("時間戳偏移超過 300 秒（過去與未來都要擋）：401 AUTH_TIMESTAMP_SKEW")
    void timestampSkew_rejectedBothDirections() throws Exception {
        long now = clock.instant().getEpochSecond();

        for (long ts : new long[]{now - 400, now + 400}) {
            mockMvc.perform(signed(BODY, secret, ts, UUID.randomUUID().toString()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("AUTH_TIMESTAMP_SKEW"));
        }
    }

    @Test
    @DisplayName("時間戳不是數字：401")
    void nonNumericTimestamp_rejected() throws Exception {
        mockMvc.perform(withOverride(HmacAuthFilter.HEADER_TIMESTAMP, "not-a-number"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TIMESTAMP_SKEW"));
    }

    @Test
    @DisplayName("同一 nonce 送第二次：401 AUTH_NONCE_REPLAY")
    void nonceReplay_rejected() throws Exception {
        long ts = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();

        mockMvc.perform(signed(BODY, secret, ts, nonce)).andExpect(status().isOk());
        mockMvc.perform(signed(BODY, secret, ts, nonce))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_NONCE_REPLAY"));
    }

    @Test
    @DisplayName("不存在的 client id：回傳與簽章錯誤相同的碼，避免被列舉")
    void unknownClientId_returnsSameCodeAsBadSignature() throws Exception {
        clientId = "cli_doesnotexist00000";

        mockMvc.perform(validRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_SIGNATURE"));
    }

    @Test
    @DisplayName("DISABLED 與 REVOKED 的 client：401 AUTH_CLIENT_DISABLED")
    void disabledOrRevokedClient_rejected() throws Exception {
        clientService.disable(clientId, "test");
        mockMvc.perform(validRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_CLIENT_DISABLED"));

        clientService.revoke(clientId, "test");
        mockMvc.perform(validRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_CLIENT_DISABLED"));
    }

    @Test
    @DisplayName("body 超過 64KB：413 PAYLOAD_TOO_LARGE")
    void oversizedBody_rejected() throws Exception {
        String huge = "{\"x\":\"" + "a".repeat(HmacAuthFilter.MAX_BODY_BYTES + 100) + "\"}";

        mockMvc.perform(signed(huge, secret, clock.instant().getEpochSecond(),
                        UUID.randomUUID().toString()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }

    // --------------------------------------------------------- 副作用檢查

    @Test
    @DisplayName("驗簽失敗的請求不得污染 nonce 表 —— 否則任何人都能灌爆它")
    void failedAuth_doesNotWriteNonce() throws Exception {
        long before = nonceRepository.count();

        mockMvc.perform(signed(BODY, "wrong-secret",
                        clock.instant().getEpochSecond(), UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());

        assertThat(nonceRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("錯誤回應不含 stack trace 或內部細節，但帶得走 requestId")
    void errorResponse_hasNoInternalDetails() throws Exception {
        String body = mockMvc.perform(withOverride(HmacAuthFilter.HEADER_SIGNATURE, "AAAA"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.requestId").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("com.jason.notifyline")
                .doesNotContain("Exception")
                .doesNotContain("at java.");
    }

    @Test
    @DisplayName("密文損毀（解不開 secret）：回 401 而非 500，並在伺服器留下痕跡")
    void corruptedSecretCiphertext_returns401NotServerError() throws Exception {
        // 模擬資料損毀或加密 key 被換掉
        var client = clientRepository.findByClientId(clientId).orElseThrow();
        jdbcTemplate.update("UPDATE client SET secret_ciphertext = ? WHERE id = ?",
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                        13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
                        25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36},
                client.getId());

        mockMvc.perform(validRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_SIGNATURE"));
    }

    @Test
    @DisplayName("非 /api/v1 路徑不受 HMAC filter 影響")
    void nonApiPath_notFiltered() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
