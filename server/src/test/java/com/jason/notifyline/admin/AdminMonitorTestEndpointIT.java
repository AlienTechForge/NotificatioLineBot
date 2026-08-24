package com.jason.notifyline.admin;

import com.jason.notifyline.monitor.MonitorProperties;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.domain.ApiMonitorRunRepository;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.fetch.DnsResolver;
import com.jason.notifyline.monitor.fetch.OutboundUrlGuard;
import com.jason.notifyline.support.PostgresIntegrationTest;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
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
import java.util.List;
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
}
