package com.jason.notifyline.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Client 權限。見 {@code Docs/plan/adr/0004-權限採-scope-集合而非固定角色.md}。
 *
 * <p>「Client 類型」不是欄位，而是「綁定狀態 + scope 組合」的結果。這讓新增權限
 * 組合只需要改資料，不需要改程式。
 */
public enum Scope {

    /** 發給該 client 綁定的那一個 LINE user。 */
    NOTIFY_SELF("notify:self"),

    /** 發給所有 is_owner 且 ACTIVE 的 user。 */
    NOTIFY_OWNER("notify:owner"),

    /** 發給請求中任意指定的 userIds。僅 OWNER 可持有。 */
    NOTIFY_USER("notify:user"),

    /** 發給所有 ACTIVE user。僅 OWNER 可持有。 */
    NOTIFY_ALL("notify:all"),

    /**
     * 允許傳原始 LINE message object。
     *
     * <p><strong>預設不給任何 client</strong> —— 原始 message object 可帶 uri action，
     * 一組外洩的金鑰就能發出掛著官方帳號名義的釣魚連結。見缺口 G5。
     */
    NOTIFY_RAW("notify:raw");

    /** 只有 OWNER 能被授予的 scope。 */
    private static final Set<Scope> OWNER_ONLY =
            Collections.unmodifiableSet(EnumSet.of(NOTIFY_USER, NOTIFY_ALL, NOTIFY_RAW));

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    /** 資料庫與 API 使用的字串形式。 */
    public String value() {
        return value;
    }

    public boolean isOwnerOnly() {
        return OWNER_ONLY.contains(this);
    }

    public static Set<Scope> ownerOnly() {
        return OWNER_ONLY;
    }

    public static Scope fromValue(String value) {
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown scope: " + value));
    }
}
