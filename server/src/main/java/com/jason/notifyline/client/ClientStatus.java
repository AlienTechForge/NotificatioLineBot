package com.jason.notifyline.client;

/**
 * Client 憑證狀態。
 *
 * <p>{@link #DISABLED} 與 {@link #REVOKED} 的差別是「可否回復」，
 * 兩者在認證時都一樣被拒絕，但語意不同：緊急拔掉失控來源之後可能想恢復，
 * 使用者重設金鑰後的舊金鑰則永遠不該再啟用。
 */
public enum ClientStatus {

    /** 正常可用。 */
    ACTIVE,

    /** 可回復的暫停 —— 例如失控時緊急拔掉，或綁定的使用者封鎖了 Bot。 */
    DISABLED,

    /** 不可回復的作廢 —— 例如使用者重設金鑰。舊記錄保留供稽核。 */
    REVOKED;

    public boolean isUsable() {
        return this == ACTIVE;
    }
}
