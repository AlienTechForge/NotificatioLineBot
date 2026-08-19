package com.jason.notifyline.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client secret 的加密儲存。見 {@code Docs/plan/adr/0002-client-secret-加密儲存而非雜湊.md}。
 *
 * <p>HMAC 驗簽需要伺服器重算簽章，所以 secret 必須「可還原」——只能加密不能雜湊。
 * 因此這把加密 key 保護了所有憑證，正確性要求很高。
 */
@DisplayName("SecretCipher")
class SecretCipherTest {

    private static final byte[] KEY_V1 = key((byte) 0x11);
    private static final byte[] KEY_V2 = key((byte) 0x22);
    private static final byte[] KEY_OTHER = key((byte) 0x99);

    private static final String CLIENT_ID = "cli_a1b2c3d4e5f6g7h8i9j0";
    private static final String SECRET = "s0me-cl1ent-secret-value-48-bytes-worth-of-entropy";

    private static byte[] key(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return k;
    }

    private static SecretCipher singleKeyCipher() {
        return new SecretCipher(Map.of(1, KEY_V1), 1);
    }

    // ------------------------------------------------------------- round-trip

    @Test
    @DisplayName("加密後解密得回原文")
    void encryptThenDecrypt_returnsOriginal() {
        SecretCipher cipher = singleKeyCipher();

        EncryptedSecret enc = cipher.encrypt(SECRET, CLIENT_ID);
        String decrypted = cipher.decrypt(enc.ciphertext(), enc.iv(), enc.keyVersion(), CLIENT_ID);

        assertThat(decrypted).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("加密結果帶當前 key 版本與 12 bytes IV")
    void encrypt_result_carriesVersionAndIv() {
        EncryptedSecret enc = singleKeyCipher().encrypt(SECRET, CLIENT_ID);

        assertThat(enc.keyVersion()).isEqualTo(1);
        assertThat(enc.iv()).hasSize(12);
        // GCM 密文 = 明文長度 + 16 bytes authentication tag
        assertThat(enc.ciphertext()).hasSize(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 16);
    }

    @Test
    @DisplayName("同一明文加密兩次，密文必須不同（IV 隨機）")
    void encrypt_samePlaintextTwice_producesDifferentCiphertext() {
        SecretCipher cipher = singleKeyCipher();

        EncryptedSecret a = cipher.encrypt(SECRET, CLIENT_ID);
        EncryptedSecret b = cipher.encrypt(SECRET, CLIENT_ID);

        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    // ------------------------------------------------------------ 失敗必須拋

    @Test
    @DisplayName("用錯誤的 key 解密會失敗，不會回傳垃圾資料")
    void decrypt_wrongKey_throws() {
        EncryptedSecret enc = singleKeyCipher().encrypt(SECRET, CLIENT_ID);
        SecretCipher otherCipher = new SecretCipher(Map.of(1, KEY_OTHER), 1);

        assertThatThrownBy(() ->
                otherCipher.decrypt(enc.ciphertext(), enc.iv(), 1, CLIENT_ID))
                .isInstanceOf(SecretCipherException.class);
    }

    @Test
    @DisplayName("AAD 不符會失敗 —— 防止把 A 的密文搬到 B 的記錄上")
    void decrypt_wrongAad_throws() {
        SecretCipher cipher = singleKeyCipher();
        EncryptedSecret enc = cipher.encrypt(SECRET, CLIENT_ID);

        assertThatThrownBy(() ->
                cipher.decrypt(enc.ciphertext(), enc.iv(), 1, "cli_someone_elses_id"))
                .isInstanceOf(SecretCipherException.class);
    }

    @Test
    @DisplayName("密文被竄改任一 byte 會失敗（GCM 完整性驗證）")
    void decrypt_tamperedCiphertext_throws() {
        SecretCipher cipher = singleKeyCipher();
        EncryptedSecret enc = cipher.encrypt(SECRET, CLIENT_ID);

        byte[] tampered = enc.ciphertext();
        tampered[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(tampered, enc.iv(), 1, CLIENT_ID))
                .isInstanceOf(SecretCipherException.class);
    }

    @Test
    @DisplayName("IV 被竄改會失敗")
    void decrypt_tamperedIv_throws() {
        SecretCipher cipher = singleKeyCipher();
        EncryptedSecret enc = cipher.encrypt(SECRET, CLIENT_ID);

        byte[] tamperedIv = enc.iv();
        tamperedIv[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(enc.ciphertext(), tamperedIv, 1, CLIENT_ID))
                .isInstanceOf(SecretCipherException.class);
    }

    @Test
    @DisplayName("解密時指定不存在的 key 版本會失敗")
    void decrypt_unknownKeyVersion_throws() {
        SecretCipher cipher = singleKeyCipher();
        EncryptedSecret enc = cipher.encrypt(SECRET, CLIENT_ID);

        assertThatThrownBy(() -> cipher.decrypt(enc.ciphertext(), enc.iv(), 99, CLIENT_ID))
                .isInstanceOf(SecretCipherException.class)
                .hasMessageContaining("99");
    }

    // ------------------------------------------------------------- key 輪替

    @Test
    @DisplayName("輪替期間：新資料用新 key 加密，舊資料仍能用舊 key 解密")
    void rotation_newKeyEncrypts_oldKeyStillDecrypts() {
        // 輪替前：只有 v1
        EncryptedSecret oldData = new SecretCipher(Map.of(1, KEY_V1), 1).encrypt(SECRET, CLIENT_ID);

        // 輪替中：v1 + v2 並存，當前版本是 2
        SecretCipher rotating = new SecretCipher(Map.of(1, KEY_V1, 2, KEY_V2), 2);

        EncryptedSecret newData = rotating.encrypt(SECRET, CLIENT_ID);
        assertThat(newData.keyVersion()).isEqualTo(2);

        // 舊資料照樣讀得到 —— 這是不停機輪替的關鍵
        assertThat(rotating.decrypt(oldData.ciphertext(), oldData.iv(), 1, CLIENT_ID))
                .isEqualTo(SECRET);
        assertThat(rotating.decrypt(newData.ciphertext(), newData.iv(), 2, CLIENT_ID))
                .isEqualTo(SECRET);
    }

    // ---------------------------------------------------------- 建構期防呆

    @Test
    @DisplayName("key 長度不是 32 bytes 時建構就失敗")
    void constructor_keyNot32Bytes_throws() {
        assertThatThrownBy(() -> new SecretCipher(Map.of(1, new byte[16]), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
        assertThatThrownBy(() -> new SecretCipher(Map.of(1, new byte[33]), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("key 為 null 時建構就失敗（Map.of 不接受 null，故用 HashMap）")
    void constructor_nullKey_throws() {
        java.util.Map<Integer, byte[]> keys = new java.util.HashMap<>();
        keys.put(1, null);

        assertThatThrownBy(() -> new SecretCipher(keys, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("沒有任何 key 時建構就失敗")
    void constructor_noKeys_throws() {
        assertThatThrownBy(() -> new SecretCipher(Map.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("當前版本不在 key 集合中時建構就失敗")
    void constructor_currentVersionMissing_throws() {
        assertThatThrownBy(() -> new SecretCipher(Map.of(1, KEY_V1), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
    }

    // ----------------------------------------------------------- 不可變性

    @Test
    @DisplayName("EncryptedSecret 對外複製陣列，呼叫端改不到內部狀態")
    void encryptedSecret_arraysAreDefensivelyCopied() {
        EncryptedSecret enc = singleKeyCipher().encrypt(SECRET, CLIENT_ID);

        byte[] firstRead = enc.ciphertext();
        firstRead[0] ^= 0x7F;

        assertThat(enc.ciphertext()).isNotEqualTo(firstRead);
    }

    @Test
    @DisplayName("建構後修改傳入的 key 陣列不影響已建立的 cipher")
    void constructor_keyArrayIsCopied() {
        byte[] mutable = key((byte) 0x33);
        SecretCipher cipher = new SecretCipher(Map.of(1, mutable), 1);
        EncryptedSecret enc = cipher.encrypt(SECRET, CLIENT_ID);

        java.util.Arrays.fill(mutable, (byte) 0x00);

        assertThat(cipher.decrypt(enc.ciphertext(), enc.iv(), 1, CLIENT_ID)).isEqualTo(SECRET);
    }
}
