package com.jason.notifyline.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * {@code NEW_ITEMS} 模式已看過的項目鍵。schema 見 {@code V4__api_monitor.sql} §3.3。
 *
 * <p>{@code item_key} 超過 200 字元時呼叫端要自行取 SHA-256 十六進位字串再傳進來
 * ——不截斷，截斷會讓兩個不同項目撞成同一個 key，變成「新項目被當成看過的」而
 * 永遠不通知。這條規則本波次不強制驗證，留給實際萃取項目鍵的波次（W2b）負責。
 */
@Entity
@Table(name = "api_monitor_seen_item")
public class SeenItem {

    @EmbeddedId
    private SeenItemId id;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    protected SeenItem() {
        // JPA
    }

    public SeenItem(Long monitorId, String itemKey, Instant firstSeenAt) {
        this.id = new SeenItemId(monitorId, itemKey);
        this.firstSeenAt = firstSeenAt;
    }

    // ---------------------------------------------------------------- getters

    public SeenItemId getId() {
        return id;
    }

    public Long getMonitorId() {
        return id.monitorId();
    }

    public String getItemKey() {
        return id.itemKey();
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    @Override
    public String toString() {
        return "SeenItem[" + id + " firstSeenAt=" + firstSeenAt + "]";
    }
}
