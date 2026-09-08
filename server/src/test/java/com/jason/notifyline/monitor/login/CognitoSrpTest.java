package com.jason.notifyline.monitor.login;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CognitoSrp} 的驗收標準。見 {@code Docs/plan/15-監控站台登入設計.md} §8。
 *
 * <h2>為什麼向量來自另一個實作</h2>
 *
 * <p>自己寫測試驗自己寫的實作，會把同一個誤解編碼兩次 —— 測試會過，對真正的
 * Cognito 仍然失敗。所以期望值不是手算的，而是由
 * {@code src/test/resources/srp/srp_ref.py}（獨立的 Python 參考實作，刻意用 hex
 * 字串操作而非 BigInteger）產生的 {@code vectors.json}。
 *
 * <p>要重新產生：{@code python server/src/test/resources/srp/srp_ref.py > vectors.json}
 *
 * <p><strong>這仍然不能證明對真實 Cognito 有效</strong>：兩個實作可能共享同一個對
 * 協定的誤解。它能抓到的是 padding、位元組順序、截斷長度這類實作錯誤，也就是
 * 實務上最容易出的那些。最終真相是後台的「測試登入」按鈕跑一次真的登入。
 */
@DisplayName("CognitoSrp")
class CognitoSrpTest {

    private static JsonNode v;

    @BeforeAll
    static void loadVectors() {
        try (InputStream in = CognitoSrpTest.class.getResourceAsStream("/srp/vectors.json")) {
            v = new ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("無法載入 /srp/vectors.json", e);
        }
    }

    private static String s(String field) {
        return v.get(field).asString();
    }

    private static BigInteger big(String field) {
        return new BigInteger(s(field), 16);
    }

    @Nested
    @DisplayName("與 Python 參考實作對拍")
    class CrossCheck {

        /**
         * 用固定的 a（不是隨機產生的）重建金鑰對，才能重現參考實作的每一個中間值。
         * 正式流程一律走 {@link CognitoSrp#generateKeyPair}。
         */
        private CognitoSrp.KeyPair fixedKeyPair() {
            BigInteger smallA = big("smallAHex");
            return new CognitoSrp.KeyPair(smallA, big("bigAHex"));
        }

        @Test
        @DisplayName("A = g^a mod N 與參考實作一致")
        void bigAMatches() {
            BigInteger n = new BigInteger(nHex(), 16);
            BigInteger computed = BigInteger.TWO.modPow(big("smallAHex"), n);

            assertThat(computed.toString(16)).isEqualTo(s("bigAHex"));
        }

        @Test
        @DisplayName("推導金鑰（hkdf）與參考實作一致")
        void derivedKeyMatches() {
            byte[] key = CognitoSrp.passwordAuthenticationKey(
                    s("poolName"), s("username"), s("password"),
                    fixedKeyPair(), big("bigBHex"), s("saltHex"),
                    CognitoSrp.SaltEncoding.PRESERVE);

            assertThat(HexFormat.of().formatHex(key)).isEqualTo(s("hkdfHex"));
        }

        @Test
        @DisplayName("PASSWORD_CLAIM_SIGNATURE 與參考實作一致")
        void signatureMatches() {
            byte[] key = CognitoSrp.passwordAuthenticationKey(
                    s("poolName"), s("username"), s("password"),
                    fixedKeyPair(), big("bigBHex"), s("saltHex"),
                    CognitoSrp.SaltEncoding.PRESERVE);

            String signature = CognitoSrp.passwordClaimSignature(
                    key, s("poolName"), s("username"),
                    Base64.getDecoder().decode(s("secretBlockB64")), s("timestamp"));

            assertThat(signature).isEqualTo(s("signatureB64"));
        }

        /** N 只在測試裡需要，正式碼裡是 private——這裡從參考實作的定義重建一份。 */
        private String nHex() {
            return "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
                    + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
                    + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                    + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
                    + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
                    + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
                    + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
                    + "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D040A99D9"
                    + "556AE0F19B9F42B54BC9F9BB1F60FEB1EDCEB9D69B70F2ED8B0AE4C0D2AABB48"
                    + "0C34C0F3D5E9C2CD9A8F44F1F1B2B8CE1CE6BD8FF67F1CDC1EFDD5C8B4D0DA0F"
                    + "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";
        }
    }

    @Nested
    @DisplayName("三個一定會踩的坑")
    class KnownTraps {

        @Test
        @DisplayName("poolName 去掉 region 前綴")
        void poolNameStripsRegion() {
            assertThat(CognitoSrp.poolNameOf("eu-west-2_FhQHPoX2z")).isEqualTo("FhQHPoX2z");
        }

        @ParameterizedTest
        @DisplayName("userPoolId 格式不對就拒絕，不會安靜地算出錯的簽章")
        @CsvSource({"FhQHPoX2z", "eu-west-2_", "''"})
        void poolNameRejectsMalformed(String input) {
            assertThatThrownBy(() -> CognitoSrp.poolNameOf(input))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("timestamp 是 en-US、UTC、日期不補零")
        void timestampFormat() {
            // 2026-09-09 是星期三；9 號必須是 "9" 不是 "09"
            assertThat(CognitoSrp.timestamp(Instant.parse("2026-09-09T01:23:45Z")))
                    .isEqualTo("Wed Sep 9 01:23:45 UTC 2026");
        }

        @Test
        @DisplayName("兩位數日期不受影響")
        void timestampTwoDigitDay() {
            assertThat(CognitoSrp.timestamp(Instant.parse("2026-09-19T22:05:00Z")))
                    .isEqualTo("Sat Sep 19 22:05:00 UTC 2026");
        }
    }

    @Nested
    @DisplayName("padHex")
    class PadHex {

        @ParameterizedTest
        @DisplayName("奇數長度補一個 0；最高位元為 1 補 00；其餘不動")
        @CsvSource({
                "abc,   0abc",
                "0abc,  0abc",
                "ff,    00ff",
                "8000,  008000",
                "7fff,  7fff",
                "00ab,  00ab"
        })
        void rules(String input, String expected) {
            assertThat(CognitoSrp.padHex(input)).isEqualTo(expected);
        }

        /**
         * 歧義範圍比直覺窄：{@code padHex(BigInteger)} 在最高位元為 1 時會把 {@code 00}
         * 補回去，所以 {@code "00ab12"} 兩種編碼其實相同。真正會分岔的是「前導 {@code 00}
         * 之後那個位元組最高位元為 0」——約 1/512 的 salt，不是 1/256。
         */
        @Test
        @DisplayName("兩種 salt 編碼只在前導 00 後接高位元為 0 的位元組時分岔")
        void saltEncodingsDifferOnlyOnLeadingZeroByteFollowedByLowByte() {
            // 前導 00 後面是 0x12（最高位元為 0）→ BigInteger 版會吃掉那個零位元組
            assertThat(CognitoSrp.padHex("0012ab")).isEqualTo("0012ab");
            assertThat(CognitoSrp.padHex(new BigInteger("0012ab", 16))).isEqualTo("12ab");

            // 前導 00 後面是 0xab（最高位元為 1）→ padHex 會把 00 補回去，兩者相同
            assertThat(CognitoSrp.padHex("00ab12"))
                    .isEqualTo(CognitoSrp.padHex(new BigInteger("00ab12", 16)))
                    .isEqualTo("00ab12");

            // 沒有前導零位元組 → 永遠相同
            assertThat(CognitoSrp.padHex("ab12"))
                    .isEqualTo(CognitoSrp.padHex(new BigInteger("ab12", 16)));
        }
    }

    @Nested
    @DisplayName("安全檢查")
    class SafetyChecks {

        @Test
        @DisplayName("B ≡ 0 (mod N) 直接拒絕")
        void rejectsZeroB() {
            CognitoSrp.KeyPair pair = CognitoSrp.generateKeyPair(new SecureRandom());

            assertThatThrownBy(() -> CognitoSrp.passwordAuthenticationKey(
                    "pool", "user", "pw", pair, BigInteger.ZERO, "ab", CognitoSrp.SaltEncoding.PRESERVE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SRP_B");
        }

        @Test
        @DisplayName("每次產生的 A 都不同，且非零")
        void keyPairIsRandomAndNonZero() {
            SecureRandom random = new SecureRandom();
            CognitoSrp.KeyPair first = CognitoSrp.generateKeyPair(random);
            CognitoSrp.KeyPair second = CognitoSrp.generateKeyPair(random);

            assertThat(first.bigA()).isNotEqualTo(second.bigA());
            assertThat(first.bigA().signum()).isPositive();
        }
    }

    @Nested
    @DisplayName("hash 輔助")
    class Hashing {

        @Test
        @DisplayName("hashSha256 回傳 64 字元小寫 hex")
        void hashFormat() {
            String hash = CognitoSrp.hashSha256("abc".getBytes(StandardCharsets.UTF_8));

            assertThat(hash).hasSize(64)
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }

        @Test
        @DisplayName("hexHash 是先把 hex 解成位元組再雜湊，不是雜湊那串字")
        void hexHashDecodesFirst() {
            assertThat(CognitoSrp.hexHash("616263"))
                    .isEqualTo(CognitoSrp.hashSha256("abc".getBytes(StandardCharsets.UTF_8)));
        }
    }
}
