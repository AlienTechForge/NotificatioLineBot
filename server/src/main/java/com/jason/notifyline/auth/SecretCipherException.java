package com.jason.notifyline.auth;

/**
 * Client secret 的加解密失敗。
 *
 * <p>訊息中絕不可包含明文 secret、加密金鑰或密文內容 —— 這個例外會被記錄到
 * 日誌，而日誌的存取控制通常比資料庫寬鬆。
 */
public class SecretCipherException extends RuntimeException {

    public SecretCipherException(String message) {
        super(message);
    }

    public SecretCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
