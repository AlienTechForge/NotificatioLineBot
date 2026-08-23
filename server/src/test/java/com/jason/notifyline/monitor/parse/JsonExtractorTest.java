package com.jason.notifyline.monitor.parse;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonExtractor")
class JsonExtractorTest {

    private static final String BODY = """
            {
              "status": "ok",
              "count": 42,
              "flag": true,
              "nil": null,
              "data": {"nested": {"deep": "value"}},
              "items": [{"id": 1}, {"id": 2}]
            }
            """;

    private final JsonExtractor extractor = new JsonExtractor(new ObjectMapper());

    private JsonNode root() {
        return extractor.parse(BODY);
    }

    // ---------------------------------------------------------------- 純量

    @Test
    @DisplayName("純量字串：直接取值")
    void scalarString() {
        assertThat(extractor.extract(root(), "/status")).isEqualTo("ok");
    }

    @Test
    @DisplayName("純量數字：asString() 轉字串")
    void scalarNumber() {
        assertThat(extractor.extract(root(), "/count")).isEqualTo("42");
    }

    @Test
    @DisplayName("純量布林：asString() 轉字串")
    void scalarBoolean() {
        assertThat(extractor.extract(root(), "/flag")).isEqualTo("true");
    }

    // ---------------------------------------------------------------- 容器

    @Test
    @DisplayName("物件：序列化成緊湊 JSON 字串")
    void objectNode() {
        assertThat(extractor.extract(root(), "/data")).isEqualTo("{\"nested\":{\"deep\":\"value\"}}");
    }

    @Test
    @DisplayName("陣列：序列化成緊湊 JSON 字串")
    void arrayNode() {
        assertThat(extractor.extract(root(), "/items")).isEqualTo("[{\"id\":1},{\"id\":2}]");
    }

    // ---------------------------------------------------------------- 取不到 / null

    @Test
    @DisplayName("路徑不存在：回傳 null")
    void missingPath() {
        assertThat(extractor.extract(root(), "/nope")).isNull();
    }

    @Test
    @DisplayName("路徑存在但值是 JSON null：回傳 null")
    void explicitJsonNull() {
        assertThat(extractor.extract(root(), "/nil")).isNull();
    }

    @Test
    @DisplayName("父層路徑不存在（穿過不存在的中繼節點）：回傳 null，不丟例外")
    void missingIntermediateSegment() {
        assertThat(extractor.extract(root(), "/nope/deeper")).isNull();
    }

    // ---------------------------------------------------------------- 深層巢狀

    @Test
    @DisplayName("深層巢狀：多層 pointer 正確取值")
    void deeplyNested() {
        assertThat(extractor.extract(root(), "/data/nested/deep")).isEqualTo("value");
    }

    @Test
    @DisplayName("陣列元素巢狀：/items/0/id")
    void arrayElementNested() {
        assertThat(extractor.extract(root(), "/items/0/id")).isEqualTo("1");
        assertThat(extractor.extract(root(), "/items/1/id")).isEqualTo("2");
    }

    @Test
    @DisplayName("陣列越界：回傳 null，不丟例外")
    void arrayIndexOutOfBounds() {
        assertThat(extractor.extract(root(), "/items/9/id")).isNull();
    }

    // ---------------------------------------------------------------- 畸形 pointer

    @Test
    @DisplayName("畸形 pointer（沒有前導 /）：丟 ApiException VALIDATION_ERROR")
    void malformedPointer_missingLeadingSlash() {
        assertThatThrownBy(() -> extractor.extract(root(), "status"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("空字串 pointer 是合法的（指向整個文件），不算畸形")
    void emptyPointer_isRootDocument() {
        // 不比對整包序列化字串的確切格式（緊湊 JSON 的空白規則是實作細節），
        // 只驗證「空字串 pointer 不算畸形、不會丟例外，且真的取到了東西」。
        String whole = extractor.extract(root(), "");

        assertThat(whole).isNotNull().contains("\"status\":\"ok\"");
    }

    // ---------------------------------------------------------------- 畸形 JSON

    @Test
    @DisplayName("body 不是合法 JSON：parse() 丟 ApiException VALIDATION_ERROR")
    void malformedJson() {
        assertThatThrownBy(() -> extractor.parse("{not valid json"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ---------------------------------------------------------------- extractAll

    @Test
    @DisplayName("extractAll：依規則清單順序取值，保留 null")
    void extractAll_preservesOrderAndNulls() {
        Map<String, String> values = extractor.extractAll(root(), List.of(
                new ExtractRule("s", "/status"),
                new ExtractRule("n", "/nil"),
                new ExtractRule("missing", "/nope")));

        assertThat(values).containsEntry("s", "ok");
        assertThat(values.get("n")).isNull();
        assertThat(values.get("missing")).isNull();
        assertThat(values.keySet()).containsExactly("s", "n", "missing");
    }

    @Test
    @DisplayName("extractAll：空規則清單回傳空 map")
    void extractAll_emptyRules() {
        assertThat(extractor.extractAll(root(), List.of())).isEmpty();
    }
}
