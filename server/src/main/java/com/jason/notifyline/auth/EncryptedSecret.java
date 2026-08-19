package com.jason.notifyline.auth;

import java.util.Arrays;
import java.util.Objects;

/**
 * AES-256-GCM 加密後的 client secret，對應 {@code client} 表的三個欄位。
 *
 * <p>陣列在建構與讀取時都會複製，呼叫端拿到的永遠是獨立副本。
 *
 * @param ciphertext 密文 + 16 bytes authentication tag
 * @param iv         12 bytes，每筆記錄獨立產生，絕不重複使用
 * @param keyVersion 對應 {@code client.secret_key_version}，支援不停機輪替
 */
public record EncryptedSecret(byte[] ciphertext, byte[] iv, int keyVersion) {

    public EncryptedSecret {
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(iv, "iv");
        ciphertext = ciphertext.clone();
        iv = iv.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] iv() {
        return iv.clone();
    }

    // record 預設對陣列用 identity 比較，會讓 equals 形同無用。改為內容比較。
    @Override
    public boolean equals(Object other) {
        return other instanceof EncryptedSecret that
                && keyVersion == that.keyVersion
                && Arrays.equals(ciphertext, that.ciphertext)
                && Arrays.equals(iv, that.iv);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(ciphertext), Arrays.hashCode(iv), keyVersion);
    }

    /** 不輸出密文內容，避免整包被寫進日誌。 */
    @Override
    public String toString() {
        return "EncryptedSecret[keyVersion=" + keyVersion + ", ciphertextLength=" + ciphertext.length + "]";
    }
}
