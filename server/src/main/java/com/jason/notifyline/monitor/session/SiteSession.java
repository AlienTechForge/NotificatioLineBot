package com.jason.notifyline.monitor.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一個 host 的登入狀態（cookie jar）。schema 見 {@code V5__site_session.sql}。見
 * {@code Docs/plan/12-API監控易用性升級.md} §3.1。
 *
 * <p>{@code host} 本身就是主鍵——這張表天生就是「依 host 查一筆」，沒有同一個 host
 * 存在多筆的情境。加密／解密與 host 比對邏輯<strong>不</strong>放在這個類別裡：
 * 那是 {@link SiteSessionService}（需要 {@code SecretCipher} 與 {@code Clock}）與
 * {@link HostMatcher}（純比對邏輯，不依賴任何 bean）的職責，這個類別只負責欄位的
 * 保存與狀態轉換，跟 {@code ApiMonitor} 的分層方式一致。
 */
@Entity
@Table(name = "site_session")
public class SiteSession {

    @Id
    @Column(name = "host", nullable = false, length = 255)
    private String host;

    /** AES-256-GCM，AAD = {@code site_session:{host}}。明文格式：{@code {"cookieName":"value",...}}。 */
    @Column(name = "jar_ciphertext", nullable = false)
    private byte[] jarCiphertext;

    @Column(name = "jar_iv", nullable = false)
    private byte[] jarIv;

    @Column(name = "jar_key_version", nullable = false)
    private int jarKeyVersion;

    /** 明文存 cookie 名稱（逗號分隔），純粹給後台顯示用。值絕不明文落地。 */
    @Column(name = "cookie_names", nullable = false)
    private String cookieNames;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SiteSession() {
        // JPA
    }

    public SiteSession(String host,
                       byte[] jarCiphertext,
                       byte[] jarIv,
                       int jarKeyVersion,
                       String cookieNames,
                       Instant now) {
        this.host = host;
        this.jarCiphertext = jarCiphertext.clone();
        this.jarIv = jarIv.clone();
        this.jarKeyVersion = jarKeyVersion;
        this.cookieNames = cookieNames == null ? "" : cookieNames;
        this.lastRefreshedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 覆寫整份 jar（重新加密後的密文/IV/版本）與 {@code cookie_names}，並把
     * {@code last_refreshed_at} 推到 {@code now}——不論呼叫來源是使用者重新貼上
     * cookie，還是抓取回應的 {@code Set-Cookie} 合併回寫，兩者都算「這份 jar
     * 被更新了」，語意上不需要區分兩個時間欄位。
     */
    public void applyJar(byte[] jarCiphertext, byte[] jarIv, int jarKeyVersion, String cookieNames, Instant now) {
        this.jarCiphertext = jarCiphertext.clone();
        this.jarIv = jarIv.clone();
        this.jarKeyVersion = jarKeyVersion;
        this.cookieNames = cookieNames == null ? "" : cookieNames;
        this.lastRefreshedAt = now;
        this.updatedAt = now;
    }

    // ---------------------------------------------------------------- getters

    public String getHost() {
        return host;
    }

    public byte[] getJarCiphertext() {
        return jarCiphertext.clone();
    }

    public byte[] getJarIv() {
        return jarIv.clone();
    }

    public int getJarKeyVersion() {
        return jarKeyVersion;
    }

    public String getCookieNames() {
        return cookieNames;
    }

    public Instant getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 不輸出密文/IV。cookie 名稱本身不是機密（見類別註解），保留方便除錯。 */
    @Override
    public String toString() {
        return "SiteSession[" + host + " cookies=" + cookieNames + "]";
    }
}
