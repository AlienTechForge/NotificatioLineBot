package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FetchParser} 對 Chrome DevTools「Copy as fetch」的解析。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.3、§5（W5 測試矩陣：三種 DevTools 變體、
 * {@code "body": null}、巢狀 JSON body）。
 */
@DisplayName("FetchParser")
class FetchParserTest {

    // ------------------------------------------------------------ 三種 DevTools 變體

    @Test
    @DisplayName("變體一：「Copy as fetch」的一般寫法")
    void plainFetchVariant() {
        String raw = """
                fetch("https://example.com/api/orders", {
                  "headers": {
                    "accept": "application/json",
                    "content-type": "application/json"
                  },
                  "body": "{\\"status\\":\\"open\\"}",
                  "method": "POST"
                });""";

        ImportedRequest result = FetchParser.parse(raw);

        assertThat(result.url()).isEqualTo("https://example.com/api/orders");
        assertThat(result.method()).isEqualTo("POST");
        assertThat(result.headers()).containsEntry("accept", "application/json")
                .containsEntry("content-type", "application/json");
        assertThat(result.body()).isEqualTo("{\"status\":\"open\"}");
    }

    @Test
    @DisplayName("變體二：「Copy as fetch (Node.js)」——headers 為陣列形式時取不到就當空 map")
    void nodeFetchVariant_withIgnoredExtraOptions() {
        String raw = """
                fetch("https://example.com/api/orders", {
                  "headers": {
                    "accept": "application/json"
                  },
                  "referrer": "https://example.com/",
                  "referrerPolicy": "strict-origin-when-cross-origin",
                  "body": null,
                  "method": "GET",
                  "mode": "cors",
                  "credentials": "include"
                });""";

        ImportedRequest result = FetchParser.parse(raw);

        assertThat(result.url()).isEqualTo("https://example.com/api/orders");
        assertThat(result.method()).isEqualTo("GET");
        assertThat(result.headers()).containsEntry("accept", "application/json").hasSize(1);
        assertThat(result.body()).isNull();
    }

    @Test
    @DisplayName("變體三：沒有第二個參數（options 物件）——等同 fetch(url)，GET 沒有 header")
    void urlOnlyVariant_noOptionsObject() {
        ImportedRequest result = FetchParser.parse("fetch(\"https://example.com/api\")");

        assertThat(result.url()).isEqualTo("https://example.com/api");
        assertThat(result.method()).isEqualTo("GET");
        assertThat(result.headers()).isEmpty();
        assertThat(result.body()).isNull();
    }

    // ------------------------------------------------------------ body: null

    @Test
    @DisplayName("\"body\": null → ImportedRequest.body() 為 null")
    void bodyNull() {
        String raw = "fetch(\"https://example.com/api\", {\"headers\":{},\"body\":null,\"method\":\"GET\"});";

        assertThat(FetchParser.parse(raw).body()).isNull();
    }

    @Test
    @DisplayName("完全沒有 body 欄位，等同 null")
    void bodyMissing_treatedAsNull() {
        String raw = "fetch(\"https://example.com/api\", {\"headers\":{},\"method\":\"GET\"});";

        assertThat(FetchParser.parse(raw).body()).isNull();
    }

    // ------------------------------------------------------------ 巢狀 JSON body

    @Test
    @DisplayName("巢狀 JSON body（已 JSON.stringify 過的字串）：正確還原、不被大括號配對誤判")
    void nestedJsonBody_stringified() {
        String raw = """
                fetch("https://example.com/api", {
                  "headers": { "content-type": "application/json" },
                  "body": "{\\"order\\":{\\"id\\":1,\\"items\\":[{\\"sku\\":\\"A\\"},{\\"sku\\":\\"B\\"}]}}",
                  "method": "POST"
                });""";

        ImportedRequest result = FetchParser.parse(raw);

        assertThat(result.body()).isEqualTo("{\"order\":{\"id\":1,\"items\":[{\"sku\":\"A\"},{\"sku\":\"B\"}]}}");
        // 帶巢狀大括號的 body 字串不能讓「找 options 物件結尾大括號」的掃描提早結束。
        assertThat(result.headers()).containsEntry("content-type", "application/json");
    }

    @Test
    @DisplayName("巢狀 JSON body（未 stringify 的原生物件）：還原成緊湊 JSON 文字")
    void nestedJsonBody_rawObject() {
        String raw = """
                fetch("https://example.com/api", {
                  "body": {"a": {"b": 1}},
                  "method": "POST"
                });""";

        assertThat(FetchParser.parse(raw).body()).isEqualTo("{\"a\":{\"b\":1}}");
    }

    // ------------------------------------------------------------ 寬鬆模式：尾逗號

    @Test
    @DisplayName("options 物件容忍尾逗號")
    void trailingCommaTolerated() {
        String raw = "fetch(\"https://example.com/api\", {\"headers\":{\"a\":\"1\",},\"method\":\"GET\",});";

        ImportedRequest result = FetchParser.parse(raw);

        assertThat(result.headers()).containsEntry("a", "1");
        assertThat(result.method()).isEqualTo("GET");
    }

    // ------------------------------------------------------------ 錯誤情況

    @Test
    @DisplayName("沒有字串字面量當 URL → 拒絕")
    void missingUrlLiteral_rejected() {
        assertThatThrownBy(() -> FetchParser.parse("fetch()")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("options 物件不是合法 JSON → 拒絕，且例外訊息不回顯片段內容")
    void invalidOptionsJson_rejected() {
        String raw = "fetch(\"https://example.com/api\", {not valid json at all!!!});";

        assertThatThrownBy(() -> FetchParser.parse(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("not valid json at all"));
    }
}
