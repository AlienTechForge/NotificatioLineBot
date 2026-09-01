package com.jason.notifyline.admin;

import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.notification.domain.NotificationDeliveryRepository;
import com.jason.notifyline.notification.domain.NotificationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private NotificationRepository notifications;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private Clock clock;

    private String clientId;
    private String ownerId;
    private String memberId;

    @BeforeEach
    void setUp() {
        // notification 有 FK 指向 client，先清才能刪 client
        deliveries.deleteAll();
        notifications.deleteAll();
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

    // ------------------------------------------------------------ 憑證 CRUD

    @Test
    @DisplayName("建立 SERVICE 憑證：201，回明文 secret 只此一次")
    void createServiceClient() throws Exception {
        mockMvc.perform(post("/admin/api/clients")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci\",\"kind\":\"SERVICE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.clientId").value(org.hamcrest.Matchers.startsWith("cli_")))
                .andExpect(jsonPath("$.data.secret").isNotEmpty())
                .andExpect(jsonPath("$.data.scopes[0]").value("notify:owner"));
    }

    @Test
    @DisplayName("建立 OWNER 憑證缺 lineUserId → 400")
    void createOwnerClientWithoutUser() throws Exception {
        mockMvc.perform(post("/admin/api/clients")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin\",\"kind\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("建立憑證未登入 → 401")
    void createClientAnonymous() throws Exception {
        mockMvc.perform(post("/admin/api/clients").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"kind\":\"SERVICE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("作廢憑證：狀態變 REVOKED")
    void revokeClient() throws Exception {
        mockMvc.perform(delete("/admin/api/clients/{id}", clientId)
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(clientRepository.findByClientId(clientId).orElseThrow().getStatus().name())
                .isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("作廢未登入 → 401，且狀態不變")
    void revokeAnonymous() throws Exception {
        mockMvc.perform(delete("/admin/api/clients/{id}", clientId).with(csrf()))
                .andExpect(status().isUnauthorized());
        assertThat(clientRepository.findByClientId(clientId).orElseThrow().getStatus().name())
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------ 使用者 owner

    @Test
    @DisplayName("切換 owner")
    void toggleOwner() throws Exception {
        mockMvc.perform(put("/admin/api/line-users/{id}/owner", memberId)
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.owner").value(true));
        assertThat(lineUserRepository.findById(memberId).orElseThrow().isOwner()).isTrue();
    }

    @Test
    @DisplayName("對不存在的使用者設 owner → 400")
    void setOwnerUnknownUser() throws Exception {
        mockMvc.perform(put("/admin/api/line-users/{id}/owner", "U%032d".formatted(9))
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":true}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ 儀表板 / 紀錄

    @Test
    @DisplayName("儀表板統計：未登入 401，登入回計數")
    void statsRequireAuth() throws Exception {
        mockMvc.perform(get("/admin/api/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/stats").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientsTotal").isNumber())
                .andExpect(jsonPath("$.data.usersActive").value(2));
    }

    @Test
    @DisplayName("近期發送列表：未登入 401")
    void recentRequiresAuth() throws Exception {
        mockMvc.perform(get("/admin/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/notifications").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ------------------------------------------------------------ 後台發送

    @Test
    @DisplayName("後台發測試通知：202，寫入一筆 QUEUED")
    void sendTest() throws Exception {
        // 讓 clientId 這組 SERVICE 憑證有預設 OWNER 對象（回填 migration 已設，但測試自建的沒有）
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                .with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(defaultTargetBody("OWNER")));

        String body = "{\"clientId\":\"" + clientId + "\",\"text\":\"後台測試\"}";
        mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.recipientCount").value(1));
    }

    // ------------------------------------------------------- 發送紀錄的內容

    @Test
    @DisplayName("發送紀錄列表帶內容節錄：title 與 text 都看得到，換行壓成空白")
    void historyListShowsPreview() throws Exception {
        setOwnerDefaultTarget();
        mockMvc.perform(post("/admin/api/notifications/test")
                .with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"" + clientId
                        + "\",\"title\":\"部署完成\",\"text\":\"版本 1.2.3\"}"));

        mockMvc.perform(get("/admin/api/notifications").with(admin()))
                .andExpect(status().isOk())
                // 內容是使用者實際收到的訊息，不可被快取留存
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data[0].preview").value("部署完成 版本 1.2.3"));
    }

    @Test
    @DisplayName("發送明細帶完整內容：文字訊息回內文與原始 message object")
    void detailShowsContent() throws Exception {
        setOwnerDefaultTarget();
        String id = idOf(mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"" + clientId
                                + "\",\"title\":\"部署完成\",\"text\":\"版本 1.2.3\"}"))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/admin/api/notifications/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.data.clientName").value("svc"))
                .andExpect(jsonPath("$.data.content.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.content.notificationDisabled").value(false))
                .andExpect(jsonPath("$.data.content.messages.length()").value(1))
                .andExpect(jsonPath("$.data.content.messages[0].type").value("text"))
                // title 渲染成首行，與實際送給 LINE 的內容一致（見 MessageAssembler）
                .andExpect(jsonPath("$.data.content.messages[0].text").value("部署完成\n版本 1.2.3"))
                .andExpect(jsonPath("$.data.content.messages[0].json").isNotEmpty());
    }

    @Test
    @DisplayName("內容被清空的紀錄：其餘欄位照常，content 說明原因而不是報錯")
    void detailWithClearedPayload() throws Exception {
        setOwnerDefaultTarget();
        String id = idOf(mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"" + clientId + "\",\"text\":\"會被清掉\"}"))
                .andReturn().getResponse().getContentAsString());

        // 模擬 persistPayload=false 送完即清，或過了 90 天保留期被清空
        var notification = notifications.findById(java.util.UUID.fromString(id)).orElseThrow();
        notification.clearPayload();
        notifications.save(notification);

        mockMvc.perform(get("/admin/api/notifications/{id}", id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.status").value("CLEARED"))
                .andExpect(jsonPath("$.data.content.messages").isEmpty())
                .andExpect(jsonPath("$.data.recipientCount").value(1));

        mockMvc.perform(get("/admin/api/notifications").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].preview").doesNotExist());
    }

    @Test
    @DisplayName("發送明細未登入 → 401（內容不會外流給沒登入的人）")
    void detailRequiresAuth() throws Exception {
        mockMvc.perform(get("/admin/api/notifications/{id}", java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("查不存在的發送明細 → 404")
    void detailUnknownNotification() throws Exception {
        mockMvc.perform(get("/admin/api/notifications/{id}", java.util.UUID.randomUUID())
                        .with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("後台發送未登入 → 401")
    void sendTestAnonymous() throws Exception {
        mockMvc.perform(post("/admin/api/notifications/test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"x\",\"text\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ LINE 配額

    @Test
    @DisplayName("LINE 配額：未登入 401；測試環境打不到 LINE，回 available=false 而不是 500")
    void lineQuota() throws Exception {
        mockMvc.perform(get("/admin/api/line-quota")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/api/line-quota").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.limit").doesNotExist())
                .andExpect(jsonPath("$.data.used").doesNotExist());
    }

    // ------------------------------------------------------------ 排程

    private void setOwnerDefaultTarget() throws Exception {
        mockMvc.perform(put("/admin/api/clients/{id}/default-target", clientId)
                .with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(defaultTargetBody("OWNER")));
    }

    private String scheduleBody(String scheduledAtIso) {
        return "{\"clientId\":\"" + clientId + "\",\"text\":\"排程測試\",\"scheduledAt\":\""
                + scheduledAtIso + "\"}";
    }

    @Test
    @DisplayName("排程發送：202 且立刻可在排程頁看到")
    void scheduleNotification() throws Exception {
        setOwnerDefaultTarget();
        String scheduledAt = clock.instant().plusSeconds(600).toString();

        String id = idOf(mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(scheduledAt)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/admin/api/notifications/scheduled").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.notificationId=='" + id + "')]").exists())
                .andExpect(jsonPath("$.data[0].clientName").value("svc"));

        // 排程還沒到，不該被立即派送的批次也一起寫進 outbox 佇列裡佔位。
        // Postgres timestamptz 只有微秒精度，比對容許次微秒級的捨入差異。
        assertThat(deliveries.findAll()).hasSize(1);
        assertThat(deliveries.findAll().getFirst().getNextAttemptAt())
                .isCloseTo(java.time.Instant.parse(scheduledAt),
                        org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("排程時間不到 30 秒後 → 400，不會寫入")
    void scheduleTooSoonRejected() throws Exception {
        setOwnerDefaultTarget();
        String scheduledAt = clock.instant().plusSeconds(5).toString();

        mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(scheduledAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("排程時間超過一年 → 400，抓可能選錯年份")
    void scheduleTooFarRejected() throws Exception {
        setOwnerDefaultTarget();
        String scheduledAt = clock.instant().plus(java.time.Duration.ofDays(400)).toString();

        mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(scheduledAt)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("取消排程：狀態變成非 QUEUED，且從排程頁消失")
    void cancelScheduled() throws Exception {
        setOwnerDefaultTarget();
        String scheduledAt = clock.instant().plusSeconds(600).toString();
        String id = idOf(mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(scheduledAt)))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(delete("/admin/api/notifications/{id}/schedule", id)
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/api/notifications/scheduled").with(admin()))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.notificationId=='" + id + "')]").doesNotExist());

        var cancelled = notifications.findById(java.util.UUID.fromString(id)).orElseThrow();
        assertThat(cancelled.getStatus().name()).isEqualTo("FAILED");
        assertThat(deliveries.findByNotificationIdOrderByBatchNo(java.util.UUID.fromString(id))
                .getFirst().getErrorCode()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("取消不存在的通知 → 404")
    void cancelUnknownNotification() throws Exception {
        mockMvc.perform(delete("/admin/api/notifications/{id}/schedule",
                        java.util.UUID.randomUUID())
                        .with(admin()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("已經開始送的通知取消不到 → 400")
    void cancelAlreadySendingRejected() throws Exception {
        setOwnerDefaultTarget();
        String scheduledAt = clock.instant().plusSeconds(600).toString();
        String id = idOf(mockMvc.perform(post("/admin/api/notifications/test")
                        .with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleBody(scheduledAt)))
                .andReturn().getResponse().getContentAsString());

        // 模擬派送器已經把它取走在送
        var notification = notifications.findById(java.util.UUID.fromString(id)).orElseThrow();
        notification.markSending(clock.instant());
        notifications.save(notification);

        mockMvc.perform(delete("/admin/api/notifications/{id}/schedule", id)
                        .with(admin()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("排程/取消端點未登入一律 401")
    void scheduleEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/admin/api/notifications/scheduled")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/api/notifications/{id}/schedule", java.util.UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private static String idOf(String responseBody) {
        int start = responseBody.indexOf("\"notificationId\":\"") + 18;
        return responseBody.substring(start, responseBody.indexOf('"', start));
    }
}
