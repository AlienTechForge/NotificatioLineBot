package com.jason.notifyline.lineuser;

import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.UserProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * LINE Profile 同步。
 *
 * <p>兩個用途：
 * <ol>
 *   <li><strong>補齊</strong> —— follow 事件當下非同步取 profile。不能在 webhook
 *       handler 內同步做，那會吃掉 reply token 的一分鐘時限。</li>
 *   <li><strong>校正</strong> —— 每日排程逐一檢查。{@code UnfollowEvent} 可能因為
 *       我們這邊當機而漏收；沒有校正機制的話名單會慢慢累積無效使用者，
 *       每次 ALL 發送都在浪費額度。</li>
 * </ol>
 *
 * <p>Profile API 回 404 是可靠的「這人已經不在了」訊號 —— 取得條件是「已加好友」或
 * 「未加好友但曾傳訊息且未封鎖」。其他錯誤一律不改變狀態，避免暫時性故障誤刪名單。
 */
@Service
public class ProfileSyncService {

    private static final Logger log = LoggerFactory.getLogger(ProfileSyncService.class);

    private static final int HTTP_NOT_FOUND = 404;

    private final MessagingApiClient messagingApiClient;
    private final LineUserService lineUserService;
    private final LineUserRepository lineUserRepository;

    public ProfileSyncService(MessagingApiClient messagingApiClient,
                              LineUserService lineUserService,
                              LineUserRepository lineUserRepository) {
        this.messagingApiClient = messagingApiClient;
        this.lineUserService = lineUserService;
        this.lineUserRepository = lineUserRepository;
    }

    /**
     * 非同步補 profile。
     *
     * <p>失敗不影響 follow 事件本身 —— 使用者已經是好友了，只是名字暫時空著，
     * 每日校正會補上。
     */
    @Async("notifyTaskExecutor")
    public void syncAsync(String lineUserId) {
        try {
            fetchAndApply(lineUserId);
        } catch (Exception e) {
            log.warn("Profile 同步失敗（不影響 follow 本身）：lineUserId={} error={}",
                    lineUserId, e.getMessage());
        }
    }

    /**
     * 每日校正。
     *
     * <p>多實例注意：這個排程在多實例下會重複執行（缺口 G12）。重複呼叫 LINE API
     * 會浪費速率配額。加第二個實例前需導入 ShedLock。
     */
    @Scheduled(cron = "${app.profile-sync.cron:0 0 3 * * *}")
    public void reconcileDaily() {
        List<LineUser> active = lineUserRepository.findByStatus(LineUserStatus.ACTIVE);
        log.info("開始每日 Profile 校正：{} 位使用者", active.size());

        int updated = 0;
        int gone = 0;
        for (LineUser user : active) {
            try {
                if (fetchAndApply(user.getLineUserId())) {
                    updated++;
                }
            } catch (NotFoundException e) {
                lineUserService.markGone(user.getLineUserId());
                gone++;
            } catch (Exception e) {
                // 暫時性錯誤不改變狀態 —— 誤把有效使用者標成 BLOCKED 比漏更新更糟
                log.warn("Profile 校正失敗，維持原狀：lineUserId={} error={}",
                        user.getLineUserId(), e.getMessage());
            }
        }
        log.info("每日 Profile 校正完成：更新 {} 筆，標記失效 {} 筆", updated, gone);
    }

    private boolean fetchAndApply(String lineUserId) throws Exception {
        try {
            Result<UserProfileResponse> result = messagingApiClient.getProfile(lineUserId).get();
            UserProfileResponse profile = result.body();
            if (profile == null) {
                return false;
            }
            URI picture = profile.pictureUrl();
            lineUserService.applyProfile(
                    lineUserId,
                    profile.displayName(),
                    picture == null ? null : picture.toString(),
                    profile.statusMessage(),
                    profile.language());
            return true;
        } catch (ExecutionException e) {
            if (isNotFound(e)) {
                throw new NotFoundException(lineUserId);
            }
            throw e;
        }
    }

    /**
     * SDK 把 HTTP 錯誤包在 {@code ExecutionException} 裡，且例外型別在
     * messaging client 模組內。這裡以狀態碼判斷而非型別比對，避免綁死在
     * SDK 的內部類別上。
     */
    private static boolean isNotFound(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            return false;
        }
        String message = String.valueOf(cause.getMessage());
        return message.contains(String.valueOf(HTTP_NOT_FOUND));
    }

    /** 使用者已刪除帳號或已封鎖 —— Profile API 回 404。 */
    static class NotFoundException extends Exception {
        NotFoundException(String lineUserId) {
            super("LINE profile not found: " + lineUserId, null, false, false);
        }
    }
}
