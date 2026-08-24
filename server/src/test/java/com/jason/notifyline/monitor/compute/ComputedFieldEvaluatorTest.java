package com.jason.notifyline.monitor.compute;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.monitor.domain.ComputedField;
import com.jason.notifyline.monitor.domain.ComputedStep;
import com.jason.notifyline.monitor.domain.HashAlgorithm;
import com.jason.notifyline.monitor.domain.HashEncoding;
import com.jason.notifyline.monitor.request.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ComputedFieldEvaluator} 的求值規則。見 {@code Docs/plan/13-監控計算欄位設計.md}。
 *
 * <p>{@link RealWorldRegression} 是<strong>驗收標準</strong>：這個演算法是從一個真實
 * 集運站反推出來的，並用兩組獨立的真實 (timestamp, sign) 配對驗證過——見該巢狀類別。
 */
@DisplayName("ComputedFieldEvaluator")
class ComputedFieldEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");
    private static final RequestTemplate.Session SESSION =
            new RequestTemplate.Session(NOW, UUID.fromString("11111111-1111-1111-1111-111111111111"));

    private final ComputedFieldEvaluator evaluator = new ComputedFieldEvaluator();

    /**
     * §1 的真實案例：{@code sign = MD5(MD5(SECRET + timestamp + DEVICE_ID).toUpperCase()).toUpperCase()}。
     * 兩組獨立的真實 (timestamp, sign) 配對——這是整份實作的驗收標準：算不出這兩個值，
     * 不管其他測試過不過，實作都是錯的。
     */
    @Nested
    @DisplayName("§1 真實案例：雙重 MD5，中間轉大寫（驗收標準）")
    class RealWorldRegression {

        private static final String APPSECRET = "YWHZ@&mxZge1A@";
        private static final String DEVICEID = "2b34aabc-6d14-490e-b76a-254097095055";

        private final ComputedField signField = new ComputedField(
                "sign",
                "{{secret.appsecret}}{{now.epochSeconds}}{{secret.deviceid}}",
                List.of(
                        new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null),
                        new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

        @ParameterizedTest(name = "timestamp={0} -> {1}")
        @DisplayName("兩組真實 (timestamp, sign) 配對都要算對")
        @CsvSource({
                "1787565614, CE2201528168DAF1286F95CB1EAB39CD",
                "1787567699, DAD9F5DF984821CA0DDDA73F578FD989"
        })
        void matchesRealWorldVectors(long epochSeconds, String expectedSign) {
            RequestTemplate.Session session = new RequestTemplate.Session(
                    Instant.ofEpochSecond(epochSeconds), UUID.randomUUID());
            Map<String, String> secrets = Map.of("appsecret", APPSECRET, "deviceid", DEVICEID);

            Map<String, String> computed = evaluator.evaluate(List.of(signField), secrets, session);

            assertThat(computed).containsEntry("sign", expectedSign);
        }
    }

    @Nested
    @DisplayName("串接：前一段的輸出字串就是後一段的輸入")
    class Chaining {

        @Test
        @DisplayName("單一步驟：只做一次雜湊")
        void singleStep() {
            ComputedField field = new ComputedField("h", "hello",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_LOWER, null)));

            Map<String, String> computed = evaluator.evaluate(List.of(field), Map.of(), SESSION);

            // MD5("hello") 已知向量
            assertThat(computed.get("h")).isEqualTo("5d41402abc4b2a76b9719d911017c592");
        }

        @Test
        @DisplayName("兩個計算欄位：後一個可以引用前一個（{{computed.NAME}}）")
        void laterFieldReferencesEarlier() {
            ComputedField first = new ComputedField("a", "hello",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_LOWER, null)));
            ComputedField second = new ComputedField("b", "prefix-{{computed.a}}",
                    List.of(new ComputedStep(HashAlgorithm.SHA256, HashEncoding.HEX_LOWER, null)));

            Map<String, String> computed = evaluator.evaluate(List.of(first, second), Map.of(), SESSION);

            assertThat(computed).containsKeys("a", "b");
            assertThat(computed.get("b")).hasSize(64); // SHA-256 hex 長度
        }

        @Test
        @DisplayName("插入順序：回傳的 map 保留 fields 的原始順序")
        void preservesInsertionOrder() {
            ComputedField a = new ComputedField("a", "x", List.of(step(HashAlgorithm.MD5, HashEncoding.HEX_LOWER)));
            ComputedField b = new ComputedField("b", "y", List.of(step(HashAlgorithm.MD5, HashEncoding.HEX_LOWER)));

            Map<String, String> computed = evaluator.evaluate(List.of(a, b), Map.of(), SESSION);

            assertThat(computed.keySet()).containsExactly("a", "b");
        }
    }

    @Nested
    @DisplayName("每種演算法 × 每種編碼 各一組已知向量")
    class KnownVectors {

        @Test
        @DisplayName("MD5 → HEX_UPPER")
        void md5HexUpper() {
            assertThat(hashOnce("abc", HashAlgorithm.MD5, HashEncoding.HEX_UPPER, Map.of()))
                    .isEqualTo("900150983CD24FB0D6963F7D28E17F72");
        }

        @Test
        @DisplayName("SHA1 → HEX_LOWER")
        void sha1HexLower() {
            assertThat(hashOnce("abc", HashAlgorithm.SHA1, HashEncoding.HEX_LOWER, Map.of()))
                    .isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
        }

        @Test
        @DisplayName("SHA256 → BASE64")
        void sha256Base64() {
            assertThat(hashOnce("abc", HashAlgorithm.SHA256, HashEncoding.BASE64, Map.of()))
                    .isEqualTo("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=");
        }

        @Test
        @DisplayName("SHA512 → HEX_LOWER")
        void sha512HexLower() {
            String expected = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39"
                    + "a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";
            assertThat(hashOnce("abc", HashAlgorithm.SHA512, HashEncoding.HEX_LOWER, Map.of()))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("HMAC_SHA1 → HEX_LOWER，已知向量（RFC 2202 test case 1：key=0x0b*20, data=\"Hi There\"）")
        void hmacSha1HexLower() {
            String key = repeatByte((byte) 0x0b, 20);
            String value = hmacOnce("Hi There", key, HashAlgorithm.HMAC_SHA1, HashEncoding.HEX_LOWER);
            assertThat(value).isEqualTo("b617318655057264e28bc0b6fb378c8ef146be00");
        }

        @Test
        @DisplayName("HMAC_SHA256 → HEX_LOWER，已知向量（RFC 4231 test case 1：key=0x0b*20, data=\"Hi There\"）")
        void hmacSha256HexLower() {
            String key = repeatByte((byte) 0x0b, 20);
            String value = hmacOnce("Hi There", key, HashAlgorithm.HMAC_SHA256, HashEncoding.HEX_LOWER);
            assertThat(value).isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
        }

        private String hashOnce(String input, HashAlgorithm algorithm, HashEncoding encoding,
                                Map<String, String> secrets) {
            ComputedField field = new ComputedField("v", input, List.of(step(algorithm, encoding)));
            return evaluator.evaluate(List.of(field), secrets, SESSION).get("v");
        }

        private String hmacOnce(String input, String rawKey, HashAlgorithm algorithm, HashEncoding encoding) {
            ComputedField field = new ComputedField("v", input,
                    List.of(new ComputedStep(algorithm, encoding, "key")));
            return evaluator.evaluate(List.of(field), Map.of("key", rawKey), SESSION).get("v");
        }

        /** {@code 0x0b} 這類任意 byte 沒有對應的可印字元，用 ISO-8859-1 一對一映射成字串——這裡只是拿來當 HMAC 金鑰的位元組來源。 */
        private String repeatByte(byte b, int count) {
            byte[] bytes = new byte[count];
            java.util.Arrays.fill(bytes, b);
            return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    @Nested
    @DisplayName("佔位符 scope")
    class Placeholders {

        @Test
        @DisplayName("{{now.*}} 與 {{uuid}} 直接重用 RequestTemplate 的語法")
        void nowAndUuid() {
            ComputedField field = new ComputedField("v", "{{now.epochSeconds}}-{{uuid}}",
                    List.of(step(HashAlgorithm.SHA256, HashEncoding.HEX_LOWER)));

            // 不拋例外就代表語法被接受；實際值不易斷言（含隨機 UUID），改用長度檢查。
            assertThat(evaluator.evaluate(List.of(field), Map.of(), SESSION).get("v")).hasSize(64);
        }

        @Test
        @DisplayName("引用不存在的 secret → 拒絕")
        void unknownSecret_rejected() {
            ComputedField field = new ComputedField("v", "{{secret.missing}}",
                    List.of(step(HashAlgorithm.MD5, HashEncoding.HEX_LOWER)));

            assertThatThrownBy(() -> evaluator.evaluate(List.of(field), Map.of(), SESSION))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("引用尚未定義的 computed 名稱（縱深防禦，正常情況下存檔時已被 ComputedFieldValidator 擋下）→ 拒絕")
        void undefinedComputedReference_rejected() {
            ComputedField field = new ComputedField("v", "{{computed.doesNotExist}}",
                    List.of(step(HashAlgorithm.MD5, HashEncoding.HEX_LOWER)));

            assertThatThrownBy(() -> evaluator.evaluate(List.of(field), Map.of(), SESSION))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("完全未知的 scope → 拒絕")
        void unknownScope_rejected() {
            ComputedField field = new ComputedField("v", "{{totally.unknown}}",
                    List.of(step(HashAlgorithm.MD5, HashEncoding.HEX_LOWER)));

            assertThatThrownBy(() -> evaluator.evaluate(List.of(field), Map.of(), SESSION))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("HMAC 步驟缺 keySecret → 拒絕")
        void hmacWithoutKeySecret_rejected() {
            ComputedField field = new ComputedField("v", "data",
                    List.of(new ComputedStep(HashAlgorithm.HMAC_SHA256, HashEncoding.HEX_LOWER, null)));

            assertThatThrownBy(() -> evaluator.evaluate(List.of(field), Map.of(), SESSION))
                    .isInstanceOf(ApiException.class);
        }
    }

    private static ComputedStep step(HashAlgorithm algorithm, HashEncoding encoding) {
        return new ComputedStep(algorithm, encoding, null);
    }
}
