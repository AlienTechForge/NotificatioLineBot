package com.jason.notifyline.admin;

import com.jason.notifyline.support.PostgresIntegrationTest;
import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.monitor.login.MonitorLogin;
import com.jason.notifyline.monitor.login.MonitorLoginRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * 後台站台登入 API（{@code Docs/plan/15-監控站台登入設計.md} §9）的整合測試。
 *
 * <p><strong>這裡的重點只有一個：密碼只能往一個方向流。</strong>它從建立請求進來、
 * 加密後落地，之後<strong>沒有任何一條 API 路徑能把它讀回去</strong>。這是整份設計裡
 * 最不能出錯的性質，而它只有在真的把 HTTP 回應撈出來逐字檢查時才驗得到——單元測試
 * 看不到序列化後的 JSON 長什麼樣。
 *
 * <p>schema 與 entity 是否吻合不需要另外測：{@code ddl-auto: validate} 會在
 * context 啟動時就擋下不一致，這個類別能跑起來本身就是那個保證。
 */
@DisplayName("後台站台登入 API（整合）")
@AutoConfigureMockMvc
class AdminLoginIT extends PostgresIntegrationTest {

    private static final String ADMIN_USER = "admin";
    private static final String PASSWORD = "s3cret-p@ssw0rd-not-in-any-response";

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
    private SecretCipher secretCipher;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        logins.deleteAll();
    }

    @AfterEach
    void tearDown() {
        logins.deleteAll();
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    private String createBody(String name) {
        return objectMapper.writeValueAsString(new AdminDto.CreateLoginRequest(
                name, null, "eu-west-2", "eu-west-2_FhQHPoX2z", "1h3khfsa958g8qa0gge2dnqvka",
                "student@example.com", PASSWORD, "Authorization", "{token}"));
    }

    private Long createLogin(String name) throws Exception {
        String body = mockMvc.perform(post("/admin/api/logins").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("id").asLong();
    }

    @Nested
    @DisplayName("密碼只能往一個方向流")
    class PasswordNeverLeaks {

        @Test
        @DisplayName("建立的回應不含密碼")
        void createResponseHasNoPassword() throws Exception {
            String body = mockMvc.perform(post("/admin/api/logins").with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(createBody("cas")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.hasPassword").value(true))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(PASSWORD);
        }

        @Test
        @DisplayName("列表的回應不含密碼")
        void listResponseHasNoPassword() throws Exception {
            createLogin("cas");

            String body = mockMvc.perform(get("/admin/api/logins").with(admin()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(PASSWORD);
        }

        @Test
        @DisplayName("落地的是密文，不是明文")
        void storedCiphertextIsNotPlaintext() throws Exception {
            Long id = createLogin("cas");
            MonitorLogin stored = logins.findById(id).orElseThrow();

            assertThat(new String(stored.getPasswordCiphertext())).doesNotContain(PASSWORD);

            // AAD 綁在 id 上——用對的 AAD 才解得開，這同時證明了建立流程沒有踩到
            // 「用還是 null 的 id 加密」那個陷阱（見 MonitorLogin 的 AAD 陷阱）
            String decrypted = secretCipher.decrypt(stored.getPasswordCiphertext(), stored.getPasswordIv(),
                    stored.getPasswordKeyVersion(), "monitor_login:" + id + ":password");
            assertThat(decrypted).isEqualTo(PASSWORD);
        }
    }

    @Nested
    @DisplayName("設定驗證")
    class Validation {

        @Test
        @DisplayName("region 與 userPoolId 前綴不一致 → 400")
        void mismatchedRegionRejected() throws Exception {
            String body = objectMapper.writeValueAsString(new AdminDto.CreateLoginRequest(
                    "bad", null, "us-east-1", "eu-west-2_FhQHPoX2z", "abc123",
                    "a@example.com", PASSWORD, null, null));

            mockMvc.perform(post("/admin/api/logins").with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("header 模板沒有 {token} → 400")
        void templateWithoutTokenRejected() throws Exception {
            String body = objectMapper.writeValueAsString(new AdminDto.CreateLoginRequest(
                    "bad", null, "eu-west-2", "eu-west-2_FhQHPoX2z", "abc123",
                    "a@example.com", PASSWORD, "Authorization", "Bearer nothing-here"));

            mockMvc.perform(post("/admin/api/logins").with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("名稱重複 → 400")
        void duplicateNameRejected() throws Exception {
            createLogin("cas");

            mockMvc.perform(post("/admin/api/logins").with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(createBody("cas")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("編輯")
    class Update {

        @Test
        @DisplayName("密碼留空 = 不變更")
        void blankPasswordKeepsExisting() throws Exception {
            Long id = createLogin("cas");
            byte[] before = logins.findById(id).orElseThrow().getPasswordCiphertext();

            String body = objectMapper.writeValueAsString(new AdminDto.UpdateLoginRequest(
                    "cas renamed", "eu-west-2", "eu-west-2_FhQHPoX2z", "1h3khfsa958g8qa0gge2dnqvka",
                    "student@example.com", "", "Authorization", "{token}", true));

            mockMvc.perform(put("/admin/api/logins/" + id).with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("cas renamed"));

            assertThat(logins.findById(id).orElseThrow().getPasswordCiphertext()).isEqualTo(before);
        }

        @Test
        @DisplayName("換帳號會清掉既有 token —— 舊 token 屬於舊帳號")
        void changingUsernameClearsTokens() throws Exception {
            Long id = createLogin("cas");

            // 先塞一個假的 token 快取，模擬已經登入過
            MonitorLogin login = logins.findById(id).orElseThrow();
            var fake = secretCipher.encrypt("fake-id-token", "monitor_login:" + id + ":token");
            login.applyTokens(fake.ciphertext(), fake.iv(), fake.keyVersion(),
                    java.time.Instant.now().plusSeconds(3600), null, null, null, java.time.Instant.now());
            logins.save(login);
            assertThat(logins.findById(id).orElseThrow().getTokenCiphertext()).isNotNull();

            String body = objectMapper.writeValueAsString(new AdminDto.UpdateLoginRequest(
                    "cas", "eu-west-2", "eu-west-2_FhQHPoX2z", "1h3khfsa958g8qa0gge2dnqvka",
                    "somebody-else@example.com", "", "Authorization", "{token}", true));

            mockMvc.perform(put("/admin/api/logins/" + id).with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            assertThat(logins.findById(id).orElseThrow().getTokenCiphertext()).isNull();
        }
    }

    @Test
    @DisplayName("刪除後就查不到了")
    void deleteRemovesRow() throws Exception {
        Long id = createLogin("cas");

        mockMvc.perform(delete("/admin/api/logins/" + id).with(admin()).with(csrf()))
                .andExpect(status().isOk());

        assertThat(logins.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("未登入一律擋下")
    void requiresAdmin() throws Exception {
        mockMvc.perform(get("/admin/api/logins"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("列表依名稱排序，方便在監控表單的下拉裡找")
    void listIsOrderedByName() throws Exception {
        createLogin("zulu");
        createLogin("alpha");

        String body = mockMvc.perform(get("/admin/api/logins").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var names = objectMapper.readTree(body).get("data").valueStream()
                .map(node -> node.get("name").asString()).toList();
        assertThat(names).isEqualTo(List.of("alpha", "zulu"));
    }
}
