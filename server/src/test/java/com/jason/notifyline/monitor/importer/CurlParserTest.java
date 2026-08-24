package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CurlParser} 對 Chrome DevTools「Copy as cURL」bash／cmd 兩種輸出的解析。
 * 見 {@code Docs/plan/12-API監控易用性升級.md} §2.2、§5（W5 測試矩陣）。
 */
@DisplayName("CurlParser")
class CurlParserTest {

    // ------------------------------------------------------------ bash 形式

    @Test
    @DisplayName("bash：Chrome 典型輸出——URL、多個 -H、--data-raw、--compressed 被忽略")
    void bashForm_typicalChromeOutput() {
        String raw = "curl 'https://example.com/api/orders' \\\n"
                + "  -H 'accept: application/json' \\\n"
                + "  -H 'content-type: application/json' \\\n"
                + "  -H 'authorization: Bearer secret-token-123' \\\n"
                + "  --data-raw '{\"status\":\"open\"}' \\\n"
                + "  --compressed";

        ImportedRequest result = CurlParser.parse(raw);

        assertThat(result.url()).isEqualTo("https://example.com/api/orders");
        assertThat(result.method()).isEqualTo("POST"); // 有 body、沒明講 -X，推斷為 POST
        assertThat(result.headers()).containsEntry("accept", "application/json")
                .containsEntry("content-type", "application/json")
                .containsEntry("authorization", "Bearer secret-token-123");
        assertThat(result.body()).isEqualTo("{\"status\":\"open\"}");
    }

    @Test
    @DisplayName("bash：GET 沒有 body，方法預設 GET")
    void bashForm_getWithoutBody() {
        ImportedRequest result = CurlParser.parse("curl 'https://example.com/api'");

        assertThat(result.url()).isEqualTo("https://example.com/api");
        assertThat(result.method()).isEqualTo("GET");
        assertThat(result.body()).isNull();
        assertThat(result.headers()).isEmpty();
    }

    // ------------------------------------------------------------ cmd 形式

    @Test
    @DisplayName("cmd：雙引號 + ^ 續行 + \\\" 跳脫，結果跟 bash 形式一致")
    void cmdForm_typicalChromeOutput() {
        String raw = "curl \"https://example.com/api/orders\" ^\r\n"
                + "  -H \"accept: application/json\" ^\r\n"
                + "  -H \"authorization: Bearer secret-token-123\" ^\r\n"
                + "  --data-raw \"{\\\"status\\\":\\\"open\\\"}\" ^\r\n"
                + "  --compressed";

        ImportedRequest result = CurlParser.parse(raw);

        assertThat(result.url()).isEqualTo("https://example.com/api/orders");
        assertThat(result.method()).isEqualTo("POST");
        assertThat(result.headers()).containsEntry("accept", "application/json")
                .containsEntry("authorization", "Bearer secret-token-123");
        assertThat(result.body()).isEqualTo("{\"status\":\"open\"}");
    }

    // ------------------------------------------------------------ 各旗標

    @Test
    @DisplayName("多個 -H：全部進到 headers map")
    void multipleHeaderFlags() {
        String raw = "curl 'https://example.com' -H 'a: 1' -H 'b: 2' -H 'c: 3'";
        ImportedRequest result = CurlParser.parse(raw);

        assertThat(result.headers()).containsEntry("a", "1").containsEntry("b", "2").containsEntry("c", "3");
    }

    @Test
    @DisplayName("--data-raw：body 正確帶入，且方法推斷成 POST")
    void dataRawFlag() {
        ImportedRequest result = CurlParser.parse("curl 'https://example.com' --data-raw 'hello world'");

        assertThat(result.body()).isEqualTo("hello world");
        assertThat(result.method()).isEqualTo("POST");
    }

    @Test
    @DisplayName("-X 明確指定方法時，優先於「有 body 就猜 POST」的預設")
    void explicitMethodOverridesInference() {
        ImportedRequest result = CurlParser.parse(
                "curl 'https://example.com' -X PUT --data-raw '{}'");

        assertThat(result.method()).isEqualTo("PUT");
    }

    @Test
    @DisplayName("-b/--cookie：當成一般 header 存進 cookie 鍵（W5 尚未做 site_session jar）")
    void cookieFlag_keptAsOrdinaryHeader() {
        ImportedRequest result = CurlParser.parse(
                "curl 'https://example.com' -b 'session=abc123; csrf=def456'");

        assertThat(result.headers()).containsEntry("cookie", "session=abc123; csrf=def456");
    }

    @Test
    @DisplayName("-A/--user-agent")
    void userAgentFlag() {
        ImportedRequest result = CurlParser.parse("curl 'https://example.com' -A 'MyBot/1.0'");

        assertThat(result.headers()).containsEntry("user-agent", "MyBot/1.0");
    }

    @Test
    @DisplayName("多個 --data 串接：用 & 接起來（跟真的 curl 行為一致）")
    void multipleDataFlags_joinedWithAmpersand() {
        ImportedRequest result = CurlParser.parse("curl 'https://example.com' -d 'a=1' -d 'b=2'");

        assertThat(result.body()).isEqualTo("a=1&b=2");
    }

    @Test
    @DisplayName("不認識的旗標：忽略旗標本身，不吃掉下一個 token（URL 仍正確辨識）")
    void unknownFlag_ignoredWithoutConsumingNextToken() {
        ImportedRequest result = CurlParser.parse("curl -s -i 'https://example.com/api'");

        assertThat(result.url()).isEqualTo("https://example.com/api");
    }

    // ------------------------------------------------------------ -L 拒絕

    @Test
    @DisplayName("-L 被拒絕，附解釋性錯誤，不是靜靜忽略")
    void locationFlag_rejected() {
        assertThatThrownBy(() -> CurlParser.parse("curl -L 'https://example.com/api'"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("redirect");
    }

    @Test
    @DisplayName("--location 被拒絕")
    void longLocationFlag_rejected() {
        assertThatThrownBy(() -> CurlParser.parse("curl --location 'https://example.com/api'"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("redirect");
    }

    // ------------------------------------------------------------ 錯誤情況

    @Test
    @DisplayName("沒有 URL → 拒絕")
    void missingUrl_rejected() {
        assertThatThrownBy(() -> CurlParser.parse("curl -H 'accept: application/json'"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("-H 值沒有冒號 → 拒絕")
    void headerWithoutColon_rejected() {
        assertThatThrownBy(() -> CurlParser.parse("curl 'https://example.com' -H 'not-a-header'"))
                .isInstanceOf(ApiException.class);
    }
}
