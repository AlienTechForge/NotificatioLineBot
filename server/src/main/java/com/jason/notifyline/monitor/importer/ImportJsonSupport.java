package com.jason.notifyline.monitor.importer;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link FetchParser} 與 {@link MonitorBundleParser} 共用的「從一段 JSON 物件讀出
 * headers/body/method」邏輯——兩者的 options/{@code request} 子物件形狀完全一樣
 * （{@code headers}/{@code method}/{@code body}），抽出來避免同一段判斷寫兩次。
 */
final class ImportJsonSupport {

    private ImportJsonSupport() {
    }

    static Map<String, String> readHeaders(JsonNode headersNode) {
        if (headersNode == null || !headersNode.isObject()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headersNode.properties().forEach(entry ->
                headers.put(entry.getKey(), entry.getValue().isValueNode()
                        ? entry.getValue().asString() : entry.getValue().toString()));
        return headers;
    }

    /**
     * {@code body} 通常是 fetch() 要求的「已經 JSON.stringify 過的字串」，但也容忍
     * 有人手改成沒有 stringify 的巢狀物件——兩種情況都還原成同一種文字表示，存進
     * {@code request_body} 這個 TEXT 欄位。
     */
    static String readBody(JsonNode bodyNode) {
        if (bodyNode == null || bodyNode.isNull() || bodyNode.isMissingNode()) {
            return null;
        }
        return bodyNode.isValueNode() ? bodyNode.asString() : bodyNode.toString();
    }

    static String readMethodOrDefault(JsonNode methodNode, String fallback) {
        if (methodNode == null || methodNode.isNull() || methodNode.isMissingNode() || !methodNode.isValueNode()) {
            return fallback;
        }
        return methodNode.asString().toUpperCase(Locale.ROOT);
    }
}
