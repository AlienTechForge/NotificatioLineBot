package com.jason.notifyline.monitor.domain;

/**
 * 監控的比對模式。見 {@code Docs/plan/11-API監控輪詢設計.md} §6.2。
 */
public enum CompareMode {

    /** 比對整個回應 body（{@code SHA-256(body)}）。 */
    WHOLE_BODY,

    /** 只比對 {@code extract_rules} 取出的值（{@code SHA-256(canonical(name=value 依 name 排序))}）。 */
    EXTRACTED,

    /**
     * 不比指紋，改比對 {@code api_monitor_seen_item} 表：只通知沒看過的項目。
     *
     * <p>此模式一定要有 {@code item_pointer} 與 {@code item_key_pointer}，否則無從
     * 判斷「新」——見 migration 的 {@code api_monitor_newitems_chk}。
     */
    NEW_ITEMS
}
