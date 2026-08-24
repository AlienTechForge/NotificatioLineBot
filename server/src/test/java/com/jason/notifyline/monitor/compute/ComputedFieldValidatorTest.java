package com.jason.notifyline.monitor.compute;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.domain.ComputedField;
import com.jason.notifyline.monitor.domain.ComputedStep;
import com.jason.notifyline.monitor.domain.HashAlgorithm;
import com.jason.notifyline.monitor.domain.HashEncoding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ComputedFieldValidator} 的存檔時驗證規則。見
 * {@code Docs/plan/13-監控計算欄位設計.md} §4、§5：「前向引用／自我引用...存檔時就要
 * 偵測並回 400，不能等到輪詢才發現」。
 */
@DisplayName("ComputedFieldValidator")
class ComputedFieldValidatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T03:00:00Z"), ZoneOffset.UTC);

    private final ComputedFieldValidator validator = new ComputedFieldValidator(CLOCK);

    @Test
    @DisplayName("null／空清單：合法（沒有計算欄位）")
    void emptyOrNull_valid() {
        assertThatCode(() -> validator.validate(null, Set.of())).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(List.of(), Set.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("合法的單一欄位：MD5 → HEX_UPPER，引用已知 secret")
    void validSingleField() {
        ComputedField field = new ComputedField("sign", "{{secret.appsecret}}{{now.epochSeconds}}",
                List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

        assertThatCode(() -> validator.validate(List.of(field), Set.of("appsecret")))
                .doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("前向引用／自我引用")
    class ForwardAndSelfReference {

        @Test
        @DisplayName("自我引用 → 400")
        void selfReference_rejected() {
            ComputedField field = new ComputedField("sign", "{{computed.sign}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }

        @Test
        @DisplayName("前向引用（引用陣列中更後面的欄位）→ 400")
        void forwardReference_rejected() {
            ComputedField first = new ComputedField("a", "{{computed.b}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));
            ComputedField second = new ComputedField("b", "x",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(first, second), Set.of()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("引用更早的欄位 → 合法")
        void referenceToEarlierField_valid() {
            ComputedField first = new ComputedField("a", "x",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));
            ComputedField second = new ComputedField("b", "{{computed.a}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatCode(() -> validator.validate(List.of(first, second), Set.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("引用完全不存在的欄位名稱 → 400")
        void referenceToUnknownField_rejected() {
            ComputedField field = new ComputedField("a", "{{computed.nope}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("名稱格式與重複")
    class NameValidation {

        @Test
        @DisplayName("名稱不合法 → 400")
        void invalidName_rejected() {
            ComputedField field = new ComputedField("bad name!", "x",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("重複名稱 → 400")
        void duplicateName_rejected() {
            ComputedField a = new ComputedField("sign", "x",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));
            ComputedField b = new ComputedField("sign", "y",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(a, b), Set.of()))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("steps 驗證")
    class StepsValidation {

        @Test
        @DisplayName("空 steps → 400")
        void emptySteps_rejected() {
            ComputedField field = new ComputedField("sign", "x", List.of());

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("HMAC 缺 keySecret → 400")
        void hmacWithoutKeySecret_rejected() {
            ComputedField field = new ComputedField("sign", "x",
                    List.of(new ComputedStep(HashAlgorithm.HMAC_SHA256, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("HMAC 的 keySecret 不是已知 secret → 400")
        void hmacWithUnknownKeySecret_rejected() {
            ComputedField field = new ComputedField("sign", "x",
                    List.of(new ComputedStep(HashAlgorithm.HMAC_SHA256, HashEncoding.HEX_UPPER, "nope")));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of("other")))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("HMAC 的 keySecret 是已知 secret → 合法")
        void hmacWithKnownKeySecret_valid() {
            ComputedField field = new ComputedField("sign", "x",
                    List.of(new ComputedStep(HashAlgorithm.HMAC_SHA256, HashEncoding.HEX_UPPER, "deviceid")));

            assertThatCode(() -> validator.validate(List.of(field), Set.of("deviceid")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非 HMAC 演算法卻帶 keySecret → 400")
        void nonHmacWithKeySecret_rejected() {
            ComputedField field = new ComputedField("sign", "x",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, "appsecret")));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of("appsecret")))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Nested
    @DisplayName("input 佔位符")
    class InputPlaceholders {

        @Test
        @DisplayName("input 空白 → 400")
        void blankInput_rejected() {
            ComputedField field = new ComputedField("sign", "",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("引用未知 secret → 400")
        void unknownSecret_rejected() {
            ComputedField field = new ComputedField("sign", "{{secret.notDeclared}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of("appsecret")))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("畸形 now.format pattern → 400（重用 RequestTemplate 的語法檢查）")
        void malformedNowFormatPattern_rejected() {
            ComputedField field = new ComputedField("sign", "{{now.format:yyyy'MM}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("完全未知的 scope → 400")
        void unknownScope_rejected() {
            ComputedField field = new ComputedField("sign", "{{totally.unknown}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatThrownBy(() -> validator.validate(List.of(field), Set.of()))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("{{uuid}} 與 {{now.epochSeconds}} 都是合法 scope")
        void nowAndUuid_valid() {
            ComputedField field = new ComputedField("sign", "{{now.epochSeconds}}-{{uuid}}",
                    List.of(new ComputedStep(HashAlgorithm.MD5, HashEncoding.HEX_UPPER, null)));

            assertThatCode(() -> validator.validate(List.of(field), Set.of())).doesNotThrowAnyException();
        }
    }
}
