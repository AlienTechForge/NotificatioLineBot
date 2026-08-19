package com.jason.notifyline.webhook;

import com.jason.notifyline.lineuser.LineUserService;
import com.jason.notifyline.lineuser.ProfileSyncService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.FollowEvent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.Source;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UnfollowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * LINE Webhook 事件入口。設計見 {@code Docs/plan/06-LINE整合設計.md} §3。
 *
 * <h2>三條硬規則</h2>
 * <ol>
 *   <li><strong>空事件也要回 200</strong> —— LINE 會送 {@code events: []} 做連線測試。
 *       SDK 已處理，這裡不會收到。</li>
 *   <li><strong>Reply token 一分鐘內用掉且只能用一次</strong> —— handler 內不做慢動作，
 *       Profile 同步一律丟背景執行緒。</li>
 *   <li><strong>handler 拋例外不可讓 HTTP 變 5xx</strong> —— 否則 LINE 會重送，
 *       而重送解決不了「程式有 bug」，只會讓同一個錯誤重複發生。</li>
 * </ol>
 *
 * <p>每個 handler 都先過 {@link WebhookEventGuard} 這道冪等閘門。
 */
@LineMessageHandler
public class LineWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookHandler.class);

    private static final String WELCOME = """
            歡迎加入！

            這個帳號會把系統通知送到你的 LINE。
            傳「說明」看可用指令。""";

    private final WebhookEventGuard guard;
    private final LineUserService lineUserService;
    private final ProfileSyncService profileSyncService;
    private final WebhookCommandRouter commandRouter;
    private final MessagingApiClient messagingApiClient;

    public LineWebhookHandler(WebhookEventGuard guard,
                              LineUserService lineUserService,
                              ProfileSyncService profileSyncService,
                              WebhookCommandRouter commandRouter,
                              MessagingApiClient messagingApiClient) {
        this.guard = guard;
        this.lineUserService = lineUserService;
        this.profileSyncService = profileSyncService;
        this.commandRouter = commandRouter;
        this.messagingApiClient = messagingApiClient;
    }

    // -------------------------------------------------------------- Follow

    @EventMapping
    public void handleFollow(FollowEvent event) {
        safely("FollowEvent", event, () -> {
            String userId = userIdOf(event.source());
            if (userId == null) {
                return;
            }
            if (!guard.shouldProcess(event.webhookEventId(), "FollowEvent", userId)) {
                return;
            }

            lineUserService.follow(userId);
            // 非同步 —— 同步呼叫 Profile API 會吃掉 reply token 的一分鐘時限
            profileSyncService.syncAsync(userId);
            reply(event.replyToken(), WELCOME);
        });
    }

    // ------------------------------------------------------------ Unfollow

    @EventMapping
    public void handleUnfollow(UnfollowEvent event) {
        safely("UnfollowEvent", event, () -> {
            String userId = userIdOf(event.source());
            if (userId == null) {
                return;
            }
            if (!guard.shouldProcess(event.webhookEventId(), "UnfollowEvent", userId)) {
                return;
            }
            // 沒有 reply token —— 使用者已經封鎖，回覆也送不出去
            lineUserService.unfollow(userId);
        });
    }

    // ------------------------------------------------------------- Message

    @EventMapping
    public void handleMessage(MessageEvent event) {
        safely("MessageEvent", event, () -> {
            String userId = userIdOf(event.source());
            if (userId == null) {
                return;
            }
            if (!guard.shouldProcess(event.webhookEventId(), "MessageEvent", userId)) {
                return;
            }

            if (!(event.message() instanceof TextMessageContent text)) {
                reply(event.replyToken(), "我只看得懂文字訊息。傳「說明」看可用指令。");
                return;
            }

            // 使用者可能在封鎖後又傳訊息（LINE 允許），確保狀態一致
            lineUserService.follow(userId);

            String response = commandRouter.route(userId, text.text());
            if (response != null) {
                reply(event.replyToken(), response);
            }
        });
    }

    // ------------------------------------------------------------- Default

    @EventMapping
    public void handleDefault(@Nullable Event event) {
        if (event == null) {
            log.warn("LINE webhook event was null");
            return;
        }
        log.info("未處理的 LINE webhook 事件：type={} id={}",
                event.getClass().getSimpleName(), event.webhookEventId());
    }

    // --------------------------------------------------------------- 內部

    /**
     * 規則三的實作：任何例外都不得冒出去變成 5xx。
     *
     * <p>與冪等閘門搭配的效果是「寧可漏處理，也不要重複處理」—— 事件已記錄，
     * LINE 重送不會重跑，失敗的部分靠 error log 追查與人工補。
     */
    private void safely(String eventType, Event event, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("處理 {} 失敗（已吞下以避免 LINE 重送）：eventId={}",
                    eventType, event == null ? null : event.webhookEventId(), e);
        }
    }

    private void reply(String replyToken, String text) {
        if (replyToken == null || replyToken.isBlank()) {
            return;
        }
        try {
            messagingApiClient.replyMessage(
                    new ReplyMessageRequest(replyToken, List.of(new TextMessage(text)), false)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("回覆訊息被中斷");
        } catch (Exception e) {
            // reply token 過期或已用過會回 400。不要改用 push 補送 ——
            // 那會消耗訊息額度，而使用者多半並不在意。
            log.warn("回覆訊息失敗：{}", e.getMessage());
        }
    }

    @Nullable
    private static String userIdOf(Source source) {
        if (source == null || source.userId() == null || source.userId().isBlank()) {
            // 群組或多人聊天室的事件可能沒有 userId。本專案只處理一對一。
            log.debug("事件來源沒有 userId，略過");
            return null;
        }
        return source.userId();
    }
}
