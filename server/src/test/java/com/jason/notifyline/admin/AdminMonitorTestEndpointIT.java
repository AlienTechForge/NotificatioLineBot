package com.jason.notifyline.admin;

import com.jason.notifyline.monitor.MonitorProperties;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ComputedField;
import com.jason.notifyline.monitor.domain.ComputedStep;
import com.jason.notifyline.monitor.domain.HashAlgorithm;
import com.jason.notifyline.monitor.domain.HashEncoding;
import com.jason.notifyline.monitor.fetch.DnsResolver;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.support.PostgresIntegrationTest;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/api/monitors/test} 成功時帶原始回應 body 這一塊的整合測試——
 * {@code Docs/plan/12-API監控易用性升級.md} §4.2 對 {@code Docs/plan/11-API監控輪詢設計.md} §10
 * 持久化路徑規則的刻意放寬，只限這個不落地、帶 {@code Cache-Control: no-store} 的端點。
 *
 * <p>獨立成自己的檔案、自己的 Spring context（見 {@link NoopGuardConfig}）——{@code guard 真的
 * 擋下危險網址}是 {@link AdminMonitorIT} 已經覆蓋的安全需求，這裡要驗證的是「guard 放行之後，
 * 成功抓到的內容怎麼流到回應」，兩者用同一個 context 會互相打架：要嘛 guard 是真的（這裡就打不到
 * loopback 的假目標 API），要嘛 guard 是假的（{@link AdminMonitorIT} 驗證「guard 真的擋下」的
 * 案例就全部失真）。分成兩個 context，各自的假設在各自的檔案裡都成立。
 */
@DisplayName("後台監控 API：試跑成功時的原始 body（整合）")
@AutoConfigureMockMvc
class AdminMonitorTestEndpointIT extends PostgresIntegrationTest {

    private static final String ADMIN_USER = "admin";

    /** 256 KB——{@code ApiMonitorTestRunner.MAX_TEST_BODY_BYTES} 的第二層上限，見該類別註解。 */
    private static final int MAX_TEST_BODY_BYTES = 256 * 1024;

    private static final MockWebServer TARGET = new MockWebServer();

    static {
        try {
            TARGET.start();
        } catch (IOException e) {
            throw new IllegalStateException("無法啟動打樁伺服器", e);
        }
    }

    @AfterAll
    static void stopTarget() throws IOException {
        TARGET.close();
    }

    @AfterEach
    void drainRequestLog() throws InterruptedException {
        while (TARGET.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // 丟掉，避免佇列殘留影響下一個測試
        }
    }

    /** 讓試跑可以打 http + loopback 的 {@link #TARGET}——理由跟 {@code ApiMonitorRunnerIT} 一樣。 */
    @TestConfiguration
    static class NoopGuardConfig {
        @Bean
        @Primary
        OutboundUrlGuard outboundUrlGuard(MonitorProperties properties, DnsResolver dnsResolver) {
            return new OutboundUrlGuard(properties, dnsResolver) {
                @Override
                public void check(URI uri) {
                    // 略過 SSRF 檢查——guard 本身「真的擋下危險網址」的行為由
                    // OutboundUrlGuardTest 與 AdminMonitorIT 覆蓋，不在這裡重複驗證。
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
    private ApiMonitorRepository monitors;
    @Autowired
    private ApiMonitorRunRepository monitorRuns;
    @Autowired
    private ObjectMapper objectMapper;

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private static AdminDto.MonitorTestRequest testRequest(String url, CompareMode mode) {
        return new AdminDto.MonitorTestRequest(
                "dry run", url, "GET", null, null, mode, List.of(), null, null, "{{value.x}}");
    }

    @Test
    @DisplayName("成功：回應帶 Cache-Control: no-store，data.body 是完整原始回應，不寫入任何監控或執行紀錄")
    void success_returnsBodyWithNoStoreHeader_andWritesNothing() throws Exception {
        String targetBody = "{\"status\":\"OK\",\"id\":42}";
        TARGET.enqueue(jsonResponse(targetBody));

        mockMvc.perform(post("/admin/api/monitors/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                testRequest(TARGET.url("/api").toString(), CompareMode.WHOLE_BODY))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.body").value(targetBody))
                .andExpect(jsonPath("$.data.bodyTruncated").value(false))
                .andExpect(jsonPath("$.data.bodyOriginalLength")
                        .value(targetBody.getBytes(StandardCharsets.UTF_8).length));

        // 試跑「不寫入任何狀態」是這個端點存在的前提——即使現在成功時會回傳 body，
        // 這條規則完全不受影響：body 只出現在這次 HTTP 回應裡，不落地。
        assertThat(monitors.findAll()).isEmpty();
        assertThat(monitorRuns.findAll()).isEmpty();
    }

    @Test
    @DisplayName("成功：回應內容超過 256 KB → data.bodyTruncated=true，bodyOriginalLength 保留真實長度，不寫入任何狀態")
    void success_bodyOverCap_isTruncatedEndToEnd() throws Exception {
        int overCapBytes = MAX_TEST_BODY_BYTES + 1000;
        String padding = "a".repeat(overCapBytes - "{\"pad\":\"\"}".length());
        String targetBody = "{\"pad\":\"" + padding + "\"}";
        TARGET.enqueue(jsonResponse(targetBody));

        String responseJson = mockMvc.perform(post("/admin/api/monitors/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                testRequest(TARGET.url("/api").toString(), CompareMode.WHOLE_BODY))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.bodyTruncated").value(true))
                .andExpect(jsonPath("$.data.bodyOriginalLength")
                        .value(targetBody.getBytes(StandardCharsets.UTF_8).length))
                .andReturn().getResponse().getContentAsString();

        String returnedBody = objectMapper.readTree(responseJson).get("data").get("body").asString();
        assertThat(returnedBody.getBytes(StandardCharsets.UTF_8).length).isEqualTo(MAX_TEST_BODY_BYTES);
        assertThat(monitors.findAll()).isEmpty();
        assertThat(monitorRuns.findAll()).isEmpty();
    }

    // ---------------------------------------------------------------- W13：試算面板顯示計算欄位

    private static String md5HexUpper(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 端到端：試算面板要能顯示計算欄位的最終值（doc 13 §5、§7），且 URL 的 {@code ts}
     * 與 header 的 {@code sign} 用同一個瞬間算出（凍結時間戳），跟
     * {@code ApiMonitorRunnerIT.frozenTimestampAndComputedSign_endToEnd} 驗證同一件事，
     * 只是這裡走的是試算路徑，不是排程輪詢路徑——兩條路徑都要有這個保證。
     */
    @Test
    @DisplayName("試算：computedValues 帶著雙重 MD5 的結果，且與實際送出的 URL/header 自洽，回應不含 secret 值")
    void computedFields_returnsComputedValues_consistentWithActualRequest() throws Exception {
        String appsecret = "YWHZ@&mxZge1A@";
        String deviceid = "2b34aabc-6d14-490e-b76a-254097095055";
        TARGET.enqueue(jsonResponse("{\"status\":\"OK\"}"));

        ComputedField signField = new ComputedField("sign",
                "{{secret.appsecret}}{{now.epochSeconds}}{{secret.deviceid}}",
                List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null),
                        new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));
        AdminDto.MonitorTestRequest req = new AdminDto.MonitorTestRequest(
                "dry run", TARGET.url("/api").toString() + "?ts={{now.epochSeconds}}", "GET", null,
                Map.of("sign", "{{computed.sign}}"), Map.of("appsecret", appsecret, "deviceid", deviceid),
                CompareMode.WHOLE_BODY, List.of(), null, null, List.of(signField), "{{value.x}}", null, null);

        String responseJson = mockMvc.perform(post("/admin/api/monitors/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andReturn().getResponse().getContentAsString();

        RecordedRequest recorded = TARGET.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        String ts = recorded.getUrl().queryParameter("ts");
        String expectedSign = md5HexUpper(md5HexUpper(appsecret + ts + deviceid));
        assertThat(recorded.getHeaders().get("sign"))
                .as("實際送出的請求：header sign 要跟 URL 的 ts 用同一個瞬間算出")
                .isEqualTo(expectedSign);

        assertThat(objectMapper.readTree(responseJson).get("data").get("computedValues").get("sign").asString())
                .as("試算面板回傳的 computedValues.sign 要跟實際送出的一致")
                .isEqualTo(expectedSign);
        assertThat(responseJson).doesNotContain(appsecret).doesNotContain(deviceid);
    }
}
