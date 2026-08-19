package com.jason.notifyline.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 已使用過的 nonce。見 {@code Docs/plan/03-權限與認證設計.md} §2.5。
 *
 * <p>這個實體<strong>刻意只做 schema 對應，不提供建構子與 getter</strong>：
 * 寫入走 {@link RequestNonceRepository#insertIfAbsent} 的原生
 * {@code ON CONFLICT DO NOTHING}，清理走 JPQL 的批次 delete，兩者都不經過實體實例。
 *
 * <p>為什麼不用 {@code save()}：主鍵是外部指定的 String，Spring Data 判定它
 * 「不是新實體」而走 {@code merge()} —— 對已存在的列是 UPDATE，不會違反約束，
 * 於是重放的 nonce 被靜默覆寫，<strong>重放防護完全失效且毫無錯誤訊息</strong>。
 */
@Entity
@Table(name = "request_nonce")
public class RequestNonce {

    @Id
    @Column(name = "nonce", length = 64)
    private String nonce;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    protected RequestNonce() {
        // JPA
    }
}
