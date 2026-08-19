package com.jason.notifyline.auth;

import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 呼叫端速率限制。見缺口 G2。
 *
 * <p>只靠「事後停用失控 client」不夠 —— 額度已經燒掉了。這是預防層。
 */
@DisplayName("RateLimitFilter（整合）")
@AutoConfigureMockMvc
@Import(RateLimitFilterIT.TestEndpoint.class)
class RateLimitFilterIT extends PostgresIntegrationTest {

    static final String PATH = "/api/v1/rl-echo";
    private static final String BODY = "{}";

    @TestConfiguration
    @RestController
    static class TestEndpoint {
        @PostMapping(PATH)
        public java.util.Map<String, String> echo() {
            return java.util.Map.of("ok", "true");
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

    private String clientId;
    private String secret;

    @BeforeEach
    void setUp() {
        nonceRepository.deleteAll();
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();
    }

    private void createClientWithLimit(Integer limitPerMinute) {
        ClientService.IssuedClient issued = clientService.create(
                new ClientService.CreateClientCommand(
                        "rl-test", null, Set.of(Scope.NOTIFY_OWNER), limitPerMinute, null, false));
        clientId = issued.clientId();
        secret = issued.secret();
    }

    private MockHttpServletRequestBuilder signed() {
        long ts = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String canonical = CanonicalRequest
                .of("POST", PATH, String.valueOf(ts), nonce, BODY.getBytes(StandardCharsets.UTF_8))
                .toCanonicalString();

        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HmacAuthFilter.HEADER_CLIENT_ID, clientId)
                .header(HmacAuthFilter.HEADER_TIMESTAMP, String.valueOf(ts))
                .header(HmacAuthFilter.HEADER_NONCE, nonce)
                .header(HmacAuthFilter.HEADER_SIGNATURE, HmacSigner.sign(secret, canonical))
                .content(BODY);
    }

    @Test
    @DisplayName("超過 client 自訂的每分鐘上限：429 RATE_LIMITED 並附 Retry-After")
    void exceedingPerClientLimit_returns429WithRetryAfter() throws Exception {
        createClientWithLimit(3);

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(signed()).andExpect(status().isOk());
        }

        mockMvc.perform(signed())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("被速率限制擋下時不會執行到實際端點")
    void rateLimited_doesNotReachEndpoint() throws Exception {
        createClientWithLimit(1);

        mockMvc.perform(signed()).andExpect(status().isOk());
        mockMvc.perform(signed())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("未設定 rate_limit_per_min 時用系統預設（60/min），一般用量不受影響")
    void nullLimit_usesSystemDefault() throws Exception {
        createClientWithLimit(null);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(signed()).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("速率限制以 client 為單位，互不影響")
    void limitIsPerClient() throws Exception {
        createClientWithLimit(1);
        String firstClient = clientId;
        String firstSecret = secret;

        mockMvc.perform(signed()).andExpect(status().isOk());
        mockMvc.perform(signed()).andExpect(status().isTooManyRequests());

        // 換另一組憑證，應該不受前一個 client 的用量影響
        createClientWithLimit(1);
        mockMvc.perform(signed()).andExpect(status().isOk());

        // 第一個 client 仍在限額中
        clientId = firstClient;
        secret = firstSecret;
        mockMvc.perform(signed()).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("錯誤回應仍是統一信封，且帶 requestId")
    void rateLimitedResponse_usesStandardEnvelope() throws Exception {
        createClientWithLimit(1);
        mockMvc.perform(signed()).andExpect(status().isOk());

        mockMvc.perform(signed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.requestId").exists());
    }
}
