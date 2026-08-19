package com.jason.notifyline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client secret 的加密金鑰設定。
 *
 * <pre>
 * app:
 *   crypto:
 *     current-key-version: 1
 *     keys:
 *       "1": ${APP_SECRET_ENC_KEY}       # base64 的 32 bytes
 *       "2": ${APP_SECRET_ENC_KEY_V2:}   # 輪替期間才會有
 * </pre>
 *
 * <p>空字串視同未設定並被忽略 —— 這讓 {@code .env} 可以固定列出 V2 欄位而不必
 * 在非輪替期間刪掉它。
 *
 * @param currentKeyVersion 新資料使用的金鑰版本
 * @param keys              版本 → base64 金鑰
 */
@ConfigurationProperties(prefix = "app.crypto")
public record CryptoProperties(int currentKeyVersion, Map<Integer, String> keys) {

    public CryptoProperties {
        if (currentKeyVersion <= 0) {
            currentKeyVersion = 1;
        }
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }

    /** 濾掉空白值後的金鑰設定，保持版本順序以利錯誤訊息閱讀。 */
    public Map<Integer, String> nonBlankKeys() {
        Map<Integer, String> result = new LinkedHashMap<>();
        keys.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().trim()));
        return result;
    }
}
