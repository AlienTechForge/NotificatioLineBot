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
import com.jason.notifyline.monitor.domain.ComputedField;
import com.jason.notifyline.monitor.domain.ExtractRule;
import com.jason.notifyline.monitor.session.SiteSession;
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
     * {@code headers_ciphertext}、{@code headers_iv} 或解密後的 header <strong>值</strong>
     * ——那些只透過 {@code GET /admin/api/monitors/{id}/headers} 明確請求才會回傳
     * （見該端點的說明），列表／摘要不是「順便夾帶機敏內容」的地方。
     *
     * <p>{@code headerNames}（排序過）則<strong>刻意含</strong>——header
     * <em>名稱</em>（{@code accept}、{@code authorization} 這類）不是機密，原本「連名稱
     * 都不回」的規則把儲存是否成功這件事變得無法從畫面確認：使用者從匯入帶進 header、
     * 按儲存、抽屜重開時欄位一片空白（因為 {@code hasHeaders} 只有布林值），跟「根本沒存進去」
     * 長得一模一樣。見 {@code fix/monitor-header-visibility} 這次修正的討論——把名稱顯示
     * 出來，使用者才能立刻確認「剛剛存的到底是哪幾個」。{@code hasHeaders} 保留給只需要
     * 「有沒有設定」這個布林判斷的既有呼叫端。
     *
     * <p>{@code secretNames} 不同：那些是<strong>使用者刻意標記為 secret</strong> 的欄位
     * （簽章用的 appsecret、device id 之類），這裡一樣只回名稱、<strong>絕不含值</strong>，
     * 而且沒有對應的「顯示目前值」端點——secret 這個機制存在的意義就是把明文回到瀏覽器
     * 的機會降到最低，見 {@code MonitorSecretService} 類別註解。{@code computedFields}
     * 本身不是機敏資料（只是公式：輸入模板 + 演算法 + 編碼），可以完整回傳。
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
            List<String> headerNames,
            List<String> secretNames,
            int intervalSeconds,
            boolean enabled,
            CompareMode compareMode,
            List<ExtractRule> extractRules,
            String itemPointer,
            String itemKeyPointer,
            List<ComputedField> computedFields,
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
                                          List<ExtractRule> extractRules, List<String> headerNames,
                                          List<String> secretNames, List<ComputedField> computedFields) {
            return new MonitorSummary(
                    m.getId(), m.getName(), clientId, clientName, m.getUrl(), hostOf(m.getUrl()), m.getMethod(),
                    m.getRequestBody(), m.getHeadersCiphertext() != null, headerNames, secretNames,
                    m.getIntervalSeconds(), m.isEnabled(), m.getCompareMode(), extractRules, m.getItemPointer(),
                    m.getItemKeyPointer(), computedFields, m.getMessageTemplate(), m.isNotifyOnFailure(),
                    m.getCooldownSeconds(), m.getMaxNotificationsPerDay(), m.getConsecutiveFailures(),
                    m.isFailureNotified(), m.getLastRunAt(), m.getNextRunAt(), m.getCreatedAt());
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
     * @param headers        null 或空 map = 這個監控沒有自訂 header（不同於編輯時「留空 =
     *                       不變更」——建立當下根本沒有「既有值」可以不變更）
     * @param enabled        null 預設 true
     * @param secrets        新增的 secret（{@code name -> 明文值}）。建立當下沒有「既有值」，
     *                       所以這裡<strong>不</strong>套用編輯時「留空 = 不變更」那套慣例——
     *                       每一筆非空白值都會被寫入，空白值直接忽略（不會建立一個空字串的
     *                       secret）。見 {@code Docs/plan/13-監控計算欄位設計.md} §7
     * @param computedFields 依序求值的計算欄位。存檔時驗證：名稱格式、
     *                       {@code steps} 非空、HMAC 需要 {@code keySecret}、佔位符只能引用
     *                       {@code secret.*}（這次請求裡的非空白項目）／{@code now.*}／
     *                       {@code uuid}／陣列中<strong>更早</strong>的 {@code computed.*}——
     *                       前向引用、自我引用、未知佔位符一律 400
     */
    public record CreateMonitorRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters") String name,

            @NotBlank(message = "clientId is required") String clientId,

            @NotBlank(message = "url is required") String url,

            String method,

            String requestBody,

            Map<String, String> headers,

            Map<String, String> secrets,

            int intervalSeconds,

            Boolean enabled,

            @NotNull(message = "compareMode is required") CompareMode compareMode,

            List<ExtractRule> extractRules,

            String itemPointer,

            String itemKeyPointer,

            List<ComputedField> computedFields,

            @NotBlank(message = "messageTemplate is required") String messageTemplate,

            Boolean notifyOnFailure,

            Integer cooldownSeconds,

            Integer maxNotificationsPerDay) {

        /** 舊有呼叫端（不涉及 secret／計算欄位的既有測試）的簡便建構子。 */
        public CreateMonitorRequest(String name, String clientId, String url, String method, String requestBody,
                                    Map<String, String> headers, int intervalSeconds, Boolean enabled,
                                    CompareMode compareMode, List<ExtractRule> extractRules, String itemPointer,
                                    String itemKeyPointer, String messageTemplate, Boolean notifyOnFailure,
                                    Integer cooldownSeconds, Integer maxNotificationsPerDay) {
            this(name, clientId, url, method, requestBody, headers, Map.of(), intervalSeconds, enabled, compareMode,
                    extractRules, itemPointer, itemKeyPointer, List.of(), messageTemplate, notifyOnFailure,
                    cooldownSeconds, maxNotificationsPerDay);
        }
    }

    /**
     * 更新監控（整份取代語意，PUT）。
     *
     * @param headers        null 或空 map = <strong>不變更</strong>既有 header；非空 = 整份覆寫。
     *                       沒有「明確清除」的表示法——真的要清掉自訂 header，目前只能作廢重建
     * @param secrets        新增／覆寫的 secret（{@code name -> 明文值}）。<strong>每一筆的
     *                       空白值代表「這個名稱不變更」</strong>，同 header 的既有慣例——
     *                       跟 header 不同的是這裡是<strong>逐筆</strong>判斷，不是整個
     *                       map 一起判斷（既有的其他 secret 名稱本來就不受這次請求影響）。
     *                       刪除單一 secret 走另一個端點
     *                       （{@code DELETE /monitors/{id}/secrets/{name}}），不透過這裡
     * @param computedFields 同 {@link CreateMonitorRequest#computedFields}；{@code null}
     *                       視為空清單（沒有計算欄位）
     */
    public record UpdateMonitorRequest(
            @NotBlank(message = "name is required")
            @Size(max = 100, message = "name must be at most 100 characters") String name,

            @NotBlank(message = "clientId is required") String clientId,

            @NotBlank(message = "url is required") String url,

            String method,

            String requestBody,

            Map<String, String> headers,

            Map<String, String> secrets,

            int intervalSeconds,

            Boolean enabled,

            @NotNull(message = "compareMode is required") CompareMode compareMode,

            List<ExtractRule> extractRules,

            String itemPointer,

            String itemKeyPointer,

            List<ComputedField> computedFields,

            @NotBlank(message = "messageTemplate is required") String messageTemplate,

            Boolean notifyOnFailure,

            Integer cooldownSeconds,

            Integer maxNotificationsPerDay) {

        /** 舊有呼叫端（不涉及 secret／計算欄位的既有測試）的簡便建構子。 */
        public UpdateMonitorRequest(String name, String clientId, String url, String method, String requestBody,
                                    Map<String, String> headers, int intervalSeconds, Boolean enabled,
                                    CompareMode compareMode, List<ExtractRule> extractRules, String itemPointer,
                                    String itemKeyPointer, String messageTemplate, Boolean notifyOnFailure,
                                    Integer cooldownSeconds, Integer maxNotificationsPerDay) {
            this(name, clientId, url, method, requestBody, headers, Map.of(), intervalSeconds, enabled, compareMode,
                    extractRules, itemPointer, itemKeyPointer, List.of(), messageTemplate, notifyOnFailure,
                    cooldownSeconds, maxNotificationsPerDay);
        }
    }

    /** 啟用／停用。 */
    public record SetEnabledRequest(boolean enabled) {
    }

    /**
     * 試跑：body 帶完整設定，<strong>未存檔也可</strong>。刻意沒有 {@code clientId}、
     * {@code intervalSeconds} 等排程/發送相關欄位——試跑不建立排程、不綁定 client、
     * 更不會發送，這些欄位對它沒有意義。
     *
     * @param monitorId 選填：已存在的監控 id。非 null 時，{@code computedFields} 裡
     *                  引用的 {@code {{secret.NAME}}} 若在 {@code secrets} 沒有給非空白的
     *                  覆寫值，會改用這個監控<strong>已存好</strong>的 secret（解密後的
     *                  明文只活在這次請求的處理過程中，不會被回傳）——不這樣做的話，每次
     *                  試算都得把 secret 重新貼一次，體驗上不可行。{@code null} = 完全未
     *                  存檔的草稿，這時只能用 {@code secrets} 裡直接給的值
     * @param secrets   這次試算要用的 secret 明文覆寫（{@code name -> value}）。空白值
     *                  代表「這個名稱不覆寫」，交給 {@code monitorId} 對應的既有值（若有）
     * @param computedFields 依序求值的計算欄位，規則同
     *                  {@link CreateMonitorRequest#computedFields}
     */
    public record MonitorTestRequest(
            String name,

            @NotBlank(message = "url is required") String url,

            String method,

            String requestBody,

            Map<String, String> headers,

            Map<String, String> secrets,

            @NotNull(message = "compareMode is required") CompareMode compareMode,

            List<ExtractRule> extractRules,

            String itemPointer,

            String itemKeyPointer,

            List<ComputedField> computedFields,

            @NotBlank(message = "messageTemplate is required") String messageTemplate,

            Long monitorId) {

        /** 舊有呼叫端（不涉及 secret／計算欄位的既有測試）的簡便建構子。 */
        public MonitorTestRequest(String name, String url, String method, String requestBody,
                                  Map<String, String> headers, CompareMode compareMode,
                                  List<ExtractRule> extractRules, String itemPointer, String itemKeyPointer,
                                  String messageTemplate) {
            this(name, url, method, requestBody, headers, Map.of(), compareMode, extractRules, itemPointer,
                    itemKeyPointer, List.of(), messageTemplate, null);
        }
    }

    /**
     * 試跑結果。抓取成功時（{@code ok=true}）帶著抽出的值、{@code NEW_ITEMS} 模式的項目
     * 預覽、渲染後的訊息文字，<strong>以及（可能被截斷的）目標 API 原始回應 body</strong>——
     * 見 {@link MonitorTestOutcome} 類別註解「這是刻意放寬的例外」：{@code
     * Docs/plan/11-API監控輪詢設計.md} §10 對持久化執行紀錄的「絕不回顯回應內容」規則，
     * 不適用於這個完全不落地、帶 {@code Cache-Control: no-store} 的試跑端點。後台拿
     * {@code body} 畫成可展開的樹，點節點插入 JsonPointer（見
     * {@code Docs/plan/12-API監控易用性升級.md} §4.2）。
     *
     * @param ok                 true = 抓取與解析都成功（不代表「有變更」——試跑沒有比對基準）
     * @param failureReason      ok=false 時的分類：{@code BLOCKED_URL}、{@code PARSE_ERROR}，
     *                           或 {@code FetchResult.Reason} 的名稱（{@code TIMEOUT} 等）
     * @param body               ok=true 時：抓到的原始回應內容，超過 256 KB 時被截斷；
     *                           ok=false 時恆為 {@code null}
     * @param bodyTruncated      {@code body} 是否已被截斷；{@code values} /
     *                           {@code renderedMessage} 不受截斷影響，一律來自完整回應
     * @param bodyOriginalLength 截斷前的原始長度（UTF-8 位元組數）
     * @param computedValues   計算欄位求出的最終值（{@code name -> 值}，多半是雜湊），
     *                         見 {@link MonitorTestOutcome.Success#computedValues()}。
     *                         {@code ok=false} 時恆為空 map——<strong>絕不含 secret 值</strong>
     */
    public record MonitorTestResult(
            boolean ok,
            Integer httpStatus,
            String failureReason,
            String failureDetail,
            Map<String, String> values,
            List<ItemPreview> items,
            String renderedMessage,
            String body,
            boolean bodyTruncated,
            int bodyOriginalLength,
            Map<String, String> computedValues) {

        public record ItemPreview(String itemKey, Map<String, String> fields) {
            public static ItemPreview from(MonitorTestOutcome.ItemPreview p) {
                return new ItemPreview(p.itemKey(), p.fields());
            }
        }

        public static MonitorTestResult from(MonitorTestOutcome outcome) {
            return switch (outcome) {
                case MonitorTestOutcome.Blocked b -> new MonitorTestResult(
                        false, null, "BLOCKED_URL", b.message(), Map.of(), List.of(), null, null, false, 0, Map.of());
                case MonitorTestOutcome.FetchFailed f -> new MonitorTestResult(
                        false, f.httpStatus(), f.reason(), f.detail(), Map.of(), List.of(), null, null, false, 0,
                        Map.of());
                case MonitorTestOutcome.ParseFailed p -> new MonitorTestResult(
                        false, null, "PARSE_ERROR", p.detail(), Map.of(), List.of(), null, null, false, 0, Map.of());
                case MonitorTestOutcome.Success s -> new MonitorTestResult(
                        true, s.httpStatus(), null, null, s.values(),
                        s.items().stream().map(ItemPreview::from).toList(), s.renderedMessage(),
                        s.body(), s.bodyTruncated(), s.bodyOriginalLength(), s.computedValues());
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

    // ============================================================ 站台登入狀態（W6）

    /**
     * 站台登入狀態（cookie jar）列表的一列。見
     * {@code Docs/plan/12-API監控易用性升級.md} §3.4。
     *
     * <p><strong>刻意不含</strong> {@code jar_ciphertext}、{@code jar_iv} 或解密後的
     * cookie 值——這個端點的整個重點就是「絕不回傳值」，只回 host、cookie
     * 名稱、數量與時間戳，理由同類別註解。
     */
    public record SiteSessionSummary(
            String host,
            List<String> cookieNames,
            int cookieCount,
            Instant lastRefreshedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static SiteSessionSummary from(SiteSession session) {
            List<String> names = session.getCookieNames() == null || session.getCookieNames().isBlank()
                    ? List.of()
                    : List.of(session.getCookieNames().split(","));
            return new SiteSessionSummary(
                    session.getHost(), names, names.size(),
                    session.getLastRefreshedAt(), session.getCreatedAt(), session.getUpdatedAt());
        }
    }
}
