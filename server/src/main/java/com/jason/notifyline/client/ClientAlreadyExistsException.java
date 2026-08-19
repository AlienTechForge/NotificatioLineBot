package com.jason.notifyline.client;

/**
 * 該 LINE user 已有一組 ACTIVE 金鑰。
 *
 * <p>由資料庫的 partial unique index {@code uq_client_bound_active} 偵測，
 * 不是應用程式「先查再插」—— 後者在併發下會兩個都成功。
 */
public class ClientAlreadyExistsException extends RuntimeException {

    private final String lineUserId;

    public ClientAlreadyExistsException(String lineUserId, Throwable cause) {
        super("該 LINE user 已有一組有效金鑰，要重新產生請先撤銷舊的：" + lineUserId, cause);
        this.lineUserId = lineUserId;
    }

    public String getLineUserId() {
        return lineUserId;
    }
}
