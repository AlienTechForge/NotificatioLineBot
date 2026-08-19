package com.jason.notifyline.client;

import com.jason.notifyline.common.TargetType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.EnumSet;
import java.util.Set;

/**
 * 呼叫端憑證。schema 見 {@code Docs/plan/04-資料模型.md} §3。
 *
 * <p><strong>secret 是加密不是雜湊</strong> —— HMAC 驗簽需要伺服器重算簽章，
 * 必須拿得回明文。見 {@code Docs/plan/adr/0002-client-secret-加密儲存而非雜湊.md}。
 *
 * <p>注意欄位命名的坑：{@code client.client_id} 是對外的公開識別碼（{@code cli_…}），
 * 而 {@code client_scope.client_id} 是指向 {@code client.id} 的 BIGINT 外鍵。
 * 兩者同名但不同東西，寫原生 SQL 時要留意。
 */
@Entity
@Table(name = "client")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 對外公開的識別碼，形如 {@code cli_a1b2c3…}。 */
    @Column(name = "client_id", nullable = false, unique = true, length = 64)
    private String clientId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "secret_ciphertext", nullable = false)
    private byte[] secretCiphertext;

    @Column(name = "secret_iv", nullable = false)
    private byte[] secretIv;

    @Column(name = "secret_key_version", nullable = false)
    private int secretKeyVersion;

    /** null 代表 SERVICE client（無綁定使用者）。 */
    @Column(name = "bound_line_user_id", length = 64)
    private String boundLineUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ClientStatus status;

    /** null = 用系統預設。 */
    @Column(name = "rate_limit_per_min")
    private Integer rateLimitPerMin;

    /** null = 不限。 */
    @Column(name = "daily_message_quota")
    private Integer dailyMessageQuota;

    /**
     * 請求未帶 {@code target} 時使用的通知對象。null = 沒設定。
     *
     * <p>這是<strong>管理者</strong>指定的，所以套用時不做 scope 檢查 ——
     * 授權行為本身就是管理者做的。呼叫端自己在請求裡指定的 target 才需要 scope。
     *
     * <p>這條界線讓「只被信任發給預設對象」的 service 可以被指向任意收件人組合，
     * 卻拿不到 {@code notify:user}（那等於能發給任何人）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_target_type", length = 16)
    private TargetType defaultTargetType;

    /** 僅 {@code defaultTargetType = USER} 時有值。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "default_target_user_ids")
    private String[] defaultTargetUserIds;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "client_scope", joinColumns = @JoinColumn(name = "client_id"))
    @Column(name = "scope", nullable = false, length = 32)
    private Set<Scope> scopes = EnumSet.noneOf(Scope.class);

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected Client() {
        // JPA
    }

    public Client(String clientId,
                  String name,
                  byte[] secretCiphertext,
                  byte[] secretIv,
                  int secretKeyVersion,
                  String boundLineUserId,
                  Set<Scope> scopes,
                  Integer rateLimitPerMin,
                  Integer dailyMessageQuota,
                  Instant now) {
        this.clientId = clientId;
        this.name = name;
        this.secretCiphertext = secretCiphertext.clone();
        this.secretIv = secretIv.clone();
        this.secretKeyVersion = secretKeyVersion;
        this.boundLineUserId = boundLineUserId;
        this.scopes = scopes.isEmpty() ? EnumSet.noneOf(Scope.class) : EnumSet.copyOf(scopes);
        this.rateLimitPerMin = rateLimitPerMin;
        this.dailyMessageQuota = dailyMessageQuota;
        this.status = ClientStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------- 狀態轉換

    /** 可回復的暫停。 */
    public void disable(Instant now) {
        this.status = ClientStatus.DISABLED;
        this.updatedAt = now;
    }

    /** 不可回復的作廢。 */
    public void revoke(Instant now) {
        this.status = ClientStatus.REVOKED;
        this.updatedAt = now;
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = now;
    }

    // ------------------------------------------------------------------ 查詢

    public boolean hasScope(Scope scope) {
        return scopes.contains(scope);
    }

    public boolean isBound() {
        return boundLineUserId != null;
    }

    public boolean isUsable() {
        return status.isUsable();
    }

    // ---------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getName() {
        return name;
    }

    public byte[] getSecretCiphertext() {
        return secretCiphertext.clone();
    }

    public byte[] getSecretIv() {
        return secretIv.clone();
    }

    public int getSecretKeyVersion() {
        return secretKeyVersion;
    }

    public String getBoundLineUserId() {
        return boundLineUserId;
    }

    public ClientStatus getStatus() {
        return status;
    }

    public Integer getRateLimitPerMin() {
        return rateLimitPerMin;
    }

    public Integer getDailyMessageQuota() {
        return dailyMessageQuota;
    }

    public Set<Scope> getScopes() {
        return Collections.unmodifiableSet(scopes);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public TargetType getDefaultTargetType() {
        return defaultTargetType;
    }

    public List<String> getDefaultTargetUserIds() {
        return defaultTargetUserIds == null ? List.of() : List.of(defaultTargetUserIds);
    }

    public boolean hasDefaultTarget() {
        return defaultTargetType != null;
    }

    /**
     * 由管理者設定預設通知對象。
     *
     * <p>{@code type = null} 代表清除設定，之後該 client 每次都必須自己指定 target。
     *
     * <p>不變式在這裡強制：USER 一定要有名單，其餘型別一定不能有。資料庫也有相同的
     * 約束（{@code client_default_target_users_chk}），兩邊都擋是刻意的 ——
     * 應用層給出好的錯誤訊息，資料庫則保證即使有人直接下 SQL 也進不了壞資料。
     */
    public void setDefaultTarget(TargetType type, List<String> userIds, Instant now) {
        if (type == TargetType.USER) {
            if (userIds == null || userIds.isEmpty()) {
                throw new IllegalArgumentException("default target USER requires at least one userId");
            }
            this.defaultTargetUserIds = List.copyOf(userIds).toArray(String[]::new);
        } else {
            if (userIds != null && !userIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "userIds is only allowed when the default target type is USER");
            }
            this.defaultTargetUserIds = null;
        }
        this.defaultTargetType = type;
        this.updatedAt = now;
    }

    /** 不輸出任何密文或 IV。 */
    @Override
    public String toString() {
        return "Client[" + clientId + " status=" + status + " scopes=" + scopes + "]";
    }
}
