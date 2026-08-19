package com.jason.notifyline.notification.domain;

/** 單一批次的投遞狀態。 */
public enum DeliveryStatus {

    /** 待送出或待重試。派送器只取這個狀態的批次。 */
    PENDING,

    /** LINE 已接受。 */
    SENT,

    /** 終局失敗，或用盡重試。 */
    FAILED
}
