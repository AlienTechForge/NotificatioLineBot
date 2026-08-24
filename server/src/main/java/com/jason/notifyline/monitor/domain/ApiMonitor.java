package com.jason.notifyline.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 監控項目：設定 + 執行狀態。schema 見 {@code V4__api_monitor.sql}。
 *
 * <p>設定與執行狀態刻意放同一個 entity（不拆成 config / state） —— 取件
 * （{@code ApiMonitorRepository.lockDue}）本來就要把兩者一起讀出來，拆開只會
 * 多一次 join，換不到任何好處。
 *
 * <p>W1 只提供資料結構：建構子做與 migration CHECK 約束對應的防禦性驗證（給更好的
 * 錯誤訊息，資料庫仍是最後一道防線）。<strong>W3 在這裡加上狀態轉換方法</strong>
 * （{@link #lease}、{@link #recordSuccess}、{@link #recordFailure} 等）——
 * 這些方法只操作記憶體中的欄位，不知道交易邊界，呼叫端（{@code ApiMonitorStore}）
 * 才是決定何時 commit 的地方，見 {@code Docs/plan/11-API監控輪詢設計.md} §7、§8。
 */
@Entity
@Table(name = "api_monitor")
public class ApiMonitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    // ------------------------------------------------------------------ 目標

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "method", nullable = false, length = 8)
    private String method;

    @Column(name = "request_body")
    private String requestBody;

    /** AES-256-GCM，AAD = {@code monitor:{id}}，與 {@code Client.secretCiphertext} 同一套金鑰管理。 */
    @Column(name = "headers_ciphertext")
    private byte[] headersCiphertext;

    @Column(name = "headers_iv")
    private byte[] headersIv;

    @Column(name = "headers_key_version")
    private Integer headersKeyVersion;

    // ------------------------------------------------------------------ 排程

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    // -------------------------------------------------------------- 解析與比對

    @Enumerated(EnumType.STRING)
    @Column(name = "compare_mode", nullable = false, length = 16)
    private CompareMode compareMode;

    /**
     * JSONB 原文，格式見 migration 註解。刻意維持 {@code String} 而非
     * {@code List<ExtractRule>} —— 照 {@code Notification.payload} 的既有慣例
     * （{@code @JdbcTypeCode(SqlTypes.JSON)} + {@code String}），序列化留給
     * 實際使用它的波次。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extract_rules", nullable = false)
    private String extractRules;

    @Column(name = "item_pointer")
    private String itemPointer;

    @Column(name = "item_key_pointer")
    private String itemKeyPointer;

    @Column(name = "message_template", nullable = false)
    private String messageTemplate;

    /**
     * JSONB 原文，格式見 {@code V6__monitor_computed.sql} 的欄位註解。理由同
     * {@link #extractRules}：維持 {@code String}，反序列化留給實際使用它的
     * {@code ComputedFieldEvaluator} / {@code ComputedFieldValidator} 負責。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "computed_fields", nullable = false)
    private String computedFields;

    // -------------------------------------------------------------- 防洗版

    @Column(name = "notify_on_failure", nullable = false)
    private boolean notifyOnFailure;

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds;

    /** null = 不限。 */
    @Column(name = "max_notifications_per_day")
    private Integer maxNotificationsPerDay;

    // -------------------------------------------------------------- 執行狀態

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    /** WHOLE_BODY / EXTRACTED 模式的比對基準。NEW_ITEMS 模式不使用。 */
    @Column(name = "last_fingerprint")
    private byte[] lastFingerprint;

    /** JSONB 原文，格式見 migration 註解。理由同 {@link #extractRules}。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_state")
    private String lastState;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "failure_notified", nullable = false)
    private boolean failureNotified;

    @Column(name = "notified_count", nullable = false)
    private int notifiedCount;

    @Column(name = "notified_day")
    private LocalDate notifiedDay;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApiMonitor() {
        // JPA
    }

    public ApiMonitor(String name,
                      Long clientId,
                      String url,
                      String method,
                      String requestBody,
                      byte[] headersCiphertext,
                      byte[] headersIv,
                      Integer headersKeyVersion,
                      int intervalSeconds,
                      boolean enabled,
                      CompareMode compareMode,
                      String extractRules,
                      String itemPointer,
                      String itemKeyPointer,
                      String messageTemplate,
                      boolean notifyOnFailure,
                      int cooldownSeconds,
                      Integer maxNotificationsPerDay,
                      Instant now) {
        this(name, clientId, url, method, requestBody, headersCiphertext, headersIv, headersKeyVersion,
                intervalSeconds, enabled, compareMode, extractRules, itemPointer, itemKeyPointer, messageTemplate,
                notifyOnFailure, cooldownSeconds, maxNotificationsPerDay, null, now);
    }

    /**
     * 帶 {@code computed_fields}（W13）的完整建構子。上面那個少一個參數的版本是給
     * 不關心計算欄位的既有呼叫端／測試用的簡便寫法，兩者共用同一份驗證與欄位指派邏輯。
     */
    public ApiMonitor(String name,
                      Long clientId,
                      String url,
                      String method,
                      String requestBody,
                      byte[] headersCiphertext,
                      byte[] headersIv,
                      Integer headersKeyVersion,
                      int intervalSeconds,
                      boolean enabled,
                      CompareMode compareMode,
                      String extractRules,
                      String itemPointer,
                      String itemKeyPointer,
                      String messageTemplate,
                      boolean notifyOnFailure,
                      int cooldownSeconds,
                      Integer maxNotificationsPerDay,
                      String computedFields,
                      Instant now) {
        String normalizedMethod = validateAndNormalizeMethod(method);
        validateInterval(intervalSeconds);
        validateNewItemsPointers(compareMode, itemPointer, itemKeyPointer);

        this.name = name;
        this.clientId = clientId;
        this.url = url;
        this.method = normalizedMethod;
        this.requestBody = requestBody;
        this.headersCiphertext = headersCiphertext == null ? null : headersCiphertext.clone();
        this.headersIv = headersIv == null ? null : headersIv.clone();
        this.headersKeyVersion = headersKeyVersion;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
        this.compareMode = compareMode;
        this.extractRules = extractRules == null ? "[]" : extractRules;
        this.itemPointer = itemPointer;
        this.itemKeyPointer = itemKeyPointer;
        this.messageTemplate = messageTemplate;
        this.notifyOnFailure = notifyOnFailure;
        this.cooldownSeconds = cooldownSeconds;
        this.maxNotificationsPerDay = maxNotificationsPerDay;
        this.computedFields = computedFields == null ? "[]" : computedFields;
        // 新建立的監控立刻可被取件抓第一次基準，不必等一個 interval —— 使用者建立
        // 監控是想馬上看到目前的值，不是想等待。
        this.nextRunAt = now;
        this.consecutiveFailures = 0;
        this.failureNotified = false;
        this.notifiedCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ------------------------------------------------------- 建構期共用驗證

    /**
     * 抄 {@code api_monitor_method_chk}：應用層先給出好的錯誤訊息，資料庫仍是最後一道防線。
     * 建構子與 {@link #applyUpdate}（W4，後台編輯）共用同一份規則，避免兩處各自維護
     * 一份、日後改一邊漏改另一邊。
     */
    private static String validateAndNormalizeMethod(String method) {
        String normalized = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        if (!"GET".equals(normalized) && !"POST".equals(normalized)) {
            throw new IllegalArgumentException("method must be GET or POST: " + method);
        }
        return normalized;
    }

    /** 抄 {@code api_monitor_interval_chk}。理由同 {@link #validateAndNormalizeMethod}。 */
    private static void validateInterval(int intervalSeconds) {
        if (intervalSeconds < 30) {
            throw new IllegalArgumentException("intervalSeconds must be >= 30: " + intervalSeconds);
        }
    }

    /**
     * 抄 {@code api_monitor_newitems_chk}：NEW_ITEMS 一定要有陣列位置與鍵位置，
     * 否則無從判斷「新」。讓「型別與位置不一致」這種狀態在應用層就無法建立。
     */
    private static void validateNewItemsPointers(CompareMode compareMode, String itemPointer, String itemKeyPointer) {
        if (compareMode == CompareMode.NEW_ITEMS && (itemPointer == null || itemKeyPointer == null)) {
            throw new IllegalArgumentException(
                    "NEW_ITEMS mode requires both itemPointer and itemKeyPointer");
        }
    }

    // ---------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getClientId() {
        return clientId;
    }

    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public byte[] getHeadersCiphertext() {
        return headersCiphertext == null ? null : headersCiphertext.clone();
    }

    public byte[] getHeadersIv() {
        return headersIv == null ? null : headersIv.clone();
    }

    public Integer getHeadersKeyVersion() {
        return headersKeyVersion;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CompareMode getCompareMode() {
        return compareMode;
    }

    public String getExtractRules() {
        return extractRules;
    }

    public String getItemPointer() {
        return itemPointer;
    }

    public String getItemKeyPointer() {
        return itemKeyPointer;
    }

    public String getMessageTemplate() {
        return messageTemplate;
    }

    public String getComputedFields() {
        return computedFields;
    }

    public boolean isNotifyOnFailure() {
        return notifyOnFailure;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public Integer getMaxNotificationsPerDay() {
        return maxNotificationsPerDay;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public Instant getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public byte[] getLastFingerprint() {
        return lastFingerprint == null ? null : lastFingerprint.clone();
    }

    public String getLastState() {
        return lastState;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean isFailureNotified() {
        return failureNotified;
    }

    public int getNotifiedCount() {
        return notifiedCount;
    }

    public LocalDate getNotifiedDay() {
        return notifiedDay;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ------------------------------------------------------------ 狀態轉換（W3）

    /**
     * 取件（claim）時把 {@code next_run_at} 推到租約時間，避免下一輪取件撿到同一筆
     * 還在處理中的監控。見 {@code ApiMonitorStore#claim}。
     */
    public void lease(Instant leaseUntil) {
        this.nextRunAt = leaseUntil;
    }

    /**
     * 成功執行一輪（不論有沒有偵測到變更）：重置失敗計數與失敗通知旗標、排下一次
     * 執行時間。
     *
     * @param effectiveIntervalSeconds 呼叫端算好的「有效間隔」——
     *                                 {@code max(intervalSeconds, app.monitor.min-interval)}。
     *                                 服務層的最小間隔是防洗版設定，就算監控本身的
     *                                 {@code interval_seconds} 曾經以較小的下限建立過，
     *                                 排程仍要用現在生效的下限，不能只看建立當時的值
     */
    public void recordSuccess(Instant now, int effectiveIntervalSeconds) {
        this.lastRunAt = now;
        this.nextRunAt = now.plusSeconds(effectiveIntervalSeconds);
        this.consecutiveFailures = 0;
        this.failureNotified = false;
        this.updatedAt = now;
    }

    /**
     * 抓取或解析失敗：累加連續失敗次數，退避排程。
     *
     * <p>{@code next_run_at = now + effectiveIntervalSeconds × min(2^(failures-1), 16)}，
     * 見 §8。乘數用 {@link #backoffMultiplier} 計算，指數本身先夾在 0..4 之間再取冪，
     * 避免 {@code consecutiveFailures} 在長時間故障下持續增長時整數運算溢位。
     *
     * @param effectiveIntervalSeconds 理由同 {@link #recordSuccess}
     */
    public void recordFailure(Instant now, int effectiveIntervalSeconds) {
        this.lastRunAt = now;
        this.consecutiveFailures++;
        int multiplier = backoffMultiplier(consecutiveFailures);
        this.nextRunAt = now.plusSeconds((long) effectiveIntervalSeconds * multiplier);
        this.updatedAt = now;
    }

    /** {@code min(2^(failures-1), 16)}。指數夾在 0..4（2^4=16）避免溢位，語意與直接夾值相同。 */
    private static int backoffMultiplier(int consecutiveFailures) {
        int exponent = Math.min(consecutiveFailures - 1, 4);
        return 1 << exponent;
    }

    /**
     * 連續失敗達到門檻、且這次故障期間還沒發過失敗通知。
     *
     * <p>{@code failure_notified} 是「這次故障」的旗標，不是「這次執行」的 ——
     * 見 {@link #markFailureNotified} 與 {@link #recordSuccess}（成功時歸零）。
     */
    public boolean shouldNotifyFailure(int threshold) {
        return consecutiveFailures >= threshold && !failureNotified;
    }

    /** 已經為這次故障發過失敗通知，避免連續失敗期間每輪都通知。 */
    public void markFailureNotified() {
        this.failureNotified = true;
    }

    /** {@code WHOLE_BODY} / {@code EXTRACTED} 模式：寫回本次的比對基準。 */
    public void applyFingerprint(byte[] fingerprint, String stateJson) {
        this.lastFingerprint = fingerprint == null ? null : fingerprint.clone();
        this.lastState = stateJson;
    }

    // ------------------------------------------------------------ 狀態轉換（W4）

    /**
     * 後台編輯（{@code PUT /admin/api/monitors/{id}}）：整份取代設定欄位，執行狀態
     * （{@code next_run_at}、fingerprint、失敗計數等）完全不動——編輯設定不該讓下一次
     * 排程時間、比對基準跟著重置，那會讓使用者以為監控「重新開始」而困惑，也會在
     * 只是想改個 cooldown 秒數時意外把 fingerprint 清掉、白白多發一次通知。
     *
     * <p>Header 不在這裡處理——加密需要先知道 id（見
     * {@code Docs/plan/11-API監控輪詢設計.md} §10 的 AAD 陷阱說明），呼叫端要另外呼叫
     * {@link #applyHeaders}。
     *
     * @throws IllegalArgumentException 驗證規則同建構子（method、interval、NEW_ITEMS 欄位）
     */
    public void applyUpdate(String name,
                            Long clientId,
                            String url,
                            String method,
                            String requestBody,
                            int intervalSeconds,
                            boolean enabled,
                            CompareMode compareMode,
                            String extractRules,
                            String itemPointer,
                            String itemKeyPointer,
                            String messageTemplate,
                            boolean notifyOnFailure,
                            int cooldownSeconds,
                            Integer maxNotificationsPerDay,
                            Instant now) {
        // 簡便版：不動既有的 computed_fields，理由同建構子的兩版分工——不關心
        // 計算欄位的既有呼叫端／測試不必跟著改。
        applyUpdate(name, clientId, url, method, requestBody, intervalSeconds, enabled, compareMode, extractRules,
                itemPointer, itemKeyPointer, messageTemplate, notifyOnFailure, cooldownSeconds,
                maxNotificationsPerDay, this.computedFields, now);
    }

    /** 帶 {@code computed_fields} 的完整版（W13）。 */
    public void applyUpdate(String name,
                            Long clientId,
                            String url,
                            String method,
                            String requestBody,
                            int intervalSeconds,
                            boolean enabled,
                            CompareMode compareMode,
                            String extractRules,
                            String itemPointer,
                            String itemKeyPointer,
                            String messageTemplate,
                            boolean notifyOnFailure,
                            int cooldownSeconds,
                            Integer maxNotificationsPerDay,
                            String computedFields,
                            Instant now) {
        String normalizedMethod = validateAndNormalizeMethod(method);
        validateInterval(intervalSeconds);
        validateNewItemsPointers(compareMode, itemPointer, itemKeyPointer);

        this.name = name;
        this.clientId = clientId;
        this.url = url;
        this.method = normalizedMethod;
        this.requestBody = requestBody;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
        this.compareMode = compareMode;
        this.extractRules = extractRules == null ? "[]" : extractRules;
        this.itemPointer = itemPointer;
        this.itemKeyPointer = itemKeyPointer;
        this.messageTemplate = messageTemplate;
        this.notifyOnFailure = notifyOnFailure;
        this.cooldownSeconds = cooldownSeconds;
        this.maxNotificationsPerDay = maxNotificationsPerDay;
        this.computedFields = computedFields == null ? "[]" : computedFields;
        this.updatedAt = now;
    }

    /**
     * 覆寫（或保留）加密後的 header。
     *
     * <p>呼叫端（{@code AdminService}）要自己判斷「編輯時 header 欄位留空 = 不變更」
     * ——這個方法本身沒有「不變更」的語意，只要被呼叫就會整份覆寫，包含用
     * {@code ciphertext = null} 清除既有 header。
     *
     * <p><strong>AAD 陷阱</strong>：header 密文的 AAD 是 {@code monitor:{id}}，建立新監控時
     * id 要到第一次 {@code save()}（{@code BIGSERIAL}）後才存在。呼叫端必須先插入拿到 id、
     * 用該 id 加密，再呼叫這個方法回寫密文——用還是 {@code null} 的 id 加密會產生永遠解不開
     * 的密文。這個方法本身不驗證 AAD 是否用對了 id，那是呼叫端的責任。
     */
    public void applyHeaders(byte[] ciphertext, byte[] iv, Integer keyVersion, Instant now) {
        this.headersCiphertext = ciphertext == null ? null : ciphertext.clone();
        this.headersIv = iv == null ? null : iv.clone();
        this.headersKeyVersion = keyVersion;
        this.updatedAt = now;
    }

    /** 啟用／停用。{@code next_run_at} 不動——理由同 {@link #applyUpdate}。 */
    public void setEnabled(boolean enabled, Instant now) {
        this.enabled = enabled;
        this.updatedAt = now;
    }

    /** 冷卻中：上次通知時間 + 冷卻秒數仍在未來。 */
    public boolean isCooldownActive(Instant now) {
        return lastNotifiedAt != null && lastNotifiedAt.plusSeconds(cooldownSeconds).isAfter(now);
    }

    /**
     * {@code notified_day} 與傳入的日期不同（含尚未通知過的 {@code null}）就換日、
     * 歸零計數。呼叫端要先呼叫這個方法，{@link #hasReachedDailyCap} 才會反映今天的
     * 計數，而不是上一個有通知的日子留下的舊值。
     */
    public void rolloverNotifiedDayIfNeeded(LocalDate today) {
        if (!today.equals(notifiedDay)) {
            this.notifiedDay = today;
            this.notifiedCount = 0;
        }
    }

    /** {@code max_notifications_per_day} 為 {@code null} 代表不限。 */
    public boolean hasReachedDailyCap() {
        return maxNotificationsPerDay != null && notifiedCount >= maxNotificationsPerDay;
    }

    /**
     * 記一次成功送出的「變更」通知。只在真的呼叫了
     * {@code NotificationService.submit()} 且沒有拋例外之後才呼叫這個方法——
     * 失敗/恢復通知不算在這個計數裡，見 {@code ApiMonitorStore} 的說明。
     */
    public void markNotified(Instant now) {
        this.lastNotifiedAt = now;
        this.notifiedCount++;
        this.updatedAt = now;
    }

    /** 不輸出 header 密文/IV、目標 URL 的查詢字串可能含機密，這裡也一併略過。 */
    @Override
    public String toString() {
        return "ApiMonitor[" + id + " name=" + name + " enabled=" + enabled
                + " mode=" + compareMode + "]";
    }
}
