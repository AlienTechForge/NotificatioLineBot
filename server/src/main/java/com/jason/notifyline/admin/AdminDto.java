package com.jason.notifyline.admin;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.monitor.MonitorTestOutcome;
import com.jason.notifyline.monitor.domain.ApiMonitor;
import com.jason.notifyline.monitor.domain.ApiMonitorRun;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.notification.domain.Notification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
     * 從後台直接發一則通知，或排到未來某個時間點發送。
     *
     * @param clientId    以哪組憑證的身分發送。決定 scope 與可用的預設對象
     * @param type        null = 用該 client 的預設對象
     * @param userIds     只有 {@code type = USER} 時才需要
     * @param scheduledAt null = 立即發送；非 null = 排到該時間點才由派送器取件，
     *                    必須晚於現在（見 {@code AdminService} 的驗證與可接受的上限）
     */
    public record SendTestRequest(
            @NotBlank(message = "clientId is required") String clientId,

            TargetType type,

            @Size(max = 500, message = "at most 500 userIds")
            List<@Pattern(regexp = "^U[0-9a-f]{32}$",
                    message = "must be a LINE user id") String> userIds,

            @Size(max = 100, message = "title must be at most 100 characters") String title,

            @NotBlank(message = "text is required")
            @Size(max = 5000, message = "text must be at most 5000 characters") String text,

            Instant scheduledAt) {
    }

    /** 切換 owner 標記。 */
    public record SetOwnerRequest(boolean owner) {
    }

    /**
     * 近期發送列表的一列。
     *
     * @param scheduledAt null = 這是一則立即發送的通知；非 null = 排程，
     *                    值是預計派送器取件的時間
     */
    public record NotificationSummary(
            String notificationId,
            String clientName,
            String targetType,
            String status,
            int recipientCount,
            int successCount,
            int failureCount,
            Instant createdAt,
            Instant finishedAt,
            Instant scheduledAt) {

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
                    n.getFinishedAt(),
                    n.getScheduledAt());
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

    /**
     * LINE 官方帳號的月訊息配額用量。
     *
     * @param available   false 代表拿不到（呼叫失敗、逾時、或方案本身不限量）——
     *                    這種情況下其餘欄位一律是 null，前端要能處理「沒有這個資訊」
     * @param unlimited   true 代表這個方案沒有月上限（LINE 的 {@code type=none}）
     * @param limit       月上限則數。{@code unlimited=true} 或 {@code available=false} 時為 null
     * @param used        本月已用則數
     * @param remaining   {@code limit - used}，可能為負（LINE 端的用量與我方查詢有些微延遲）
     */
    public record LineQuota(
            boolean available,
            boolean unlimited,
            Long limit,
            Long used,
            Long remaining) {

        public static LineQuota unavailable() {
            return new LineQuota(false, false, null, null, null);
        }

        public static LineQuota unlimitedPlan(long used) {
            return new LineQuota(true, true, null, used, null);
        }

        public static LineQuota of(long limit, long used) {
            return new LineQuota(true, false, limit, used, limit - used);
        }
    }

    /** 排到未來時間的通知。取消需要 {@code notificationId}。 */
    public record ScheduledNotification(
            String notificationId,
            String clientName,
            String targetType,
            int recipientCount,
            Instant scheduledAt) {

        public static ScheduledNotification from(Notification n, String clientName) {
            return new ScheduledNotification(
                    n.getId().toString(),
                    clientName,
                    n.getTargetType().name(),
                    n.getRecipientCount(),
                    n.getScheduledAt());
        }
    }

    // ============================================================ 監控（W4）

    /**
     * 監控清單一列，同時也是後台編輯抽屜的預填資料。<strong>刻意不含</strong>
     * {@code headers_ciphertext}、{@code headers_iv} 或解密後的 header 值——理由同類別
     * 註解：{@code hasHeaders} 只說「有沒有設定」，不透露內容。
     */
    public record MonitorSummary(
            Long id,
            String name,
            String clientId,
            String clientName,
            String url,
            String host,
            String method,
            String requestBody,
            boolean hasHeaders,
            int intervalSeconds,
            boolean enabled,
            CompareMode compareMode,
            List<ExtractRule> extractRules,
            String itemPointer,
            String itemKeyPointer,
            String messageTemplate,
            boolean notifyOnFailure,
            int cooldownSeconds,
            Integer maxNotificationsPerDay,
            int consecutiveFailures,
            boolean failureNotified,
            Instant lastRunAt,
            Instant nextRunAt,
            Instant createdAt) {

        public static MonitorSummary from(ApiMonitor m, String clientId, String clientName,
                                          List<ExtractRule> extractRules) {
            return new MonitorSummary(
                    m.getId(), m.getName(), clientId, clientName, m.getUrl(), hostOf(m.getUrl()), m.getMethod(),
                    m.getRequestBody(), m.getHeadersCiphertext() != null, m.getIntervalSeconds(), m.isEnabled(),
                    m.getCompareMode(), extractRules, m.getItemPointer(), m.getItemKeyPointer(),
                    m.getMessageTemplate(), m.isNotifyOnFailure(), m.getCooldownSeconds(),
                    m.getMaxNotificationsPerDay(), m.getConsecutiveFailures(), m.isFailureNotified(),
                    m.getLastRunAt(), m.getNextRunAt(), m.getCreatedAt());
        }

        /** 列表要顯示的是目標 host，不是完整網址（可能帶查詢字串）。解析不出來就顯示 null，前端自行代換。 */
        private static String hostOf(String url) {
            try {
                return URI.create(url).getHost();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * 建立監控。
     *
     * @param headers null 或空 map = 這個監控沒有自訂 header（不同於編輯時「留空 = 不變更」——
     *                建立當下根本沒有「既有值」可以不變更）
     * @param enabled null 預設 true
     */
    public record CreateMonitorRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters") String name,

            @NotBlank(message = "clientId is required") String clientId,

            @NotBlank(message = "url is required") String url,

            String method,

            String requestBody,

            Map<String, String> headers,

            int intervalSeconds,

            Boolean enabled,

            @NotNull(message = "compareMode is required") CompareMode compareMode,

            List<ExtractRule> extractRules,

            String itemPointer,

            String itemKeyPointer,

            @NotBlank(message = "messageTemplate is required") String messageTemplate,

            Boolean notifyOnFailure,

            Integer cooldownSeconds,

            Integer maxNotificationsPerDay) {
    }

    /**
     * 更新監控（整份取代語意，PUT）。
     *
     * @param headers null 或空 map = <strong>不變更</strong>既有 header；非空 = 整份覆寫。
     *                沒有「明確清除」的表示法——真的要清掉自訂 header，目前只能作廢重建
     */
    public record UpdateMonitorRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters") String name,

            @NotBlank(message = "clientId is required") String clientId,

            @NotBlank(message = "url is required") String url,

            String method,

            String requestBody,

            Map<String, String> headers,

            int intervalSeconds,

            Boolean enabled,

            @NotNull(message = "compareMode is required") CompareMode compareMode,

            List<ExtractRule> extractRules,

            String itemPointer,

            String itemKeyPointer,

            @NotBlank(message = "messageTemplate is required") String messageTemplate,

            Boolean notifyOnFailure,

            Integer cooldownSeconds,

            Integer maxNotificationsPerDay) {
    }

    /** 啟用／停用。 */
    public record SetEnabledRequest(boolean enabled) {
    }

    /**
     * 試跑：body 帶完整設定，<strong>未存檔也可</strong>。刻意沒有 {@code clientId}、
     * {@code intervalSeconds} 等排程/發送相關欄位——試跑不建立排程、不綁定 client、
     * 更不會發送，這些欄位對它沒有意義。
     */
    public record MonitorTestRequest(
            String name,

            @NotBlank(message = "url is required") String url,

            String method,

            String requestBody,

            Map<String, String> headers,

            @NotNull(message = "compareMode is required") CompareMode compareMode,

            List<ExtractRule> extractRules,

            String itemPointer,

            String itemKeyPointer,

            @NotBlank(message = "messageTemplate is required") String messageTemplate) {
    }

    /**
     * 試跑結果。<strong>絕不含目標 API 的原始回應內容</strong>——只有抽出的值、
     * {@code NEW_ITEMS} 模式的項目預覽，與渲染後的訊息文字。見 {@link MonitorTestOutcome}。
     *
     * @param ok            true = 抓取與解析都成功（不代表「有變更」——試跑沒有比對基準）
     * @param failureReason ok=false 時的分類：{@code BLOCKED_URL}、{@code PARSE_ERROR}，
     *                       或 {@code FetchResult.Reason} 的名稱（{@code TIMEOUT} 等）
     */
    public record MonitorTestResult(
            boolean ok,
            Integer httpStatus,
            String failureReason,
            String failureDetail,
            Map<String, String> values,
            List<ItemPreview> items,
            String renderedMessage) {

        public record ItemPreview(String itemKey, Map<String, String> fields) {
            public static ItemPreview from(MonitorTestOutcome.ItemPreview p) {
                return new ItemPreview(p.itemKey(), p.fields());
            }
        }

        public static MonitorTestResult from(MonitorTestOutcome outcome) {
            return switch (outcome) {
                case MonitorTestOutcome.Blocked b -> new MonitorTestResult(
                        false, null, "BLOCKED_URL", b.message(), Map.of(), List.of(), null);
                case MonitorTestOutcome.FetchFailed f -> new MonitorTestResult(
                        false, f.httpStatus(), f.reason(), f.detail(), Map.of(), List.of(), null);
                case MonitorTestOutcome.ParseFailed p -> new MonitorTestResult(
                        false, null, "PARSE_ERROR", p.detail(), Map.of(), List.of(), null);
                case MonitorTestOutcome.Success s -> new MonitorTestResult(
                        true, s.httpStatus(), null, null, s.values(),
                        s.items().stream().map(ItemPreview::from).toList(), s.renderedMessage());
            };
        }
    }

    /** 單筆監控最近 50 筆執行紀錄的一列。 */
    public record MonitorRunSummary(
            Long id,
            Instant startedAt,
            Integer durationMs,
            String outcome,
            Integer httpStatus,
            String errorMessage,
            String notificationId) {

        public static MonitorRunSummary from(ApiMonitorRun r) {
            return new MonitorRunSummary(
                    r.getId(), r.getStartedAt(), r.getDurationMs(), r.getOutcome().name(),
                    r.getHttpStatus(), r.getErrorMessage(),
                    r.getNotificationId() == null ? null : r.getNotificationId().toString());
        }
    }

    // ============================================================ 監控：匯入（W5）

    /**
     * 匯入端點的輸入：使用者貼上的原始內容（cURL / {@code fetch(...)} / 自訂 JSON）。
     * 見 {@code Docs/plan/12-API監控易用性升級.md} §2.6。
     *
     * <p>64 KB 的大小上限刻意<strong>不</strong>用 {@code @Size} 在這裡表示——
     * {@code @Size} 算的是字元數，貼上內容常含中文 header 值，字元數會低估實際
     * 位元組數。實際的上限檢查在 {@code AdminService#importMonitorRequest}，用
     * UTF-8 位元組長度。
     */
    public record ImportMonitorRequest(@NotBlank(message = "raw is required") String raw) {
    }
}
