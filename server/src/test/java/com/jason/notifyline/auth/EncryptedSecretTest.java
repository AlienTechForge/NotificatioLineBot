package com.jason.notifyline.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EncryptedSecret} 的 record 語意。
 *
 * <p>record 對陣列成員預設用 identity 比較，會讓 {@code equals} 形同無用 ——
 * 兩個內容相同的實例會被判定為不相等。這裡明確覆寫成內容比較，所以需要測試
 * 守住這個行為，否則日後有人「簡化」掉覆寫也不會有人發現。
 */
@DisplayName("EncryptedSecret")
class EncryptedSecretTest {

    private static final byte[] CIPHERTEXT = {1, 2, 3, 4};
    private static final byte[] IV = {9, 8, 7};

    @Test
    @DisplayName("equals：內容相同即相等，不是 identity 比較")
    void equals_sameContent_areEqual() {
        EncryptedSecret a = new EncryptedSecret(CIPHERTEXT.clone(), IV.clone(), 1);
        EncryptedSecret b = new EncryptedSecret(CIPHERTEXT.clone(), IV.clone(), 1);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    @DisplayName("equals：密文、IV 或版本任一不同即不相等")
    void equals_anyFieldDiffers_areNotEqual() {
        EncryptedSecret base = new EncryptedSecret(CIPHERTEXT, IV, 1);

        assertThat(base).isNotEqualTo(new EncryptedSecret(new byte[]{1, 2, 3, 5}, IV, 1));
        assertThat(base).isNotEqualTo(new EncryptedSecret(CIPHERTEXT, new byte[]{9, 8, 6}, 1));
        assertThat(base).isNotEqualTo(new EncryptedSecret(CIPHERTEXT, IV, 2));
        assertThat(base).isNotEqualTo("not an EncryptedSecret");
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isEqualTo(base);
    }

    @Test
    @DisplayName("toString：不輸出密文內容，避免整包被寫進日誌")
    void toString_doesNotLeakCiphertext() {
        String text = new EncryptedSecret(CIPHERTEXT, IV, 3).toString();

        assertThat(text).contains("keyVersion=3").contains("ciphertextLength=4");
        // 不應出現任何看起來像密文的十六進位或 base64 傾印
        assertThat(text).doesNotContain("[1, 2, 3, 4]");
    }

    @Test
    @DisplayName("建構時複製陣列，之後改動來源不影響已建立的實例")
    void constructor_copiesArrays() {
        byte[] mutable = CIPHERTEXT.clone();
        EncryptedSecret secret = new EncryptedSecret(mutable, IV, 1);

        mutable[0] = 99;

        assertThat(secret.ciphertext()).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("null 密文或 IV 直接拒絕")
    void constructor_nullArrays_throws() {
        assertThatThrownBy(() -> new EncryptedSecret(null, IV, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EncryptedSecret(CIPHERTEXT, null, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
