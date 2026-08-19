package com.jason.notifyline.admin;

import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.support.PostgresIntegrationTest;
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

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理介面的認證邊界與預設對象設定。
 *
 * <p>這組測試的重點<strong>不是</strong>功能能不能用，而是「沒登入的人做不到」。
 * 管理介面能改變所有通知的流向，一個沒被擋住的端點就是整個服務的權限漏洞。
 */
@DisplayName("管理介面（整合）")
@AutoConfigureMockMvc
class AdminConsoleIT extends PostgresIntegrationTest {

    private static final String ADMIN_USER = "admin";

    @DynamicPropertySource
    static void adminCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.admin.username", () -> ADMIN_USER);
        registry.add("app.admin.password", () -> "integration-test-password");
        registry.add("app.dispatch.enabled", () -> "false");
        registry.add("app.line.api-base-url", () -> "http://127.0.0.1:1");
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
    private Clock clock;

    private String clientId;
    private String ownerId;
    private String memberId;

    @BeforeEach
    void setUp() {
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();

        ownerId = lineUser("U%032d".formatted(1), true);
        memberId = lineUser("U%032d".formatted(2), false);

        clientId = clientService.create(
                ClientService.CreateClientCommand.forService("svc", null)).clientId();
    }

    private String lineUser(String id, boolean owner) {
        LineUser user = new LineUser(id, clock.instant());
        user.setOwner(owner, clock.instant());
        user.applyProfile("使用者" + id.charAt(32), null, null, "zh-TW", clock.instant());
        lineUserRepository.save(user);
        return id;
    }

    /** 已登入的管理者。 */
    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    private String defaultTargetBody(String type, String... userIds) {
        String ids = userIds.length == 0
                ? "[]"
                : "[\"" + String.join("\",\"", userIds) + "\"]";
        String typeJson = type == null ? "null" : "\"" + type + "\"";
        return "{\"type\":%s,\"userIds\":%s}".formatted(typeJson, ids);
    }

    // ------------------------------------------------------------ 認證邊界

    @Test
    @DisplayName("未登入讀取管理 API → 401（不是 200，也不是登入頁的 HTML）")
    void anonymousApiIsRejected() throws Exception {
        mockMvc.perform(get("/admin/api/clients")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/line-users")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登入寫入管理 API → 401")
    void anonymousWriteIsRejected() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("ALL")))
                .andExpect(status().isUnauthorized());

        assertThat(clientRepository.findByClientId(clientId).orElseThrow().hasDefaultTarget())
                .isFalse();
    }

    @Test
    @DisplayName("已登入但沒有 CSRF token → 403。有 cookie 就有 CSRF 風險")
    void writeWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("ALL")))
                .andExpect(status().isForbidden());

        assertThat(clientRepository.findByClientId(clientId).orElseThrow().hasDefaultTarget())
                .isFalse();
    }

    @Test
    @DisplayName("登入頁本身不需要認證，否則沒有人進得去")
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/admin/login.html")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/assets/app.css")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("HMAC 的 /api/v1/** 完全不受管理 session 影響")
    void adminSessionDoesNotUnlockTheHmacApi() throws Exception {
        // 帶著管理者身分打 HMAC 端點，仍然要 401 —— 兩條 chain 必須互相獨立
        mockMvc.perform(get("/api/v1/whoami").with(admin()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ 讀取

    @Test
    @DisplayName("列出憑證，不含任何金鑰材料")
    void listClients() throws Exception {
        String body = mockMvc.perform(get("/admin/api/clients").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].clientId").value(clientId))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].defaultTargetType").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // 密文與 IV 絕不可出現在管理介面的回應裡
        assertThat(body).doesNotContain("secret").doesNotContain("ciphertext").doesNotContain("iv");
    }

    @Test
    @DisplayName("只列出 ACTIVE 的使用者，owner 排在前面")
    void listLineUsers() throws Exception {
        lineUserRepository.findById(memberId).ifPresent(u -> {
            u.block(clock.instant());
            lineUserRepository.save(u);
        });

        mockMvc.perform(get("/admin/api/line-users").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].lineUserId").value(ownerId))
                .andExpect(jsonPath("$.data[0].owner").value(true));
    }

    // ------------------------------------------------------------ 設定

    @Test
    @DisplayName("設定成 OWNER")
    void setOwnerTarget() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultTargetType").value("OWNER"))
                .andExpect(jsonPath("$.data.defaultTargetUserIds.length()").value(0));
    }

    @Test
    @DisplayName("設定成指定使用者，且不需要授予 notify:user")
    void setUserTarget() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("USER", memberId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultTargetType").value("USER"))
                .andExpect(jsonPath("$.data.defaultTargetUserIds[0]").value(memberId))
                // scope 沒有變 —— 管理者指定收件人不等於放寬 client 的權限
                .andExpect(jsonPath("$.data.scopes.length()").value(1))
                .andExpect(jsonPath("$.data.scopes[0]").value("notify:owner"));
    }

    @Test
    @DisplayName("type=null 清除設定")
    void clearTarget() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                .with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(defaultTargetBody("ALL")));

        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultTargetType").doesNotExist());

        assertThat(clientRepository.findByClientId(clientId).orElseThrow().hasDefaultTarget())
                .isFalse();
    }

    @Test
    @DisplayName("重複送出同一個設定結果相同（PUT 是取代語意）")
    void putIsIdempotent() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                            .with(admin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(defaultTargetBody("USER", memberId)))
                    .andExpect(status().isOk());
        }
        assertThat(clientRepository.findByClientId(clientId).orElseThrow()
                .getDefaultTargetUserIds()).containsExactly(memberId);
    }

    // ------------------------------------------------------------ 驗證

    @Test
    @DisplayName("USER 但沒給收件人 → 400")
    void userTypeWithoutRecipients() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("收件人不存在 → 400，且訊息指得出是哪一個")
    void unknownRecipientRejected() throws Exception {
        String ghost = "U%032d".formatted(9);

        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("USER", ghost)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString(ghost)));
    }

    @Test
    @DisplayName("已封鎖的使用者不能被設為收件人 —— 設定當下就擋，不要等到發送才失敗")
    void blockedRecipientRejected() throws Exception {
        lineUserRepository.findById(memberId).ifPresent(u -> {
            u.block(clock.instant());
            lineUserRepository.save(u);
        });

        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("USER", memberId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("不存在的 client → 404")
    void unknownClient() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", "cli_nope")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("OWNER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("格式不合的 LINE user id → 400，擋在 bean validation")
    void malformedUserId() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultTargetBody("USER", "not-a-line-id")))
                .andExpect(status().isBadRequest());
    }
}
