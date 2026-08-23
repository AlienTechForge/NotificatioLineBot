package com.jason.notifyline.monitor.parse;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.Ids;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChangeDetector")
class ChangeDetectorTest {

    private final ChangeDetector detector = new ChangeDetector(new JsonExtractor(new ObjectMapper()));

    // =============================================================== WHOLE_BODY

    @Nested
    @DisplayName("WHOLE_BODY 模式")
    class WholeBody {

        private static final String BODY_V1 = "{\"status\":\"ok\",\"count\":1}";
        private static final String BODY_V2 = "{\"status\":\"fail\",\"count\":2}";

        @Test
        @DisplayName("首次執行（last_fingerprint 為 null）：回 Unchanged，記錄基準指紋")
        void firstRun_recordsBaselineWithoutNotifying() {
            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.WHOLE_BODY, BODY_V1, List.of(), null, null);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
            ChangeResult.Unchanged unchanged = (ChangeResult.Unchanged) result;
            assertThat(unchanged.fingerprint()).isEqualTo(Ids.sha256(BODY_V1));
        }

        @Test
        @DisplayName("body 沒變：回 Unchanged")
        void unchanged_whenBodyIdentical() {
            byte[] previous = Ids.sha256(BODY_V1);

            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.WHOLE_BODY, BODY_V1, List.of(), previous, null);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
        }

        @Test
        @DisplayName("body 變了：回 Changed，帶新指紋")
        void changed_whenBodyDiffers() {
            byte[] previous = Ids.sha256(BODY_V1);

            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.WHOLE_BODY, BODY_V2, List.of(), previous, null);

            assertThat(result).isInstanceOf(ChangeResult.Changed.class);
            ChangeResult.Changed changed = (ChangeResult.Changed) result;
            assertThat(changed.fingerprint()).isEqualTo(Ids.sha256(BODY_V2));
        }

        @Test
        @DisplayName("即使有設定 extract_rules，WHOLE_BODY 的指紋仍然是整個 body 的雜湊")
        void fingerprintIgnoresExtractRulesEvenWhenPresent() {
            byte[] previous = Ids.sha256(BODY_V1);
            List<ExtractRule> rules = List.of(new ExtractRule("status", "/status"));

            // body 的 "count" 變了但 "status" 沒變 —— WHOLE_BODY 仍要判定為變更，
            // 因為比對基準是整個 body，不是取出的欄位。
            String bodyOnlyCountChanged = "{\"status\":\"ok\",\"count\":999}";
            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.WHOLE_BODY, bodyOnlyCountChanged, rules, previous, null);

            assertThat(result).isInstanceOf(ChangeResult.Changed.class);
        }

        @Test
        @DisplayName("有 extract_rules 時，WHOLE_BODY 模式仍會取值供 {{value.NAME}} 使用")
        void stillExtractsValuesForTemplateEvenThoughFingerprintIsWholeBody() {
            List<ExtractRule> rules = List.of(new ExtractRule("status", "/status"));

            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.WHOLE_BODY, BODY_V1, rules, null, null);

            assertThat(result.currentValues()).containsEntry("status", "ok");
        }

        @Test
        @DisplayName("沒有 extract_rules 時，WHOLE_BODY 不必解析 JSON —— 即使 body 不是合法 JSON 也能正常運作")
        void doesNotParseBodyWhenNoExtractRules() {
            String notJson = "plain text response, not JSON at all";

            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.WHOLE_BODY, notJson, List.of(), null, null);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
            assertThat(((ChangeResult.Unchanged) result).fingerprint()).isEqualTo(Ids.sha256(notJson));
        }
    }

    // =============================================================== EXTRACTED

    @Nested
    @DisplayName("EXTRACTED 模式")
    class Extracted {

        private static final String BODY_V1 = "{\"status\":\"ok\",\"count\":1}";
        private static final String BODY_V2 = "{\"status\":\"fail\",\"count\":1}";
        private static final List<ExtractRule> RULES = List.of(
                new ExtractRule("status", "/status"),
                new ExtractRule("count", "/count"));
        private static final List<ExtractRule> RULES_REORDERED = List.of(
                new ExtractRule("count", "/count"),
                new ExtractRule("status", "/status"));

        @Test
        @DisplayName("首次執行：回 Unchanged，記錄基準指紋與取出值")
        void firstRun_recordsBaselineWithoutNotifying() {
            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, BODY_V1, RULES, null, null);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
            assertThat(result.currentValues()).containsEntry("status", "ok").containsEntry("count", "1");
        }

        @Test
        @DisplayName("取出的值沒變：回 Unchanged（即使整個 body 字面上不同也一樣，因為只比對取出的值）")
        void unchanged_whenExtractedValuesIdentical() {
            ChangeResult baseline = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, BODY_V1, RULES, null, null);
            byte[] previous = baseline.fingerprint();

            // 多一個沒被規則引用的欄位，body 字面不同，但取出的值一樣。
            String bodyWithExtraField = "{\"status\":\"ok\",\"count\":1,\"noise\":\"whatever\"}";
            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, bodyWithExtraField, RULES, previous, null);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
        }

        @Test
        @DisplayName("取出的值變了：回 Changed，帶上次的值供 {{old.NAME}} 使用")
        void changed_whenExtractedValuesDiffer() {
            ChangeResult baseline = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, BODY_V1, RULES, null, null);
            byte[] previous = baseline.fingerprint();
            Map<String, String> previousValues = baseline.currentValues();

            ChangeResult result = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, BODY_V2, RULES, previous, previousValues);

            assertThat(result).isInstanceOf(ChangeResult.Changed.class);
            ChangeResult.Changed changed = (ChangeResult.Changed) result;
            assertThat(changed.currentValues()).containsEntry("status", "fail");
            assertThat(changed.previousValues()).containsEntry("status", "ok");
        }

        @Test
        @DisplayName("欄位順序不影響指紋 —— extract_rules 重新排序後，同一組值算出同一個指紋")
        void fingerprintIsStableUnderFieldReordering() {
            ChangeResult original = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, BODY_V1, RULES, null, null);
            ChangeResult reordered = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, BODY_V1, RULES_REORDERED, null, null);

            assertThat(reordered.fingerprint()).isEqualTo(original.fingerprint());
        }

        @Test
        @DisplayName("null 取出值與空字串取出值的指紋不同 —— canonical 化沒有把兩者混為一談")
        void nullValueAndEmptyStringProduceDifferentFingerprints() {
            List<ExtractRule> rule = List.of(new ExtractRule("x", "/x"));

            ChangeResult withNull = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, "{\"x\":null}", rule, null, null);
            ChangeResult withEmpty = detector.detectByFingerprint(
                    CompareMode.EXTRACTED, "{\"x\":\"\"}", rule, null, null);

            assertThat(withNull.fingerprint()).isNotEqualTo(withEmpty.fingerprint());
        }

        @Test
        @DisplayName("mode 傳 NEW_ITEMS 是呼叫端用錯方法，丟 IllegalArgumentException")
        void rejectsNewItemsMode() {
            assertThatThrownBy(() -> detector.detectByFingerprint(
                    CompareMode.NEW_ITEMS, BODY_V1, RULES, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =============================================================== NEW_ITEMS

    @Nested
    @DisplayName("NEW_ITEMS 模式")
    class NewItems {

        private static final String ITEM_POINTER = "/items";
        private static final String ITEM_KEY_POINTER = "/id";

        private static String bodyWithIds(String... ids) {
            StringBuilder sb = new StringBuilder("{\"items\":[");
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(ids[i]).append("\"}");
            }
            return sb.append("]}").toString();
        }

        @Test
        @DisplayName("首次執行：不通知，但回傳的 Unchanged 帶著全部項目供呼叫端寫入 seen_item")
        void firstRun_marksAllSeenWithoutNotifying() {
            String body = bodyWithIds("a", "b", "c");

            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), Set.of(), true);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
            ChangeResult.Unchanged unchanged = (ChangeResult.Unchanged) result;
            assertThat(unchanged.newItems()).extracting(ChangeResult.NewItem::itemKey)
                    .containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("非首次執行、沒有新項目：回 Unchanged，帶空清單")
        void noNewItems_isUnchanged() {
            String body = bodyWithIds("a", "b");
            Set<String> seen = Set.of("a", "b");

            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), seen, false);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
            assertThat(((ChangeResult.Unchanged) result).newItems()).isEmpty();
        }

        @Test
        @DisplayName("非首次執行、出現新項目：回 Changed，只帶新項目")
        void newItemsAppear_isChanged() {
            String body = bodyWithIds("a", "b", "c");
            Set<String> seen = Set.of("a");

            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), seen, false);

            assertThat(result).isInstanceOf(ChangeResult.Changed.class);
            assertThat(((ChangeResult.Changed) result).newItems())
                    .extracting(ChangeResult.NewItem::itemKey)
                    .containsExactlyInAnyOrder("b", "c");
        }

        @Test
        @DisplayName("firstRun 不能用 seenKeys.isEmpty() 代替：非首次執行但 seenKeys 剛好是空的，仍要通知")
        void emptySeenKeysOnNonFirstRun_stillNotifies() {
            String body = bodyWithIds("a");

            // seenKeys 是空的（過去每次都回空陣列），但這不是第一次執行。
            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), Set.of(), false);

            assertThat(result).isInstanceOf(ChangeResult.Changed.class);
        }

        @Test
        @DisplayName("item_pointer 沒有指向陣列：丟 ApiException VALIDATION_ERROR，不是回空結果")
        void itemPointerNotAnArray_throws() {
            String body = "{\"items\":\"not-an-array\"}";

            assertThatThrownBy(() -> detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), Set.of(), true))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("item_pointer 指向空陣列：不是失敗，正常回傳沒有新項目")
        void itemPointerEmptyArray_isNotAFailure() {
            String body = "{\"items\":[]}";

            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), Set.of(), true);

            assertThat(result).isInstanceOf(ChangeResult.Unchanged.class);
            assertThat(((ChangeResult.Unchanged) result).newItems()).isEmpty();
        }

        @Test
        @DisplayName("元素缺少可用的鍵：略過該元素，不影響其他元素、不丟例外")
        void elementWithoutKey_isSkipped() {
            String body = "{\"items\":[{\"id\":\"a\"},{\"noId\":true},{\"id\":\"b\"}]}";

            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), Set.of(), true);

            assertThat(((ChangeResult.Unchanged) result).newItems())
                    .extracting(ChangeResult.NewItem::itemKey)
                    .containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("item_field_rules 供逐項渲染 {{item.NAME}} 使用")
        void itemFieldRules_populateNewItemFields() {
            String body = "{\"items\":[{\"id\":\"a\",\"label\":\"Alpha\"}]}";
            List<ExtractRule> fieldRules = List.of(
                    new ExtractRule("id", "/id"), new ExtractRule("label", "/label"));

            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, fieldRules, Set.of(), false);

            ChangeResult.NewItem item = ((ChangeResult.Changed) result).newItems().getFirst();
            assertThat(item.fields()).containsEntry("id", "a").containsEntry("label", "Alpha");
        }

        @Test
        @DisplayName("item_key 未超過 200 字元：原樣使用，不雜湊")
        void shortItemKey_usedVerbatim() {
            String body = bodyWithIds("short-key");

            ChangeResult result = detector.detectNewItems(
                    body, ITEM_POINTER, ITEM_KEY_POINTER, List.of(), Set.of(), true);

            assertThat(((ChangeResult.Unchanged) result).newItems().getFirst().itemKey())
                    .isEqualTo("short-key");
        }

        @Test
        @DisplayName("item_key 超過 200 字元：取 SHA-256 雜湊而非截斷 —— 共同前綴的兩個不同鍵不會撞成同一個")
        void longItemKey_hashedNotTruncated() {
            String prefix = "p".repeat(200);
            String key1 = prefix + "-tail-one-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            String key2 = prefix + "-tail-two-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            assertThat(key1.length()).isGreaterThan(200);
            assertThat(key1.substring(0, 200)).isEqualTo(key2.substring(0, 200));

            // 第一輪：只看到 key1，記錄基準。
            ChangeResult round1 = detector.detectNewItems(
                    bodyWithIds(key1), ITEM_POINTER, ITEM_KEY_POINTER, List.of(), Set.of(), true);
            String hash1 = ((ChangeResult.Unchanged) round1).newItems().getFirst().itemKey();

            // 雜湊過的鍵是 SHA-256 十六進位字串：64 個字元。
            assertThat(hash1).hasSize(64).matches("[0-9a-f]{64}");

            // 第二輪：改看到 key2（前 200 字元跟 key1 相同，之後不同）。
            // 若用截斷而非雜湊，key2 會被誤判成跟 key1 是同一個 key，變成「已看過」而不通知。
            Set<String> seenAfterRound1 = new HashSet<>();
            seenAfterRound1.add(hash1);
            ChangeResult round2 = detector.detectNewItems(
                    bodyWithIds(key2), ITEM_POINTER, ITEM_KEY_POINTER, List.of(), seenAfterRound1, false);

            assertThat(round2).isInstanceOf(ChangeResult.Changed.class);
            assertThat(((ChangeResult.Changed) round2).newItems()).hasSize(1);
        }
    }
}
