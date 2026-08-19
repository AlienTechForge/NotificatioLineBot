package com.jason.notifyline.lineuser;

/**
 * 好友狀態。
 *
 * <p>刻意只有兩個值。不加 {@code DELETED}：LINE 端沒有可靠的「帳號已刪除」事件，
 * 多一個猜測性的狀態只會製造不確定。Profile API 回 404 一律視為 BLOCKED。
 */
public enum LineUserStatus {
    ACTIVE,
    BLOCKED
}
