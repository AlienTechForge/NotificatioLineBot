package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 解析自訂 JSON 匯入格式（給使用者拿去餵 AI 用的 schema）。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.4，schema 逐字對應。
 *
 * <p>W5 只需要 {@code request} 這個子物件——{@code name}／{@code intervalSeconds}／
 * {@code compare}／{@code messageTemplate} 這些監控層級欄位屬於「填表」而不是
 * 「解析請求」，{@code POST /monitors/import} 的回應形狀就是 {@link ImportedRequest}
 * （url/method/headers/body 四個欄位），跟 {@link CurlParser}、{@link FetchParser}
 * 完全一致。之後的波次要用到其餘欄位時，直接對同一份 raw JSON 再解析一次即可，
 * 不需要這裡先把它們留住、多背一份「這份資料到底屬於哪個波次」的包袱。
 */
final class MonitorBundleParser {

    private MonitorBundleParser() {
    }

    /** 這個格式是使用者／AI 手key 出來的固定 schema，不需要 {@link FetchParser} 那種寬鬆模式。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    static ImportedRequest parse(String raw) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "JSON bundle is not valid JSON.");
        }
        if (!root.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "JSON bundle must be a JSON object.");
        }

        JsonNode request = root.get("request");
        if (request == null || !request.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "JSON bundle is missing the \"request\" object.");
        }

        JsonNode urlNode = request.get("url");
        if (urlNode == null || !urlNode.isValueNode() || urlNode.asString().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "JSON bundle is missing \"request.url\".");
        }

        String method = ImportJsonSupport.readMethodOrDefault(request.get("method"), "GET");
        Map<String, String> headers = ImportJsonSupport.readHeaders(request.get("headers"));
        String body = ImportJsonSupport.readBody(request.get("body"));

        return new ImportedRequest(urlNode.asString(), method, headers, body);
    }
}
