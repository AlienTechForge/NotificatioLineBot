package com.jason.notifyline.webhook;

import com.jason.notifyline.lineuser.LineUserService;
import com.jason.notifyline.lineuser.ProfileSyncService;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.UnfollowEvent;
import com.linecorp.bot.webhook.model.UserSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Handler 的<strong>規則三</strong>：任何例外都不得冒出去變成 5xx。
 *
 * <p>違反的話 LINE 會重送，而重送解決不了「程式有 bug」，只會讓同一個錯誤
 * 重複發生。見 {@code Docs/plan/06-LINE整合設計.md} §3.4。
 *
 * <p>正常路徑的行為由 {@link LineWebhookIT} 走完整 webhook 驗簽驗證。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LineWebhookHandler")
class LineWebhookHandlerTest {

    private static final String USER_ID = "U0000000000000000000000000000000a";

    @Mock
    private WebhookEventGuard guard;
    @Mock
    private LineUserService lineUserService;
    @Mock
    private ProfileSyncService profileSyncService;
    @Mock
    private WebhookCommandRouter commandRouter;
    @Mock
    private MessagingApiClient messagingApiClient;

    @InjectMocks
    private LineWebhookHandler handler;

    private static UnfollowEvent unfollow(String userId) {
        return new UnfollowEvent(new UserSource(userId), 0L, null, "wh-evt-1", null);
    }

    @Test
    @DisplayName("下游服務拋例外時不冒出去 —— 否則 LINE 會重送同一個壞掉的事件")
    void handlerSwallowsDownstreamFailure() {
        when(guard.shouldProcess(anyString(), anyString(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("資料庫掛了")).when(lineUserService).unfollow(anyString());

        assertThatCode(() -> handler.handleUnfollow(unfollow(USER_ID))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("冪等閘門判定為重送時，完全不碰下游")
    void redelivery_skipsBusinessLogic() {
        when(guard.shouldProcess(anyString(), anyString(), anyString())).thenReturn(false);

        handler.handleUnfollow(unfollow(USER_ID));

        verifyNoInteractions(lineUserService, profileSyncService, commandRouter);
    }

    @Test
    @DisplayName("來源沒有 userId（群組事件）時安靜略過，不進冪等閘門")
    void eventWithoutUserId_isIgnored() {
        handler.handleUnfollow(new UnfollowEvent(new UserSource(null), 0L, null, "wh-evt-2", null));

        verifyNoInteractions(guard, lineUserService);
    }

    @Test
    @DisplayName("未知事件型別只記 log，不拋例外")
    void handleDefault_unknownEvent_doesNotThrow() {
        Event event = unfollow(USER_ID);

        assertThatCode(() -> handler.handleDefault(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("event 為 null 也不拋例外")
    void handleDefault_nullEvent_doesNotThrow() {
        assertThatCode(() -> handler.handleDefault(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("replyToken 為 null 時不嘗試回覆")
    void nullReplyToken_skipsReply() {
        when(guard.shouldProcess(anyString(), anyString(), anyString())).thenReturn(true);

        assertThatCode(() -> handler.handleUnfollow(unfollow(USER_ID))).doesNotThrowAnyException();
        verifyNoInteractions(messagingApiClient);
    }
}
