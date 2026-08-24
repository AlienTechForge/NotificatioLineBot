package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestImporter}：自動辨識格式並分派給對應的解析器。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.1、§2.6。
 */
@DisplayName("RequestImporter")
class RequestImporterTest {

    private final RequestImporter importer = new RequestImporter();

    @Test
    @DisplayName("以 { 開頭 → 自訂 JSON 格式")
    void detectsJsonBundle() {
        String raw = "{\"request\":{\"url\":\"https://example.com/api\"}}";

        assertThat(importer.importRequest(raw).url()).isEqualTo("https://example.com/api");
    }

    @Test
    @DisplayName("以 fetch( 開頭（大小寫不拘）→ fetch 格式")
    void detectsFetch() {
        assertThat(importer.importRequest("fetch(\"https://example.com/api\")").url())
                .isEqualTo("https://example.com/api");
        assertThat(importer.importRequest("FETCH(\"https://example.com/api\")").url())
                .isEqualTo("https://example.com/api");
    }

    @Test
    @DisplayName("其餘（curl ...）→ cURL 格式")
    void detectsCurl() {
        assertThat(importer.importRequest("curl 'https://example.com/api'").url())
                .isEqualTo("https://example.com/api");
    }

    @Test
    @DisplayName("前後空白不影響偵測")
    void trimsWhitespaceBeforeDetecting() {
        assertThat(importer.importRequest("  \n  curl 'https://example.com/api'  \n").url())
                .isEqualTo("https://example.com/api");
    }

    @Test
    @DisplayName("空白輸入 → 拒絕")
    void blankInput_rejected() {
        assertThatThrownBy(() -> importer.importRequest("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> importer.importRequest(null)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("不是 { 開頭也不是 fetch( 開頭 → 落到 cURL 解析器；沒有 URL 時拋錯，"
            + "訊息不回顯已解析出的機敏內容")
    void nonCurlNonFetchNonJson_fallsBackToCurlParser_andNeverEchoesParsedContent() {
        // 只有旗標、沒有任何非旗標 token 當 URL——CurlParser 會拋「缺少 URL」，
        // 但這段輸入本身仍含一個看起來像機密的 header 值，例外訊息絕不可以夾帶它。
        String raw = "-H 'x-api-key: SECRET_TOKEN=abc123'";

        assertThatThrownBy(() -> importer.importRequest(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET_TOKEN"));
    }
}
