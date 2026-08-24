package com.jason.notifyline.admin;

import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.monitor.domain.ApiMonitor;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.monitor.domain.SeenItemRepository;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 後台監控 API（{@code Docs/plan/11-API監控輪詢設計.md} §10）的整合測試。
 *
 * <p>重點覆蓋 W4 的三項安全需求：{@code /monitors/test} 走
 * {@code OutboundUrlGuard}、header 密文用正確的 id 當 AAD（否則解密會直接拋例外，
 * 而不是得到錯誤明文）、以及回應不外洩任何密文／IV／解密後的 header 值。
 *
 * <p>試跑成功時帶原始回應 body 這一塊（{@code Docs/plan/12-API監控易用性升級.md} §4.2 的
 * 刻意放寬）需要真的打得到目標 API，這裡的 guard 是真的（上面這段安全需求就是在測它），
 * 沒有 no-op 的空間——那一塊測試獨立放在 {@link AdminMonitorTestEndpointIT}，
 * 用另一個 context（把 guard 換成 no-op）跑，避免污染這裡驗證 guard 真的擋下的案例。
 */
@DisplayName("後台監控 API（整合）")
@AutoConfigureMockMvc
class AdminMonitorIT extends PostgresIntegrationTest {

    private static final String ADMIN_USER = "admin";

    @DynamicPropertySource
    static void adminCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.admin.username", () -> ADMIN_USER);
        registry.add("app.admin.password", () -> "integration-test-password");
        registry.add("app.dispatch.enabled", () -> "false");
        // 直接呼叫 admin API，不需要背景排程搶著取件。
        registry.add("app.monitor.enabled", () -> "false");
        registry.add("app.line.api-base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ClientService clientService;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private ApiMonitorRepository monitors;
    @Autowired
    private ApiMonitorRunRepository monitorRuns;
    @Autowired
    private SeenItemRepository seenItems;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private SecretCipher secretCipher;
    @Autowired
    private ObjectMapper objectMapper;

    private String clientId;

    @BeforeEach
    void setUp() {
        cleanUp();
        clientId = clientService.create(ClientService.CreateClientCommand.forService("svc", null)).clientId();
    }

    /** 理由同 {@code ApiMonitorStoreIT}：整個 JVM 共用同一個 Postgres 容器。 */
    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        seenItems.deleteAll();
        monitorRuns.deleteAll();
        monitors.deleteAll();
        deliveries.deleteAll();
        notifications.deleteAll();
        clientRepository.deleteAll();
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    private String createBody(String clientId, int intervalSeconds, Map<String, String> headers) {
        AdminDto.CreateMonitorRequest req = new AdminDto.CreateMonitorRequest(
                "monitor A", clientId, "https://target.example/api", "GET", null, headers,
                intervalSeconds, true, CompareMode.WHOLE_BODY, List.of(), null, null,
                "{{value.x}}", true, 0, null);
        return objectMapper.writeValueAsString(req);
    }

    private String updateBody(String clientId, int intervalSeconds, Map<String, String> headers) {
        AdminDto.UpdateMonitorRequest req = new AdminDto.UpdateMonitorRequest(
                "monitor A", clientId, "https://target.example/api", "GET", null, headers,
                intervalSeconds, true, CompareMode.WHOLE_BODY, List.of(), null, null,
                "{{value.x}}", true, 0, null);
        return objectMapper.writeValueAsString(req);
    }

    private Long createMonitorViaApi(Map<String, String> headers) throws Exception {
        String body = mockMvc.perform(post("/admin/api/monitors")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(clientId, 60, headers)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private Long idOf(String responseBody) {
        return objectMapper.readTree(responseBody).get("data").get("id").asLong();
    }

    // ------------------------------------------------------------ CRUD 基本行為

    @Test
    @DisplayName("建立 → 出現在列表，狀態正常，host 從網址解析出來")
    void createAndList() throws Exception {
        createMonitorViaApi(null);

        mockMvc.perform(get("/admin/api/monitors").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("monitor A"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].consecutiveFailures").value(0))
                .andExpect(jsonPath("$.data[0].host").value("target.example"));
    }

    @Test
    @DisplayName("更新：整份取代設定欄位")
    void update() throws Exception {
        Long id = createMonitorViaApi(null);

        AdminDto.UpdateMonitorRequest req = new AdminDto.UpdateMonitorRequest(
                "monitor A renamed", clientId, "https://target.example/api2", "GET", null, null,
                90, true, CompareMode.WHOLE_BODY, List.of(), null, null,
                "{{value.y}}", true, 0, null);

        mockMvc.perform(put("/admin/api/monitors/{id}", id)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("monitor A renamed"))
                .andExpect(jsonPath("$.data.intervalSeconds").value(90))
                .andExpect(jsonPath("$.data.url").value("https://target.example/api2"));
    }

    @Test
    @DisplayName("更新不存在的監控 → 404")
    void updateUnknown_notFound() throws Exception {
        mockMvc.perform(put("/admin/api/monitors/{id}", 999_999L)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(clientId, 60, null)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("啟用／停用")
    void toggleEnabled() throws Exception {
        Long id = createMonitorViaApi(null);

        mockMvc.perform(post("/admin/api/monitors/{id}/enabled", id)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(monitors.findById(id).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("刪除：連帶清掉 run 與 seen_item（ON DELETE CASCADE），不可回復")
    void deleteMonitor_removesRow() throws Exception {
        Long id = createMonitorViaApi(null);

        mockMvc.perform(delete("/admin/api/monitors/{id}", id).with(admin()).with(csrf()))
                .andExpect(status().isOk());

        assertThat(monitors.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("最近執行紀錄：目前沒有任何紀錄回空陣列")
    void runsEmpty() throws Exception {
        Long id = createMonitorViaApi(null);

        mockMvc.perform(get("/admin/api/monitors/{id}/runs", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("監控相關端點未登入一律 401")
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/admin/api/monitors")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/api/monitors").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(clientId, 60, null)))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ 修正 #2：間隔下限

    @Test
    @DisplayName("intervalSeconds 低於設定下限（預設 60 秒）→ 400，不寫入")
    void createBelowIntervalFloor_rejected() throws Exception {
        mockMvc.perform(post("/admin/api/monitors")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(clientId, 30, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(monitors.findAll()).isEmpty();
    }

    @Test
    @DisplayName("更新時 intervalSeconds 低於下限 → 400，既有設定不變")
    void updateBelowIntervalFloor_rejected() throws Exception {
        Long id = createMonitorViaApi(null);

        mockMvc.perform(put("/admin/api/monitors/{id}", id)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(clientId, 30, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(monitors.findById(id).orElseThrow().getIntervalSeconds()).isEqualTo(60);
    }

    // ------------------------------------------------------------ header 密文：AAD 陷阱、留空不變更

    @Test
    @DisplayName("建立：密文用正確的 id 當 AAD 加密，能用同一組 AAD 解回原始 header")
    void create_headerCiphertextDecryptsCorrectly() throws Exception {
        Long id = createMonitorViaApi(Map.of("Authorization", "Bearer secret-token-abc"));

        ApiMonitor stored = monitors.findById(id).orElseThrow();
        assertThat(stored.getHeadersCiphertext()).isNotNull();
        assertThat(stored.getHeadersIv()).isNotNull();

        // 用「monitor:{id}」當 AAD 解密——如果建立時誤用還是 null 的 id 加密（AAD 陷阱），
        // SecretCipher.decrypt 這裡會直接拋 SecretCipherException，而不是得到錯誤的明文，
        // 所以這個測試本身就是 AAD 順序正確與否的證明。
        String plaintext = secretCipher.decrypt(
                stored.getHeadersCiphertext(), stored.getHeadersIv(), stored.getHeadersKeyVersion(),
                "monitor:" + id);
        assertThat(plaintext).contains("Authorization").contains("Bearer secret-token-abc");
    }

    @Test
    @DisplayName("編輯：header 留空不變更既有密文；非空整份覆寫")
    void update_emptyHeadersUnchanged_nonEmptyOverwrites() throws Exception {
        Long id = createMonitorViaApi(Map.of("Authorization", "Bearer original-token"));
        ApiMonitor before = monitors.findById(id).orElseThrow();
        byte[] ciphertextBefore = before.getHeadersCiphertext();
        byte[] ivBefore = before.getHeadersIv();

        // 留空 = 不變更
        mockMvc.perform(put("/admin/api/monitors/{id}", id)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(clientId, 60, null)))
                .andExpect(status().isOk());

        ApiMonitor afterEmptyUpdate = monitors.findById(id).orElseThrow();
        assertThat(afterEmptyUpdate.getHeadersCiphertext()).isEqualTo(ciphertextBefore);
        assertThat(afterEmptyUpdate.getHeadersIv()).isEqualTo(ivBefore);
        String stillOriginal = secretCipher.decrypt(afterEmptyUpdate.getHeadersCiphertext(),
                afterEmptyUpdate.getHeadersIv(), afterEmptyUpdate.getHeadersKeyVersion(), "monitor:" + id);
        assertThat(stillOriginal).contains("original-token");

        // 非空 = 整份覆寫
        mockMvc.perform(put("/admin/api/monitors/{id}", id)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(clientId, 60, Map.of("X-New-Header", "new-value-xyz"))))
                .andExpect(status().isOk());

        ApiMonitor afterOverwrite = monitors.findById(id).orElseThrow();
        assertThat(afterOverwrite.getHeadersCiphertext()).isNotEqualTo(ciphertextBefore);
        String overwritten = secretCipher.decrypt(afterOverwrite.getHeadersCiphertext(),
                afterOverwrite.getHeadersIv(), afterOverwrite.getHeadersKeyVersion(), "monitor:" + id);
        assertThat(overwritten).contains("X-New-Header").contains("new-value-xyz");
    }

    @Test
    @DisplayName("列表／執行紀錄回應都不含密文、IV 或解密後的 header 值")
    void responsesNeverLeakHeaderSecrets() throws Exception {
        Long id = createMonitorViaApi(Map.of("Authorization", "Bearer super-secret-leak-check"));

        String listBody = mockMvc.perform(get("/admin/api/monitors").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody)
                .doesNotContain("ciphertext")
                .doesNotContain("headersIv")
                .doesNotContain("super-secret-leak-check");

        String runsBody = mockMvc.perform(get("/admin/api/monitors/{id}/runs", id).with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(runsBody).doesNotContain("ciphertext").doesNotContain("super-secret-leak-check");
    }

    // ------------------------------------------------------------ 試跑：安全關鍵，必須走 OutboundUrlGuard

    @Test
    @DisplayName("試跑：guard 擋下內網位址 → ok=false，且不寫入任何監控")
    void test_blockedPrivateAddress_rejectedWithoutWritingState() throws Exception {
        AdminDto.MonitorTestRequest req = new AdminDto.MonitorTestRequest(
                "dry run", "https://127.0.0.1/admin", "GET", null, null,
                CompareMode.WHOLE_BODY, List.of(), null, null, "{{value.x}}");

        mockMvc.perform(post("/admin/api/monitors/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.failureReason").value("BLOCKED_URL"))
                .andExpect(jsonPath("$.data.renderedMessage").doesNotExist());

        assertThat(monitors.findAll()).isEmpty();
    }

    @Test
    @DisplayName("試跑：http（非 https）一樣被擋")
    void test_httpScheme_rejected() throws Exception {
        AdminDto.MonitorTestRequest req = new AdminDto.MonitorTestRequest(
                "dry run", "http://example.com/api", "GET", null, null,
                CompareMode.WHOLE_BODY, List.of(), null, null, "{{value.x}}");

        mockMvc.perform(post("/admin/api/monitors/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.failureReason").value("BLOCKED_URL"));
    }

    @Test
    @DisplayName("試跑未登入 → 401")
    void test_anonymous_rejected() throws Exception {
        mockMvc.perform(post("/admin/api/monitors/test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"compareMode\":\"WHOLE_BODY\","
                                + "\"messageTemplate\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ client 驗證（安全需求 #5）

    @Test
    @DisplayName("clientId 不存在 → 400")
    void createWithUnknownClient_rejected() throws Exception {
        mockMvc.perform(post("/admin/api/monitors")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("cli_does_not_exist", 60, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("clientId 非 ACTIVE（已作廢）→ 400")
    void createWithRevokedClient_rejected() throws Exception {
        var issued = clientService.create(ClientService.CreateClientCommand.forService("to-revoke", null));
        clientService.revoke(issued.clientId(), "test");

        mockMvc.perform(post("/admin/api/monitors")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(issued.clientId(), 60, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------ extract rule 驗證

    @Test
    @DisplayName("extract rule 名稱不合法（含空白與驚嘆號）→ 400")
    void createWithInvalidExtractRuleName_rejected() throws Exception {
        AdminDto.CreateMonitorRequest req = new AdminDto.CreateMonitorRequest(
                "monitor A", clientId, "https://target.example/api", "GET", null, null,
                60, true, CompareMode.EXTRACTED,
                List.of(new ExtractRule("bad name!", "/status")),
                null, null, "{{value.x}}", true, 0, null);

        mockMvc.perform(post("/admin/api/monitors")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("NEW_ITEMS 缺 item pointer → 400（entity 建構子擋下）")
    void createNewItemsWithoutPointers_rejected() throws Exception {
        AdminDto.CreateMonitorRequest req = new AdminDto.CreateMonitorRequest(
                "monitor A", clientId, "https://target.example/api", "GET", null, null,
                60, true, CompareMode.NEW_ITEMS, List.of(),
                null, null, "{{item.x}}", true, 0, null);

        mockMvc.perform(post("/admin/api/monitors")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
