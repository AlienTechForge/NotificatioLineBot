package com.jason.notifyline.notification.domain;

/**
 * 通知的整體狀態。
 *
 * <pre>
 * QUEUED ──→ SENDING ──┬──→ SUCCEEDED   全批成功
 *                      ├──→ PARTIAL     部分批次用盡重試仍失敗
 *                      └──→ FAILED      全部失敗，或遇到終局錯誤
 * </pre>
 *
 * <p><strong>SUCCEEDED 的意思是「LINE 接受了請求」，不是「使用者看到了訊息」。</strong>
 * LINE 對已封鎖帳號的使用者仍會回 200（見 06 §1.3），任何系統都無法從發送回應
 * 判斷實際觸及。
 */
public enum NotificationStatus {
    QUEUED,
    SENDING,
    SUCCEEDED,
    PARTIAL,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == PARTIAL || this == FAILED;
    }
}
