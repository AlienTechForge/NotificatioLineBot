package com.jason.notifyline.common;

/**
 * LINE Messaging API 的硬性限制。查證於 2026-08-18 的官方 API reference，
 * 對照表見 {@code Docs/plan/06-LINE整合設計.md} §1.1。
 *
 * <p>集中一處定義是因為這些數字散落在切批、驗證、測試三個地方。改成 501 是
 * 一個 HTTP 400，而 400 只會在正式環境的某一次大量發送時才第一次出現。
 */
public final class LineLimits {

    /** multicast 單次收件人上限。超過就要切批。 */
    public static final int MULTICAST_MAX_RECIPIENTS = 500;

    /** 單次請求的 message object 上限。 */
    public static final int MAX_MESSAGE_OBJECTS = 5;

    /** 文字訊息的字元上限。 */
    public static final int MAX_TEXT_LENGTH = 5000;

    /**
     * multicast 的速率上限（req/s）。
     *
     * <p>我們的 RateLimiter 只設一半，留餘裕給 webhook 回覆與 profile 校正 ——
     * 它們與發送共用同一個 channel 的配額。
     */
    public static final int MULTICAST_RATE_PER_SECOND = 200;

    private LineLimits() {
    }
}
