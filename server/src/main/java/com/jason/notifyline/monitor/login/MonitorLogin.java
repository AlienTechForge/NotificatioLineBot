package com.jason.notifyline.monitor.login;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 一組站台登入憑證 + token 快取。schema 見 {@code V7__monitor_login.sql}。
 *
 * <p>設定（帳密、注入方式）與狀態（token 快取、失敗計數）放同一個 entity，理由同
 * {@link com.jason.notifyline.monitor.domain.ApiMonitor}：取用時本來就要一起讀出來。
 *
 * <h2>AAD 陷阱</h2>
 *
 * <p>三個加密欄位的 AAD 都含 {@code id}，而 {@code id} 是 {@code BIGSERIAL} ——
 * 第一次 {@code save()} 之後才存在。呼叫端必須<strong>先 insert 拿到 id、再用該 id
 * 加密、最後回寫</strong>。用還是 {@code null} 的 id 加密會產生永遠解不開的密文。
 * 這與 {@code ApiMonitor.applyHeaders} 是同一個坑。
 */
@Entity
@Table(name = "monitor_login")
public class MonitorLogin {

    /** JWT 解析失敗時的退路壽命。寧可早一點重登，也不要拿著過期 token 去撞 401。 */
    private static final int FALLBACK_LIFETIME_SECONDS = 50 * 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private String config;

    @Column(name = "username", nullable = false, length = 320)
    private String username;

    /**
     * 可為 null 的理由見 {@code V7__monitor_login.sql} 的欄位註解：AAD 含 id，
     * 而 id 要到第一次 save() 之後才存在，NOT NULL 會讓那次 insert 直接失敗。
     * 與 {@code ApiMonitor.headersCiphertext} 是同一個取捨。
     */
    @Column(name = "password_ciphertext")
    private byte[] passwordCiphertext;

    @Column(name = "password_iv")
    private byte[] passwordIv;

    @Column(name = "password_key_version")
    private Integer passwordKeyVersion;

    @Column(name = "refresh_ciphertext")
    private byte[] refreshCiphertext;

    @Column(name = "refresh_iv")
    private byte[] refreshIv;

    @Column(name = "refresh_key_version")
    private Integer refreshKeyVersion;

    @Column(name = "token_ciphertext")
    private byte[] tokenCiphertext;

    @Column(name = "token_iv")
    private byte[] tokenIv;

    @Column(name = "token_key_version")
    private Integer tokenKeyVersion;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "header_name", nullable = false, length = 64)
    private String headerName;

    @Column(name = "header_value_template", nullable = false, length = 200)
    private String headerValueTemplate;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_error", length = 200)
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitorLogin() {
        // JPA
    }

    public MonitorLogin(String name,
                        LoginType type,
                        String config,
                        String username,
                        String headerName,
                        String headerValueTemplate,
                        Instant now) {
        this.name = requireText(name, "name");
        this.type = type == null ? LoginType.COGNITO_SRP.name() : type.name();
        this.enabled = true;
        this.config = requireText(config, "config");
        this.username = requireText(username, "username");
        this.headerName = validateHeaderName(headerName);
        this.headerValueTemplate = validateTemplate(headerValueTemplate);
        this.consecutiveFailures = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ------------------------------------------------------- 建構期共用驗證

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** 抄 {@code monitor_login_header_chk}：應用層先給好的錯誤訊息，資料庫仍是最後一道防線。 */
    private static String validateHeaderName(String headerName) {
        String value = headerName == null || headerName.isBlank() ? "Authorization" : headerName;
        if (!value.matches("^[A-Za-z0-9-]{1,64}$")) {
            throw new IllegalArgumentException("headerName must match ^[A-Za-z0-9-]{1,64}$: " + headerName);
        }
        return value;
    }

    /** 抄 {@code monitor_login_template_chk}：模板沒用到 token 的話這筆設定不會有任何效果。 */
    private static String validateTemplate(String template) {
        String value = template == null || template.isBlank() ? "{token}" : template;
        if (!value.contains("{token}")) {
            throw new IllegalArgumentException("headerValueTemplate must contain {token}: " + template);
        }
        if (value.length() > 200) {
            throw new IllegalArgumentException("headerValueTemplate too long: " + value.length());
        }
        return value;
    }

    // ---------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public LoginType getType() {
        return LoginType.valueOf(type);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getConfig() {
        return config;
    }

    public String getUsername() {
        return username;
    }

    public byte[] getPasswordCiphertext() {
        return copy(passwordCiphertext);
    }

    public byte[] getPasswordIv() {
        return copy(passwordIv);
    }

    public Integer getPasswordKeyVersion() {
        return passwordKeyVersion;
    }

    public byte[] getRefreshCiphertext() {
        return copy(refreshCiphertext);
    }

    public byte[] getRefreshIv() {
        return copy(refreshIv);
    }

    public Integer getRefreshKeyVersion() {
        return refreshKeyVersion;
    }

    public byte[] getTokenCiphertext() {
        return copy(tokenCiphertext);
    }

    public byte[] getTokenIv() {
        return copy(tokenIv);
    }

    public Integer getTokenKeyVersion() {
        return tokenKeyVersion;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderValueTemplate() {
        return headerValueTemplate;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ------------------------------------------------------------- 狀態轉換

    /** 見類別註解的 AAD 陷阱：{@code id} 必須已經存在。 */
    public void applyPassword(byte[] ciphertext, byte[] iv, Integer keyVersion, Instant now) {
        this.passwordCiphertext = copy(ciphertext);
        this.passwordIv = copy(iv);
        this.passwordKeyVersion = keyVersion;
        this.updatedAt = now;
    }

    /**
     * 寫回登入成功的結果。
     *
     * <p>{@code refreshCiphertext == null} 代表這次沒有拿到新的 refresh token
     * （{@code REFRESH_TOKEN_AUTH} 的回應就不含），此時<strong>保留</strong>舊的 ——
     * 覆寫成 null 會讓下一次被迫走完整 SRP 登入，白白多一次登入紀錄。
     */
    public void applyTokens(byte[] tokenCiphertext,
                            byte[] tokenIv,
                            Integer tokenKeyVersion,
                            Instant expiresAt,
                            byte[] refreshCiphertext,
                            byte[] refreshIv,
                            Integer refreshKeyVersion,
                            Instant now) {
        this.tokenCiphertext = copy(tokenCiphertext);
        this.tokenIv = copy(tokenIv);
        this.tokenKeyVersion = tokenKeyVersion;
        this.tokenExpiresAt = expiresAt;
        if (refreshCiphertext != null) {
            this.refreshCiphertext = copy(refreshCiphertext);
            this.refreshIv = copy(refreshIv);
            this.refreshKeyVersion = refreshKeyVersion;
        }
        this.lastLoginAt = now;
        this.lastError = null;
        this.consecutiveFailures = 0;
        this.updatedAt = now;
    }

    /**
     * 暫時性失敗（限流、網路、5xx）：累加計數但保持啟用。
     *
     * <p>刻意<strong>不</strong>清掉既有的 token 快取——對方暫時掛掉不代表我們手上的
     * token 失效了，清掉只會讓恢復後被迫重新登入。
     */
    public void recordTransientFailure(String error, Instant now) {
        this.consecutiveFailures++;
        this.lastError = truncateError(error);
        this.updatedAt = now;
    }

    /**
     * 永久性失敗（帳密錯、要求改密碼、MFA、設定錯）：<strong>停用</strong>。
     *
     * <p>見 {@code Docs/plan/15-監控站台登入設計.md} §5.1 —— Cognito 對連續失敗會鎖
     * 帳號，繼續退避重試只會把使用者自己的帳號鎖死。停用後由 {@code notifyOnFailure}
     * 通知使用者來處理。
     *
     * <p>同時清掉 token 與 refresh token：憑證已經不可信，留著只會在別處被誤用。
     */
    public void disableAfterPermanentFailure(String error, Instant now) {
        this.enabled = false;
        this.consecutiveFailures++;
        this.lastError = truncateError(error);
        this.tokenCiphertext = null;
        this.tokenIv = null;
        this.tokenKeyVersion = null;
        this.tokenExpiresAt = null;
        this.refreshCiphertext = null;
        this.refreshIv = null;
        this.refreshKeyVersion = null;
        this.updatedAt = now;
    }

    /** 後台編輯：整份取代設定欄位。密碼與 token 不動，由呼叫端另外處理。 */
    public void applyUpdate(String name,
                            String config,
                            String username,
                            String headerName,
                            String headerValueTemplate,
                            boolean enabled,
                            Instant now) {
        this.name = requireText(name, "name");
        this.config = requireText(config, "config");
        this.headerName = validateHeaderName(headerName);
        this.headerValueTemplate = validateTemplate(headerValueTemplate);

        // 換帳號等於換人：舊 token 屬於舊帳號，留著會讓監控繼續用前一個人的身分抓資料
        String newUsername = requireText(username, "username");
        if (!newUsername.equals(this.username)) {
            clearTokens();
        }
        this.username = newUsername;

        // 重新啟用時歸零失敗計數——使用者剛處理過問題，不該還帶著舊的失敗次數
        if (enabled && !this.enabled) {
            this.consecutiveFailures = 0;
            this.lastError = null;
        }
        this.enabled = enabled;
        this.updatedAt = now;
    }

    /** 密碼被改過：舊 token／refresh token 都可能屬於舊密碼，一律作廢。 */
    public void clearTokens() {
        this.tokenCiphertext = null;
        this.tokenIv = null;
        this.tokenKeyVersion = null;
        this.tokenExpiresAt = null;
        this.refreshCiphertext = null;
        this.refreshIv = null;
        this.refreshKeyVersion = null;
    }

    /**
     * 快取的 token 還能用嗎。
     *
     * @param skewSeconds 安全邊際：token 只剩幾秒時就當作已過期，避免「檢查時還有效、
     *                    送到對方手上已過期」
     */
    public boolean hasFreshToken(Instant now, int skewSeconds) {
        return tokenCiphertext != null
                && tokenExpiresAt != null
                && tokenExpiresAt.minusSeconds(skewSeconds).isAfter(now);
    }

    public boolean hasRefreshToken() {
        return refreshCiphertext != null;
    }

    /** 從 {@code ExpiresIn} 推算到期時間；缺值時用 50 分鐘的保守退路。 */
    public static Instant fallbackExpiry(Instant now, int expiresInSeconds) {
        int lifetime = expiresInSeconds > 0 ? expiresInSeconds : FALLBACK_LIFETIME_SECONDS;
        return now.plusSeconds(lifetime);
    }

    /** {@code last_error} 是 VARCHAR(200)，截斷比讓整筆寫入失敗好。 */
    private static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 200 ? error : error.substring(0, 197) + "...";
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    /** 不輸出任何密文、IV、帳號。 */
    @Override
    public String toString() {
        return "MonitorLogin[" + id + " name=" + name + " type=" + type + " enabled=" + enabled + "]";
    }
}
