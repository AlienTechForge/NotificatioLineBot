package com.jason.notifyline.admin;

import com.jason.notifyline.support.PostgresIntegrationTest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/api/monitors/import} 的整合測試。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.6、§5（W5 測試矩陣）。
 *
 * <p>重點覆蓋三項安全需求：guard 擋下時<strong>不回傳解析結果</strong>、輸入超過
 * 64 KB 被拒、回應帶 {@code Cache-Control: no-store}。
 */
@DisplayName("後台監控匯入 API（整合）")
@AutoConfigureMockMvc
class AdminMonitorImportIT extends PostgresIntegrationTest {

    private static final String ADMIN_USER = "admin";

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
    private ObjectMapper objectMapper;

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    private String importBody(String raw) {
        return objectMapper.writeValueAsString(new AdminDto.ImportMonitorRequest(raw));
    }

    // ------------------------------------------------------------ 成功案例

    @Test
    @DisplayName("cURL 貼上 → 解析成功，回應帶 Cache-Control: no-store")
    void importCurl_success_carriesNoStoreHeader() throws Exception {
        String raw = "curl 'https://example.com/api/orders' -H 'accept: application/json' "
                + "--data-raw '{\"x\":1}'";

        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(raw)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.url").value("https://example.com/api/orders"))
                .andExpect(jsonPath("$.data.method").value("POST"))
                .andExpect(jsonPath("$.data.headers.accept").value("application/json"))
                .andExpect(jsonPath("$.data.body").value("{\"x\":1}"));
    }

    @Test
    @DisplayName("fetch(...) 貼上 → 解析成功")
    void importFetch_success() throws Exception {
        String raw = "fetch(\"https://example.com/api\", {\"headers\":{\"accept\":\"application/json\"},"
                + "\"body\":null,\"method\":\"GET\"});";

        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://example.com/api"))
                .andExpect(jsonPath("$.data.method").value("GET"));
    }

    // ------------------------------------------------------------ guard 擋下：不可回傳解析結果

    @Test
    @DisplayName("解析出的 URL 指向內網位址 → guard 擋下，回錯誤，不回傳任何解析內容")
    void importBlockedByGuard_returnsErrorWithoutParsedContent() throws Exception {
        String raw = "curl 'https://127.0.0.1/admin' -H 'authorization: Bearer super-secret-abc'";

        String body = mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(raw)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn().getResponse().getContentAsString();

        // 絕不可以在錯誤回應裡看到解析出的 header 值——guard 擋下就是「什麼都不回」。
        assertThat(body).doesNotContain("super-secret-abc").doesNotContain("\"url\"");
    }

    @Test
    @DisplayName("http（非 https）→ 一樣被 guard 擋下")
    void importHttpScheme_blockedByGuard() throws Exception {
        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("curl 'http://example.com/api'")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------ 大小上限

    @Test
    @DisplayName("超過 64 KB 的輸入 → 413 拒絕")
    void oversizedInput_rejected() throws Exception {
        String huge = "curl 'https://example.com/api' -H 'x: " + "a".repeat(70_000) + "'";

        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(huge)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("剛好在 64 KB 上限內 → 允許")
    void exactlyAtLimit_allowed() throws Exception {
        // "curl 'https://example.com/api' -H 'x: " + N 個字元 + "'" 抓到剛好 <= 64 KB。
        int prefixAndSuffixLength = "curl 'https://example.com/api' -H 'x: '".length();
        String raw = "curl 'https://example.com/api' -H 'x: "
                + "a".repeat(65_536 - prefixAndSuffixLength) + "'";
        assertThat(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isEqualTo(65_536);

        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(raw)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 解析失敗

    @Test
    @DisplayName("空白輸入 → 400")
    void blankInput_rejected() throws Exception {
        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("URL 帶未跳脫空白等不合法字元 → 400，訊息附上出錯的字元與位置，使用者能照著改")
    void malformedUrl_rejectedWithActionableMessage() throws Exception {
        // 空白沒有 percent-encode，new URI(...) 會直接判定成不合法字元——藉此確認
        // 「真的解析失敗」時，訊息不再只講「不是合法 URI」，還帶得出哪裡壞了。
        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("curl 'https://example.com/a b'")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers.containsString("index")))
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers.containsString("character")));
    }

    @Test
    @DisplayName("--url 帶 curl 專屬 URL glob 跳脫（\\[ \\]）的貼上 → 解析成功，跳脫被剝掉（bug 回歸）")
    void curlGlobEscapedUrl_importsSuccessfully() throws Exception {
        // 跟回報的 bug 同樣的跳脫樣式與空值 header，改用 example.com 避免整合測試依賴
        // 外部網域的 DNS 可解析性——OutboundUrlGuard 這裡一樣會真的跑一次 DNS 查詢。
        String raw = "curl --url 'https://example.com/Shop/Order/GetList?OrderBy=CreateTime+desc"
                + "&PageIndex=1&PageSize=30&Expressionable=\\[%7B%22FieldName%22:%22CountryId%22,"
                + "%22FieldValue%22:%221620%22,%22ConditionalType%22:10%7D\\]' "
                + "-H 'accept: application/json, text/plain, */*' "
                + "-H 'sign: ' "
                + "-H 'timestamp: 1787565614'";

        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(
                        "https://example.com/Shop/Order/GetList?OrderBy=CreateTime+desc&PageIndex=1"
                                + "&PageSize=30&Expressionable=[%7B%22FieldName%22:%22CountryId%22,"
                                + "%22FieldValue%22:%221620%22,%22ConditionalType%22:10%7D]"))
                .andExpect(jsonPath("$.data.headers.sign").value(""));
    }

    // ------------------------------------------------------------ 認證

    @Test
    @DisplayName("未登入 → 401")
    void anonymous_rejected() throws Exception {
        mockMvc.perform(post("/admin/api/monitors/import").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("curl 'https://example.com/api'")))
                .andExpect(status().isUnauthorized());
    }
}
