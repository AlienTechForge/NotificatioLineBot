package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MonitorBundleParser} 對自訂 JSON 匯入格式的解析。schema 逐字對應
 * {@code Docs/plan/12-API監控易用性升級.md} §2.4。
 */
@DisplayName("MonitorBundleParser")
class MonitorBundleParserTest {

    @Test
    @DisplayName("doc §2.4 的完整範例：只取出 request 子物件")
    void docExample_extractsRequestOnly() {
        String raw = """
                {
                  "version": 1,
                  "name": "範例監控",
                  "request": {
                    "url": "https://example.com/api/orders?since=2026-08-17",
                    "method": "GET",
                    "headers": { "accept": "application/json" },
                    "body": null
                  },
                  "intervalSeconds": 300,
                  "compare": {
                    "mode": "NEW_ITEMS",
                    "itemPointer": "/data/orders",
                    "itemKeyPointer": "/id",
                    "rules": [{ "name": "amount", "pointer": "/amount" }]
                  },
                  "messageTemplate": "新訂單 {{item.amount}} 元"
                }""";

        ImportedRequest result = MonitorBundleParser.parse(raw);

        assertThat(result.url()).isEqualTo("https://example.com/api/orders?since=2026-08-17");
        assertThat(result.method()).isEqualTo("GET");
        assertThat(result.headers()).containsEntry("accept", "application/json");
        assertThat(result.body()).isNull();
    }

    @Test
    @DisplayName("method/headers/body 省略時採用預設值")
    void minimalBundle_appliesDefaults() {
        String raw = "{\"request\":{\"url\":\"https://example.com/api\"}}";

        ImportedRequest result = MonitorBundleParser.parse(raw);

        assertThat(result.url()).isEqualTo("https://example.com/api");
        assertThat(result.method()).isEqualTo("GET");
        assertThat(result.headers()).isEmpty();
        assertThat(result.body()).isNull();
    }

    @Test
    @DisplayName("POST + body：body 保留字串內容")
    void postWithBody() {
        String raw = "{\"request\":{\"url\":\"https://example.com/api\",\"method\":\"post\","
                + "\"body\":\"{\\\"x\\\":1}\"}}";

        ImportedRequest result = MonitorBundleParser.parse(raw);

        assertThat(result.method()).isEqualTo("POST");
        assertThat(result.body()).isEqualTo("{\"x\":1}");
    }

    @Test
    @DisplayName("不是合法 JSON → 拒絕")
    void invalidJson_rejected() {
        assertThatThrownBy(() -> MonitorBundleParser.parse("{not json"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("缺少 request 物件 → 拒絕")
    void missingRequestObject_rejected() {
        assertThatThrownBy(() -> MonitorBundleParser.parse("{\"version\":1}"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("request 缺少 url → 拒絕")
    void missingUrl_rejected() {
        assertThatThrownBy(() -> MonitorBundleParser.parse("{\"request\":{\"method\":\"GET\"}}"))
                .isInstanceOf(ApiException.class);
    }
}
