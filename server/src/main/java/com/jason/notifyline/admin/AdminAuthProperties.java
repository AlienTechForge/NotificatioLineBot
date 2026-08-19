package com.jason.notifyline.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理介面的登入帳密。來源是 GitHub Secrets，部署時寫進伺服器的 {@code .env}。
 *
 * <p><strong>兩者都留空 = 整個管理介面停用</strong>（連登入頁都不存在）。
 * 這是刻意的預設值：忘記設定的後果是「後台用不了」，而不是「後台沒有密碼」。
 * 反過來設計的話，一次設定疏漏就是一個對外開放的管理後台。
 *
 * @param username 登入帳號
 * @param password 登入密碼的<strong>明文</strong>。啟動時會雜湊，之後記憶體裡只留雜湊值
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminAuthProperties(String username, String password) {

    /** 密碼長度下限。管理後台能改變所有通知的流向，不接受短密碼。 */
    public static final int MIN_PASSWORD_LENGTH = 12;

    public AdminAuthProperties {
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
    }

    public boolean isConfigured() {
        return !username.isBlank() && !password.isBlank();
    }

    /**
     * @throws IllegalStateException 設定了但密碼太短 —— 啟動時就失敗，
     *         而不是等到某天被猜到
     */
    public void validate() {
        if (isConfigured() && password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "APP_ADMIN_PASSWORD 至少要 " + MIN_PASSWORD_LENGTH + " 個字元");
        }
    }
}
