package com.jason.notifyline.admin;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 管理端點的資料形狀。
 *
 * <p><strong>刻意不含 secret_ciphertext、secret_iv 或任何金鑰材料。</strong>
 * 管理介面沒有任何需要它們的理由，而一個「順便帶出來」的欄位會在某次前端
 * 除錯時被印進瀏覽器 console，然後留在那裡。
 */
public final class AdminDto {

    private AdminDto() {
    }

    /**
     * @param defaultTargetType    null = 沒設定預設對象，該 client 每次都要自己帶 target
     * @param scopes               API 形式的字串（{@code notify:owner}），不是 enum 名稱
     */
    public record ClientSummary(
            String clientId,
            String name,
            String status,
            String boundLineUserId,
            List<String> scopes,
            String defaultTargetType,
            List<String> defaultTargetUserIds,
            Integer rateLimitPerMin,
            Integer dailyMessageQuota,
            Instant createdAt,
            Instant lastUsedAt) {

        public static ClientSummary from(Client client) {
            return new ClientSummary(
                    client.getClientId(),
                    client.getName(),
                    client.getStatus().name(),
                    client.getBoundLineUserId(),
                    client.getScopes().stream().map(Scope::value).sorted().toList(),
                    client.getDefaultTargetType() == null ? null : client.getDefaultTargetType().name(),
                    client.getDefaultTargetUserIds(),
                    client.getRateLimitPerMin(),
                    client.getDailyMessageQuota(),
                    client.getCreatedAt(),
                    client.getLastUsedAt());
        }
    }

    /**
     * 可以被選為收件人的 LINE 使用者。
     *
     * <p>只有 ACTIVE 的才會出現 —— 讓管理者從清單挑選，避免手打
     * 33 個字元的 user id 打錯一個字元卻毫無徵兆。
     */
    public record LineUserSummary(
            String lineUserId,
            String displayName,
            String pictureUrl,
            boolean owner,
            String status) {

        public static LineUserSummary from(LineUser user) {
            return new LineUserSummary(
                    user.getLineUserId(),
                    user.getDisplayName(),
                    user.getPictureUrl(),
                    user.isOwner(),
                    user.getStatus().name());
        }
    }

    /**
     * 設定預設通知對象。
     *
     * @param type    null = 清除設定
     * @param userIds 只有 {@code type = USER} 時才可以有值
     */
    public record SetDefaultTargetRequest(
            TargetType type,

            @Size(max = 500, message = "at most 500 userIds")
            List<@Pattern(regexp = "^U[0-9a-f]{32}$",
                    message = "must be a LINE user id") String> userIds) {
    }
}
