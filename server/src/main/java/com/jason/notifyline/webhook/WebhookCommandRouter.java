package com.jason.notifyline.webhook;

import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.lineuser.LineUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 文字指令路由。見 {@code Docs/plan/06-LINE整合設計.md} §3.3。
 *
 * <p>回傳要回覆的文字；回傳 null 代表不回覆。
 *
 * <p><strong>不在這裡做慢動作</strong> —— reply token 只能用一次且需在收到 webhook 後
 * 一分鐘內使用。任何可能超過數百毫秒的操作都要丟背景執行緒。
 */
@Component
public class WebhookCommandRouter {

    private static final Logger log = LoggerFactory.getLogger(WebhookCommandRouter.class);

    private static final Set<String> ISSUE_COMMANDS = Set.of("申請金鑰", "申請", "issue");
    private static final Set<String> RESET_COMMANDS = Set.of("重設金鑰", "重置金鑰", "reset");
    private static final Set<String> WHOAMI_COMMANDS = Set.of("我的id", "我的ID", "whoami", "id");
    private static final Set<String> HELP_COMMANDS = Set.of("說明", "help", "?", "？");

    private static final String HELP_TEXT = """
            可用指令：

            申請金鑰 — 取得可呼叫通知 API 的憑證
            重設金鑰 — 撤銷舊憑證並產生新的
            我的ID   — 查看自己的 LINE User ID
            說明     — 顯示這則訊息""";

    private final LineUserService lineUserService;
    private final ClientService clientService;

    public WebhookCommandRouter(LineUserService lineUserService, ClientService clientService) {
        this.lineUserService = lineUserService;
        this.clientService = clientService;
    }

    /**
     * @return 要回覆的文字，null 代表不回覆
     */
    public String route(String lineUserId, String rawText) {
        String command = normalise(rawText);
        log.debug("收到指令：lineUserId={} command={}", lineUserId, command);

        if (ISSUE_COMMANDS.contains(command)) {
            return handleIssue(lineUserId);
        }
        if (RESET_COMMANDS.contains(command)) {
            return handleReset(lineUserId);
        }
        if (WHOAMI_COMMANDS.contains(command)) {
            return "你的 LINE User ID：\n" + lineUserId;
        }
        if (HELP_COMMANDS.contains(command)) {
            return HELP_TEXT;
        }
        return "我看不懂這則訊息。傳「說明」看可用指令。";
    }

    private String handleIssue(String lineUserId) {
        if (clientService.findActiveByLineUser(lineUserId).isPresent()) {
            return "你已經有金鑰了。要重新產生請傳「重設金鑰」（舊金鑰會立即失效）。";
        }
        // T6 會在這裡發放一次性連結
        return "金鑰申請流程即將開放（T6）。";
    }

    private String handleReset(String lineUserId) {
        if (!lineUserService.isActive(lineUserId)) {
            return "找不到你的好友紀錄，請先重新加入好友。";
        }
        // T6 會在這裡撤銷舊金鑰並發放一次性連結
        return "金鑰重設流程即將開放（T6）。";
    }

    /** 比對前移除前後空白與全形空白，並統一大小寫。 */
    private static String normalise(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.replace('　', ' ').trim().toLowerCase(Locale.ROOT);
    }
}
