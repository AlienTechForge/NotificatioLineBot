package com.jason.notifyline.admin;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.notification.domain.Notification;
import jakarta.validation.constraints.NotBlank;
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

    /** 建立憑證的種類。決定預設 scope 組合。 */
    public enum ClientKind {
        /** 後端服務，無綁定使用者，預設只能通知 owner。 */
        SERVICE,
        /** 管理者金鑰，擁有全部發送權限，需綁定 LINE user。 */
        OWNER
    }

    /**
     * 建立憑證的請求。
     *
     * @param lineUserId {@code kind = OWNER} 時必填
     * @param dailyQuota null = 不限
     */
    public record CreateClientRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters") String name,

            ClientKind kind,

            @Pattern(regexp = "^U[0-9a-f]{32}$", message = "must be a LINE user id")
            String lineUserId,

            Integer dailyQuota) {
    }

    /**
     * 建立成功的回應。<strong>{@code secret} 是明文，只在這一刻存在。</strong>
     * 前端顯示完就丟，不要存進任何地方。
     */
    public record CreatedClient(String clientId, String secret, List<String> scopes) {

        public static CreatedClient from(ClientService.IssuedClient issued) {
            return new CreatedClient(
                    issued.clientId(),
                    issued.secret(),
                    issued.scopes().stream().map(Scope::value).sorted().toList());
        }
    }

    /**
     * 從後台直接發一則通知（測試用）。
     *
     * @param clientId 以哪組憑證的身分發送。決定 scope 與可用的預設對象
     * @param type     null = 用該 client 的預設對象
     * @param userIds  只有 {@code type = USER} 時才需要
     */
    public record SendTestRequest(
            @NotBlank(message = "clientId is required") String clientId,

            TargetType type,

            @Size(max = 500, message = "at most 500 userIds")
            List<@Pattern(regexp = "^U[0-9a-f]{32}$",
                    message = "must be a LINE user id") String> userIds,

            @Size(max = 100, message = "title must be at most 100 characters") String title,

            @NotBlank(message = "text is required")
            @Size(max = 5000, message = "text must be at most 5000 characters") String text) {
    }

    /** 切換 owner 標記。 */
    public record SetOwnerRequest(boolean owner) {
    }

    /** 近期發送列表的一列。 */
    public record NotificationSummary(
            String notificationId,
            String clientName,
            String targetType,
            String status,
            int recipientCount,
            int successCount,
            int failureCount,
            Instant createdAt,
            Instant finishedAt) {

        public static NotificationSummary from(Notification n, String clientName) {
            return new NotificationSummary(
                    n.getId().toString(),
                    clientName,
                    n.getTargetType().name(),
                    n.getStatus().name(),
                    n.getRecipientCount(),
                    n.getSuccessCount(),
                    n.getFailureCount(),
                    n.getCreatedAt(),
                    n.getFinishedAt());
        }
    }

    /** 儀表板統計。 */
    public record Stats(
            long clientsTotal,
            long clientsActive,
            long usersActive,
            long owners,
            long notifications24h,
            long succeeded24h,
            long partial24h,
            long failed24h) {
    }
}
