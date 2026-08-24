package com.jason.notifyline.admin;

import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.monitor.session.SiteSession;
import com.jason.notifyline.monitor.session.SiteSessionRepository;
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

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站台登入狀態（cookie jar）的後台 API 整合測試。見
 * {@code Docs/plan/12-API監控易用性升級.md} §3.4、§5（W6 測試矩陣）。
 *
 * <p>重點覆蓋兩項安全需求：{@code GET /sessions} 的回應不含任何 cookie 值／密文／IV；
 * 匯入時 {@code cookie:} header 會被抽進 jar，並從回傳給呼叫端的 header 中移除。
 */
@DisplayName("後台站台登入狀態 API（整合）")
@AutoConfigureMockMvc
class AdminSessionIT extends PostgresIntegrationTest {

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
    @Autowired
    private SiteSessionRepository sessions;
    @Autowired
    private SecretCipher secretCipher;

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    @BeforeEach
    void setUp() {
        sessions.deleteAll();
    }

    @AfterEach
    void tearDown() {
        sessions.deleteAll();
    }

    private void seedSession(String host, Map<String, String> cookies) {
        String plaintext = objectMapper.writeValueAsString(cookies);
        var encrypted = secretCipher.encrypt(plaintext, "site_session:" + host);
        sessions.save(new SiteSession(
                host, encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(),
                String.join(",", cookies.keySet()), Instant.now()));
    }

    // ------------------------------------------------------------ GET /sessions：絕不回傳值

    @Test
    @DisplayName("GET /sessions：回應含 host／cookie 名稱／數量／時間戳，絕不含 cookie 值、密文或 IV")
    void listSessions_neverExposesValuesOrCiphertext() throws Exception {
        seedSession("example.com", Map.of("session", "super-secret-token-value", "csrf", "another-secret"));

        String body = mockMvc.perform(get("/admin/api/sessions").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].host").value("example.com"))
                .andExpect(jsonPath("$.data[0].cookieCount").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("session").contains("csrf") // 名稱可以出現
                .doesNotContain("super-secret-token-value")
                .doesNotContain("another-secret")
                .doesNotContainIgnoringCase("ciphertext")
                .doesNotContainIgnoringCase("\"iv\"");
    }

    @Test
    @DisplayName("GET /sessions：cookieNames 陣列裡也只有名稱")
    void listSessions_cookieNamesArrayContainsOnlyNames() throws Exception {
        seedSession("example.com", Map.of("session", "value-should-not-appear"));

        mockMvc.perform(get("/admin/api/sessions").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cookieNames[0]").value("session"));
    }

    @Test
    @DisplayName("未登入 → 401")
    void listSessions_anonymous_rejected() throws Exception {
        mockMvc.perform(get("/admin/api/sessions"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ DELETE /sessions/{host}

    @Test
    @DisplayName("DELETE /sessions/{host}：清除該站登入狀態")
    void deleteSession_removesRow() throws Exception {
        seedSession("example.com", Map.of("session", "abc"));

        mockMvc.perform(delete("/admin/api/sessions/example.com").with(admin()).with(csrf()))
                .andExpect(status().isOk());

        assertThat(sessions.existsById("example.com")).isFalse();
    }

    @Test
    @DisplayName("DELETE /sessions/{host}：不存在的 host 回 404")
    void deleteSession_missing_returns404() throws Exception {
        mockMvc.perform(delete("/admin/api/sessions/never-existed.example").with(admin()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 匯入整合：cookie header 抽出

    private String importBody(String raw) {
        return objectMapper.writeValueAsString(new AdminDto.ImportMonitorRequest(raw));
    }

    @Test
    @DisplayName("匯入含 cookie: header 的 cURL：cookie 進 jar，且回傳給前端的 header 不再含它")
    void importWithCookieHeader_cookiesLandInJarAndAreAbsentFromReturnedHeaders() throws Exception {
        String raw = "curl 'https://example.com/api/orders' "
                + "-H 'accept: application/json' "
                + "-b 'session=super-secret-session-value; csrf=abc123'";

        String body = mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers.accept").value("application/json"))
                .andReturn().getResponse().getContentAsString();

        // 回傳給前端（將用來預填監控表單）的 header 絕不可再含 cookie 值。
        assertThat(body)
                .doesNotContainIgnoringCase("\"cookie\"")
                .doesNotContain("super-secret-session-value")
                .doesNotContain("abc123");

        // 但 cookie 確實已經寫進該 host 的 jar。
        SiteSession saved = sessions.findById("example.com").orElseThrow();
        assertThat(saved.getCookieNames().split(",")).containsExactlyInAnyOrder("session", "csrf");

        String decrypted = secretCipher.decrypt(
                saved.getJarCiphertext(), saved.getJarIv(), saved.getJarKeyVersion(), "site_session:example.com");
        assertThat(decrypted).contains("super-secret-session-value").contains("abc123");
    }

    @Test
    @DisplayName("匯入不含 cookie header 的 cURL：不建立任何登入狀態")
    void importWithoutCookieHeader_doesNotCreateSession() throws Exception {
        String raw = "curl 'https://example.com/api/orders' -H 'accept: application/json'";

        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(raw)))
                .andExpect(status().isOk());

        assertThat(sessions.findById("example.com")).isEmpty();
    }

    @Test
    @DisplayName("重新匯入同一站台的新 cookie：覆蓋舊值，過期重貼一次即可復原")
    void reimportingCookies_overwritesPreviousJar() throws Exception {
        String firstRaw = "curl 'https://example.com/api/a' -b 'session=old-value'";
        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(firstRaw)))
                .andExpect(status().isOk());

        String secondRaw = "curl 'https://example.com/api/b' -b 'session=fresh-value'";
        mockMvc.perform(post("/admin/api/monitors/import")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(secondRaw)))
                .andExpect(status().isOk());

        SiteSession saved = sessions.findById("example.com").orElseThrow();
        String decrypted = secretCipher.decrypt(
                saved.getJarCiphertext(), saved.getJarIv(), saved.getJarKeyVersion(), "site_session:example.com");
        assertThat(decrypted).contains("fresh-value").doesNotContain("old-value");
    }
}
