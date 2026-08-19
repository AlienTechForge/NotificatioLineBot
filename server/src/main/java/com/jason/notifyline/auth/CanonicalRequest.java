package com.jason.notifyline.auth;

import com.jason.notifyline.common.Ids;

import java.util.HexFormat;
import java.util.Locale;

/**
 * 待簽章的請求正規化表示。
 *
 * <p>規格見 {@code Docs/plan/03-權限與認證設計.md} §2.2：五段以 {@code \n} 串接，
 * <strong>無結尾換行</strong>。
 *
 * <pre>
 * {HTTP METHOD 大寫}
 * {請求路徑，不含 query string}
 * {X-Timestamp}
 * {X-Nonce}
 * {hex_lowercase(sha256(raw request body))}
 * </pre>
 *
 * <p><strong>務必對「實際送出的 body bytes」計算雜湊</strong>，而不是反序列化後
 * 重新序列化的結果 —— 欄位順序、空白、數字格式的任何差異都會讓驗簽失敗，
 * 而症狀只會是一句「簽章不符」。這是接入端最常見的錯誤。
 *
 * @param method         已轉大寫的 HTTP method
 * @param path           請求路徑，不含 query string
 * @param timestamp      epoch seconds 字串
 * @param nonce          一次性隨機值
 * @param bodySha256Hex  body 的 SHA-256，小寫十六進位
 */
public record CanonicalRequest(
        String method,
        String path,
        String timestamp,
        String nonce,
        String bodySha256Hex) {

    private static final byte[] EMPTY_BODY = new byte[0];
    private static final HexFormat HEX = HexFormat.of();

    public CanonicalRequest {
        requireText(method, "method");
        requireText(path, "path");
        requireText(timestamp, "timestamp");
        requireText(nonce, "nonce");
        requireText(bodySha256Hex, "bodySha256Hex");
    }

    /**
     * @param body 可為 null，視同空 body
     */
    public static CanonicalRequest of(String method, String path, String timestamp, String nonce, byte[] body) {
        requireText(method, "method");
        requireText(path, "path");
        if (path.indexOf('?') >= 0) {
            // 目前所有需驗簽的端點都用 body 傳參。若日後有帶 query 的端點，
            // 需擴充 canonical 格式並升 API 版本，不能默默把 query 忽略掉。
            throw new IllegalArgumentException("path must not contain a query string: " + path);
        }
        return new CanonicalRequest(
                method.toUpperCase(Locale.ROOT),
                path,
                timestamp,
                nonce,
                sha256Hex(body == null ? EMPTY_BODY : body));
    }

    /** 五段以 {@code \n} 串接，無結尾換行。 */
    public String toCanonicalString() {
        return String.join("\n", method, path, timestamp, nonce, bodySha256Hex);
    }

    /**
     * body 的 SHA-256，小寫十六進位。
     *
     * <p>空 body 得到的是「空位元組陣列」的雜湊
     * （{@code e3b0c442…}），不是空字串本身。
     */
    public static String sha256Hex(byte[] body) {
        return HEX.formatHex(Ids.sha256(body));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /** canonical string 不含機密，可安全記錄；但仍避免在一般 log 中傾印整包。 */
    @Override
    public String toString() {
        return "CanonicalRequest[" + method + " " + path + " ts=" + timestamp + "]";
    }
}
