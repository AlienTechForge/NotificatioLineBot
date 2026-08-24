package com.jason.notifyline.monitor.secret;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 監控專屬的機敏常數（appsecret、device id 之類）。schema 見
 * {@code V6__monitor_computed.sql}。見 {@code Docs/plan/13-監控計算欄位設計.md} §2.1。
 *
 * <p>加密／解密邏輯不放在這裡——那是 {@link MonitorSecretService}（需要
 * {@code SecretCipher}）的職責，這個類別只負責欄位的保存與狀態轉換，跟
 * {@code SiteSession} / {@code ApiMonitor} 的分層方式一致。
 */
@Entity
@Table(name = "monitor_secret")
public class MonitorSecret {

    @EmbeddedId
    private MonitorSecretId id;

    /**
     * AES-256-GCM，AAD = {@code "monitor_secret:" + monitorId + ":" + name}。
     * 值絕不回傳到任何 DTO。
     */
    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @Column(name = "iv", nullable = false)
    private byte[] iv;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitorSecret() {
        // JPA
    }

    public MonitorSecret(Long monitorId, String name, byte[] ciphertext, byte[] iv, int keyVersion, Instant now) {
        this.id = new MonitorSecretId(monitorId, name);
        this.ciphertext = ciphertext.clone();
        this.iv = iv.clone();
        this.keyVersion = keyVersion;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 覆寫既有值（使用者重新輸入了非空值）。 */
    public void applyValue(byte[] ciphertext, byte[] iv, int keyVersion, Instant now) {
        this.ciphertext = ciphertext.clone();
        this.iv = iv.clone();
        this.keyVersion = keyVersion;
        this.updatedAt = now;
    }

    // ---------------------------------------------------------------- getters

    public MonitorSecretId getId() {
        return id;
    }

    public Long getMonitorId() {
        return id.monitorId();
    }

    public String getName() {
        return id.name();
    }

    public byte[] getCiphertext() {
        return ciphertext.clone();
    }

    public byte[] getIv() {
        return iv.clone();
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 不輸出密文/IV。 */
    @Override
    public String toString() {
        return "MonitorSecret[" + id + "]";
    }
}
