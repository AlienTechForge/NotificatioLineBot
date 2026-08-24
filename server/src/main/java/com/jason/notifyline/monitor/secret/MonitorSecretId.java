package com.jason.notifyline.monitor.secret;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * {@link MonitorSecret} 的複合主鍵：{@code (monitor_id, name)}。
 *
 * <p>用 {@code @EmbeddedId} 而非 {@code @IdClass}，理由同
 * {@code com.jason.notifyline.monitor.domain.SeenItemId}：Hibernate ORM 6.2+ 對
 * record 當 embeddable 型別有原生支援，不必再多寫一個可變的 JavaBean id class。
 */
@Embeddable
public record MonitorSecretId(
        @Column(name = "monitor_id") Long monitorId,
        @Column(name = "name", length = 32) String name)
        implements Serializable {
}
