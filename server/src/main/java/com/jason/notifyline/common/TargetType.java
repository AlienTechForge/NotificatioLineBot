package com.jason.notifyline.common;

/**
 * 通知對象的型別。
 *
 * <p>見 {@code Docs/plan/03-權限與認證設計.md} §1.3 的允許矩陣。
 *
 * <p><strong>為什麼放在 {@code common} 而不是 {@code notification.domain}</strong>：
 * {@code client} 也要用它（預設通知對象），而 {@code notification} 本來就依賴
 * {@code client}。放在任一邊都會形成套件循環，讓日後想把通知功能拆出去時
 * 得先解一團互相引用。
 *
 * <p>也因此這個列舉<strong>刻意不知道 scope 的存在</strong>。「哪一型需要哪個
 * scope」是授權規則，屬於 {@code TargetResolver}；混進來就會把 {@code common}
 * 綁死在 {@code client} 上。
 */
public enum TargetType {

    /** 該 client 綁定的那一個 LINE user。 */
    SELF,

    /** 所有 is_owner 且 ACTIVE 的 user。 */
    OWNER,

    /** 明確指定的 userIds。 */
    USER,

    /** 所有 ACTIVE user。 */
    ALL;

    /**
     * 派送優先級，0 最高。見缺口 G10 與 07 §9。
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

    /** {@code USER} 是唯一需要附帶收件人清單的型別。 */
    public boolean requiresUserIds() {
        return this == USER;
    }
}
