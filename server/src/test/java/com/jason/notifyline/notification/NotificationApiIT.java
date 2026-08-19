package com.jason.notifyline.notification;

import com.jason.notifyline.auth.CanonicalRequest;
import com.jason.notifyline.auth.HmacAuthFilter;
import com.jason.notifyline.auth.HmacSigner;
import com.jason.notifyline.auth.RequestNonceRepository;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.Scope;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知 API 的端到端行為。見 {@code Docs/plan/08-測試計畫.md} §3.3。
 *
 * <p>刻意跑在真的 PostgreSQL 上 —— 冪等靠 partial unique index、配額靠
 * {@code SELECT FOR UPDATE}、收件人靠 {@code TEXT[]}，這三樣在替代資料庫上
 * 行為都不同。
 */
@DisplayName("通知 API（整合）")
@AutoConfigureMockMvc
class NotificationApiIT extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/notifications";

    @DynamicPropertySource
    static void isolateFromLine(DynamicPropertyRegistry registry) {
        // 這組測試只驗「受理」段。自動派送會與斷言競爭，而且會真的去打 LINE ——
        // 一個測試套件在 CI 上對外發送真實訊息是最不該發生的事。
        registry.add("app.dispatch.enabled", () -> "false");
        // 萬一有人不小心把上面那行拿掉，也讓它打不到真的 LINE
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
    private RequestNonceRepository nonceRepository;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private Clock clock;

    private String ownerUserId;

    @BeforeEach
    void setUp() {
        deliveries.deleteAll();
        notifications.deleteAll();
        nonceRepository.deleteAll();
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();

        ownerUserId = activeUser("U%032d".formatted(1), true);
    }

    // ------------------------------------------------------------ 測試資料

    private String activeUser(String lineUserId, boolean owner) {
        LineUser user = new LineUser(lineUserId, clock.instant());
        user.setOwner(owner, clock.instant());
        lineUserRepository.save(user);
        return lineUserId;
    }

    private ClientService.IssuedClient serviceClient() {
        return clientService.create(ClientService.CreateClientCommand.forService("svc", null));
    }

    private ClientService.IssuedClient ownerClient(Set<Scope> scopes, Integer dailyQuota) {
        return clientService.create(new ClientService.CreateClientCommand(
                "admin", null, scopes, null, dailyQuota, true));
    }

    // ------------------------------------------------------------ 簽章工具

    private MockHttpServletRequestBuilder signedPost(ClientService.IssuedClient client,
                                                     String body,
                                                     String idempotencyKey) {
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String canonical = CanonicalRequest
                .of("POST", PATH, String.valueOf(timestamp), nonce, raw)
                .toCanonicalString();

        MockHttpServletRequestBuilder builder = post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HmacAuthFilter.HEADER_CLIENT_ID, client.clientId())
                .header(HmacAuthFilter.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(HmacAuthFilter.HEADER_NONCE, nonce)
                .header(HmacAuthFilter.HEADER_SIGNATURE, HmacSigner.sign(client.secret(), canonical))
                .content(raw);

        return idempotencyKey == null ? builder : builder.header("Idempotency-Key", idempotencyKey);
    }

    private MockHttpServletRequestBuilder signedGet(ClientService.IssuedClient client, String id) {
        String path = PATH + "/" + id;
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String canonical = CanonicalRequest
                .of("GET", path, String.valueOf(timestamp), nonce, new byte[0])
                .toCanonicalString();

        return get(path)
                .header(HmacAuthFilter.HEADER_CLIENT_ID, client.clientId())
                .header(HmacAuthFilter.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(HmacAuthFilter.HEADER_NONCE, nonce)
                .header(HmacAuthFilter.HEADER_SIGNATURE, HmacSigner.sign(client.secret(), canonical));
    }

    private static String ownerBody(String text) {
        return """
                {"target":{"type":"OWNER"},"message":{"text":"%s"}}""".formatted(text);
    }

    // ---------------------------------------------------------------- 受理

    @Test
    @DisplayName("SERVICE client 發給 OWNER：202，並寫入一筆 QUEUED 與一個批次")
    void accepted() throws Exception {
        mockMvc.perform(signedPost(serviceClient(), ownerBody("備份完成"), null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.recipientCount").value(1))
                .andExpect(jsonPath("$.data.batchCount").value(1))
                .andExpect(jsonPath("$.data.notificationId").isNotEmpty());

        assertThat(notifications.findAll()).hasSize(1);
        assertThat(deliveries.findAll()).hasSize(1);
        assertThat(deliveries.findAll().getFirst().getLineUserIds()).containsExactly(ownerUserId);
    }

    @Test
    @DisplayName("202 當下批次已經持久化 —— 程序此刻被 kill 也不掉單")
    void workIsDurableBeforeResponding() throws Exception {
        mockMvc.perform(signedPost(serviceClient(), ownerBody("x"), null))
                .andExpect(status().isAccepted());

        // outbox 的取件條件：PENDING 且 next_attempt_at <= now
        assertThat(deliveries.countPending()).isEqualTo(1);
        assertThat(deliveries.findOldestPendingAt()).isNotNull();
    }

    @Test
    @DisplayName("OWNER 批次的 priority 是 0，ALL 是 9 —— 告警不會被公告卡住")
    void priorityByTargetType() throws Exception {
        mockMvc.perform(signedPost(serviceClient(), ownerBody("告警"), null))
                .andExpect(status().isAccepted());
        assertThat(deliveries.findAll().getFirst().getPriority()).isEqualTo((short) 0);

        deliveries.deleteAll();
        notifications.deleteAll();

        var admin = ownerClient(Set.of(Scope.NOTIFY_ALL), null);
        mockMvc.perform(signedPost(admin,
                        """
                        {"target":{"type":"ALL"},"message":{"text":"公告"}}""", null))
                .andExpect(status().isAccepted());
        assertThat(deliveries.findAll().getFirst().getPriority()).isEqualTo((short) 9);
    }

    @Test
    @DisplayName("1200 人的 ALL 切成 500/500/200，每批一把不同的 retry key")
    void largeAudienceIsBatched() throws Exception {
        IntStream.range(2, 1202).forEach(i -> activeUser("U%032d".formatted(i), false));
        var admin = ownerClient(Set.of(Scope.NOTIFY_ALL), null);

        mockMvc.perform(signedPost(admin,
                        """
                        {"target":{"type":"ALL"},"message":{"text":"公告"}}""", null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.recipientCount").value(1201))
                .andExpect(jsonPath("$.data.batchCount").value(3));

        var rows = deliveries.findAll();
        assertThat(rows).extracting(d -> d.getRecipientCount())
                .containsExactlyInAnyOrder(500, 500, 201);
        assertThat(rows).extracting(d -> d.getRetryKey()).doesNotHaveDuplicates();
    }

    // -------------------------------------------------------------- 冪等

    @Test
    @DisplayName("同一把 Idempotency-Key 重送相同內容：回同一個 id，不重複建立")
    void idempotentReplay() throws Exception {
        var client = serviceClient();
        String body = ownerBody("重送測試");

        String first = mockMvc.perform(signedPost(client, body, "key-1"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(signedPost(client, body, "key-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.notificationId")
                        .value(idOf(first)))
                .andExpect(jsonPath("$.data.batchCount").value(1));

        assertThat(notifications.findAll()).hasSize(1);
        assertThat(deliveries.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("同一把 key 換了內容：409，不要悄悄回舊結果掩蓋呼叫端的 bug")
    void idempotencyConflict() throws Exception {
        var client = serviceClient();

        mockMvc.perform(signedPost(client, ownerBody("第一則"), "key-1"))
                .andExpect(status().isAccepted());

        mockMvc.perform(signedPost(client, ownerBody("第二則"), "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(notifications.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("不同 client 用同一把 key 不互相衝突")
    void idempotencyIsScopedToClient() throws Exception {
        mockMvc.perform(signedPost(serviceClient(), ownerBody("a"), "shared-key"))
                .andExpect(status().isAccepted());
        mockMvc.perform(signedPost(serviceClient(), ownerBody("b"), "shared-key"))
                .andExpect(status().isAccepted());

        assertThat(notifications.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("沒帶 Idempotency-Key 時每次都是新的一筆")
    void withoutKeyEachRequestIsNew() throws Exception {
        var client = serviceClient();
        mockMvc.perform(signedPost(client, ownerBody("x"), null)).andExpect(status().isAccepted());
        mockMvc.perform(signedPost(client, ownerBody("x"), null)).andExpect(status().isAccepted());

        assertThat(notifications.findAll()).hasSize(2);
    }

    // -------------------------------------------------------------- 權限

    @Test
    @DisplayName("SERVICE client 發 ALL：403，因為它沒有 notify:all")
    void scopeDenied() throws Exception {
        mockMvc.perform(signedPost(serviceClient(),
                        """
                        {"target":{"type":"ALL"},"message":{"text":"x"}}""", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCOPE_DENIED"));

        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    @DisplayName("沒有 notify:raw 就不能用 lineMessages")
    void rawRequiresScope() throws Exception {
        mockMvc.perform(signedPost(serviceClient(),
                        """
                        {"target":{"type":"OWNER"},"lineMessages":[{"type":"text","text":"x"}]}""",
                        null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCOPE_DENIED"));
    }

    @Test
    @DisplayName("沒有任何 ACTIVE owner 時回 400，不要送出一個 0 人的批次")
    void noRecipient() throws Exception {
        lineUserRepository.deleteAll();

        mockMvc.perform(signedPost(serviceClient(), ownerBody("x"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NO_RECIPIENT"));

        assertThat(deliveries.findAll()).isEmpty();
    }

    // -------------------------------------------------------------- 配額

    @Test
    @DisplayName("每日配額以收件人數計，超過回 429 且不寫入")
    void dailyQuotaEnforced() throws Exception {
        IntStream.range(2, 6).forEach(i -> activeUser("U%032d".formatted(i), true));
        // 5 個 owner，配額 8：第一次成功（用掉 5），第二次要 5 但只剩 3
        var admin = ownerClient(Set.of(Scope.NOTIFY_OWNER), 8);

        mockMvc.perform(signedPost(admin, ownerBody("第一批"), null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.recipientCount").value(5));

        mockMvc.perform(signedPost(admin, ownerBody("第二批"), null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("CLIENT_QUOTA_EXCEEDED"));

        assertThat(notifications.findAll()).hasSize(1);
        assertThat(deliveries.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("配額為 null 代表不限")
    void nullQuotaMeansUnlimited() throws Exception {
        var admin = ownerClient(Set.of(Scope.NOTIFY_OWNER), null);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(signedPost(admin, ownerBody("x" + i), null))
                    .andExpect(status().isAccepted());
        }
        assertThat(notifications.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("冪等重播不再扣一次配額")
    void replayDoesNotChargeQuotaTwice() throws Exception {
        var admin = ownerClient(Set.of(Scope.NOTIFY_OWNER), 1);

        mockMvc.perform(signedPost(admin, ownerBody("x"), "key-1"))
                .andExpect(status().isAccepted());
        // 配額只剩 0，但這是重播，不該被擋
        mockMvc.perform(signedPost(admin, ownerBody("x"), "key-1"))
                .andExpect(status().isAccepted());
    }

    // -------------------------------------------------------------- 驗證

    @Test
    @DisplayName("兩種訊息模式都沒給：400")
    void missingMessage() throws Exception {
        mockMvc.perform(signedPost(serviceClient(),
                        """
                        {"target":{"type":"OWNER"}}""", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("target.type 缺漏：400，且錯誤訊息不回傳送進來的值")
    void missingTargetType() throws Exception {
        mockMvc.perform(signedPost(serviceClient(),
                        """
                        {"target":{},"message":{"text":"x"}}""", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("type=USER 但沒給 userIds：400")
    void userTargetWithoutIds() throws Exception {
        var admin = ownerClient(Set.of(Scope.NOTIFY_USER), null);

        mockMvc.perform(signedPost(admin,
                        """
                        {"target":{"type":"USER"},"message":{"text":"x"}}""", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("文字內容裡的連結不在白名單：400 URI_HOST_NOT_ALLOWED")
    void linkNotAllowed() throws Exception {
        mockMvc.perform(signedPost(serviceClient(),
                        ownerBody("詳見 https://evil.example.net/x"), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("URI_HOST_NOT_ALLOWED"));
    }

    // -------------------------------------------------------------- 查詢

    @Test
    @DisplayName("查得到自己的通知，含批次明細但不含收件人清單")
    void getOwnNotification() throws Exception {
        var client = serviceClient();
        String created = mockMvc.perform(signedPost(client, ownerBody("x"), null))
                .andReturn().getResponse().getContentAsString();
        String id = idOf(created);

        mockMvc.perform(signedGet(client, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notificationId").value(id))
                .andExpect(jsonPath("$.data.targetType").value("OWNER"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.batches[0].batchNo").value(0))
                .andExpect(jsonPath("$.data.batches[0].recipientCount").value(1))
                .andExpect(jsonPath("$.data.batches[0].lineUserIds").doesNotExist());
    }

    @Test
    @DisplayName("查別人的通知回 404，不是 403 —— 403 等於承認那個 id 存在")
    void cannotSeeOtherClientsNotification() throws Exception {
        var owner = serviceClient();
        var other = serviceClient();
        String id = idOf(mockMvc.perform(signedPost(owner, ownerBody("x"), null))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(signedGet(other, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("查不存在的 id 回 404，與查別人的回應完全相同")
    void unknownIdIsNotFound() throws Exception {
        mockMvc.perform(signedGet(serviceClient(), UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private static String idOf(String responseBody) {
        int start = responseBody.indexOf("\"notificationId\":\"") + 18;
        return responseBody.substring(start, responseBody.indexOf('"', start));
    }

}
