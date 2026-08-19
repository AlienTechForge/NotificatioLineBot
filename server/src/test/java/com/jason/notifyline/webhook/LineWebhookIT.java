package com.jason.notifyline.webhook;

import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.ClientStatus;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.lineuser.LineUserStatus;
import com.jason.notifyline.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LINE Webhook 端到端。見 {@code Docs/plan/08-測試計畫.md} §3.6。
 *
 * <p>自己用 channel secret 簽 webhook 請求，完整走過 SDK 的驗簽路徑 ——
 * 不打真實 LINE API。
 */
@DisplayName("LINE Webhook（整合）")
@AutoConfigureMockMvc
class LineWebhookIT extends PostgresIntegrationTest {

    private static final String WEBHOOK_PATH = "/line/webhook";
    private static final String USER_ID = "U0000000000000000000000000000000a";
    private static final String DESTINATION = "U00000000000000000000000000000099";

    @Value("${line.bot.channel-secret}")
    private String channelSecret;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LineUserRepository lineUserRepository;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private ClientService clientService;
    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @BeforeEach
    void setUp() {
        webhookEventRepository.deleteAll();
        clientRepository.deleteAll();
        lineUserRepository.deleteAll();
    }

    // ------------------------------------------------------------ 簽章工具

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(channelSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void send(String body) throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }

    private static String followEvent(String eventId, String userId) {
        return """
                {"destination":"%s","events":[{
                  "type":"follow",
                  "mode":"active",
                  "timestamp":1755500000000,
                  "webhookEventId":"%s",
                  "deliveryContext":{"isRedelivery":false},
                  "replyToken":"reply-token-%s",
                  "source":{"type":"user","userId":"%s"}
                }]}""".formatted(DESTINATION, eventId, eventId, userId);
    }

    private static String unfollowEvent(String eventId, String userId) {
        return """
                {"destination":"%s","events":[{
                  "type":"unfollow",
                  "mode":"active",
                  "timestamp":1755500000000,
                  "webhookEventId":"%s",
                  "deliveryContext":{"isRedelivery":false},
                  "source":{"type":"user","userId":"%s"}
                }]}""".formatted(DESTINATION, eventId, userId);
    }

    private static String textMessageEvent(String eventId, String userId, String text) {
        return """
                {"destination":"%s","events":[{
                  "type":"message",
                  "mode":"active",
                  "timestamp":1755500000000,
                  "webhookEventId":"%s",
                  "deliveryContext":{"isRedelivery":false},
                  "replyToken":"reply-token-%s",
                  "source":{"type":"user","userId":"%s"},
                  "message":{"type":"text","id":"msg-1","text":"%s"}
                }]}""".formatted(DESTINATION, eventId, eventId, userId, text);
    }

    // ---------------------------------------------------------------- 驗簽

    @Test
    @DisplayName("正確簽章的 follow 事件：使用者建立且為 ACTIVE")
    void followEvent_createsActiveUser() throws Exception {
        send(followEvent("evt-follow-1", USER_ID));

        assertThat(lineUserRepository.findById(USER_ID)).hasValueSatisfying(user -> {
            assertThat(user.getStatus()).isEqualTo(LineUserStatus.ACTIVE);
            assertThat(user.getFollowedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("錯誤簽章：拒絕且資料庫無變化")
    void wrongSignature_rejectedAndNoSideEffect() throws Exception {
        String body = followEvent("evt-bad-sig", USER_ID);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", "bogus-signature")
                        .content(body))
                .andExpect(status().is4xxClientError());

        assertThat(lineUserRepository.count()).isZero();
        assertThat(webhookEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("缺簽章 header：拒絕")
    void missingSignature_rejected() throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followEvent("evt-no-sig", USER_ID)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("空事件請求（LINE 連線測試）必須回 200")
    void emptyEvents_returns200() throws Exception {
        send("{\"destination\":\"" + DESTINATION + "\",\"events\":[]}");
    }

    @Test
    @DisplayName("header 名稱大小寫不敏感")
    void signatureHeaderIsCaseInsensitive() throws Exception {
        String body = followEvent("evt-case", USER_ID);

        mockMvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Line-Signature", sign(body))
                        .content(body))
                .andExpect(status().isOk());

        assertThat(lineUserRepository.findById(USER_ID)).isPresent();
    }

    // ------------------------------------------------------------ 冪等閘門

    @Test
    @DisplayName("相同 webhookEventId 送兩次：只處理一次")
    void redelivery_processedOnce() throws Exception {
        String body = followEvent("evt-dup", USER_ID);

        send(body);
        send(body);

        assertThat(webhookEventRepository.count()).isEqualTo(1);
        assertThat(webhookEventRepository.findById("evt-dup")).isPresent();
    }

    @Test
    @DisplayName("不同 webhookEventId 各自處理")
    void differentEventIds_bothProcessed() throws Exception {
        send(followEvent("evt-a", USER_ID));
        send(unfollowEvent("evt-b", USER_ID));

        assertThat(webhookEventRepository.count()).isEqualTo(2);
    }

    // -------------------------------------------------------- unfollow 連鎖

    @Test
    @DisplayName("unfollow：使用者標 BLOCKED，且其 ACTIVE 金鑰連帶停用")
    void unfollow_blocksUserAndDisablesClient() throws Exception {
        send(followEvent("evt-f1", USER_ID));
        ClientService.IssuedClient issued = clientService.create(
                ClientService.CreateClientCommand.forUser("jason", USER_ID, null));

        send(unfollowEvent("evt-u1", USER_ID));

        assertThat(lineUserRepository.findById(USER_ID).orElseThrow().getStatus())
                .isEqualTo(LineUserStatus.BLOCKED);
        assertThat(clientRepository.findByClientId(issued.clientId()).orElseThrow().getStatus())
                .isEqualTo(ClientStatus.DISABLED);
    }

    @Test
    @DisplayName("封鎖後重新加好友：回到 ACTIVE，但金鑰「不會」自動恢復")
    void refollow_reactivatesUserButNotClient() throws Exception {
        send(followEvent("evt-f2", USER_ID));
        ClientService.IssuedClient issued = clientService.create(
                ClientService.CreateClientCommand.forUser("jason", USER_ID, null));
        send(unfollowEvent("evt-u2", USER_ID));

        send(followEvent("evt-f3", USER_ID));

        assertThat(lineUserRepository.findById(USER_ID).orElseThrow().getStatus())
                .isEqualTo(LineUserStatus.ACTIVE);
        // 刻意不自動恢復 —— 封鎖期間金鑰可能已外流
        assertThat(clientRepository.findByClientId(issued.clientId()).orElseThrow().getStatus())
                .isEqualTo(ClientStatus.DISABLED);
    }

    // ---------------------------------------------------------------- 指令

    @Test
    @DisplayName("文字訊息：使用者會被 upsert 成 ACTIVE")
    void textMessage_upsertsUser() throws Exception {
        send(textMessageEvent("evt-msg-1", USER_ID, "說明"));

        assertThat(lineUserRepository.findById(USER_ID)).hasValueSatisfying(user ->
                assertThat(user.getStatus()).isEqualTo(LineUserStatus.ACTIVE));
    }

    @Test
    @DisplayName("處理過程若失敗，HTTP 仍回 200（避免 LINE 重送同一個壞掉的事件）")
    void handlerFailure_stillReturns200() throws Exception {
        // 來源沒有 userId（群組事件）—— handler 應安靜略過而非 5xx
        String body = """
                {"destination":"%s","events":[{
                  "type":"message",
                  "mode":"active",
                  "timestamp":1755500000000,
                  "webhookEventId":"evt-group",
                  "deliveryContext":{"isRedelivery":false},
                  "replyToken":"reply-token-group",
                  "source":{"type":"group","groupId":"Cxxxxxxxx"},
                  "message":{"type":"text","id":"msg-2","text":"hi"}
                }]}""".formatted(DESTINATION);

        send(body);

        assertThat(lineUserRepository.count()).isZero();
    }
}
