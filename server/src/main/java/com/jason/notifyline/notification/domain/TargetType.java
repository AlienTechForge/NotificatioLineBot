package com.jason.notifyline.notification.domain;

import com.jason.notifyline.client.Scope;

/**
 * 通知對象。每一型對應一個必要的 scope。
 *
 * <p>見 {@code Docs/plan/03-權限與認證設計.md} §1.3 的允許矩陣。
 */
public enum TargetType {

    /** 該 client 綁定的那一個 LINE user。 */
    SELF(Scope.NOTIFY_SELF),

    /** 所有 is_owner 且 ACTIVE 的 user。 */
    OWNER(Scope.NOTIFY_OWNER),

    /** 請求中指定的 userIds。僅 OWNER 可用。 */
    USER(Scope.NOTIFY_USER),

    /** 所有 ACTIVE user。僅 OWNER 可用。 */
    ALL(Scope.NOTIFY_ALL);

    private final Scope requiredScope;

    TargetType(Scope requiredScope) {
        this.requiredScope = requiredScope;
    }

    public Scope requiredScope() {
        return requiredScope;
    }

    /**
     * 優先級。見缺口 G10 與 07 §9。
     *
     * <p>OWNER 幾乎都是系統告警（最需要即時）；ALL 幾乎都是公告（可以等）。
     * 單一 FIFO 佇列會讓 1200 人的公告卡住後面的緊急告警。
     */
    public short priority() {
        return switch (this) {
            case OWNER -> 0;
            case SELF, USER -> 5;
            case ALL -> 9;
        };
    }
}
