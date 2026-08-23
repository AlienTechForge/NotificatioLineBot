package com.jason.notifyline.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * {@link SeenItem} 的複合主鍵：{@code (monitor_id, item_key)}。
 *
 * <p>用 {@code @EmbeddedId} 而非 {@code @IdClass} —— Hibernate ORM 6.2+ 對 record
 * 當 embeddable 型別有原生支援（用 canonical constructor 還原實例），不必再多寫
 * 一個可變的 JavaBean id class 只為了滿足 JPA 的規格要求。
 */
@Embeddable
public record SeenItemId(
        @Column(name = "monitor_id") Long monitorId,
        @Column(name = "item_key", length = 200) String itemKey)
        implements Serializable {
}
