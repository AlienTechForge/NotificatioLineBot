package com.jason.notifyline.monitor.domain;

/**
 * 單次輪詢執行結果。見 {@code Docs/plan/11-API監控輪詢設計.md} §3.2。
 */
public enum RunOutcome {

    /** 偵測到變更，且通過防洗版檢查，已呼叫 {@code notificationService.submit()}。 */
    CHANGED,

    /** 抓取成功但沒有變更（或是首次執行只記錄基準）。 */
    UNCHANGED,

    /** 抓取或解析失敗。 */
    FAILED,

    /**
     * 有變更但被防洗版擋下（冷卻中或已達每日上限）。
     *
     * <p>刻意與 {@code UNCHANGED} 分開 —— 兩者對「要不要更新 fingerprint」的處理不同
     * （SKIPPED 不更新，見 §8），管理台也需要能分辨「真的沒變」與「有變但沒發」。
     */
    SKIPPED
}
