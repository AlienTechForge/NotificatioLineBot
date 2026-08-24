package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 解析 Chrome DevTools「Copy as fetch」（一般／Node.js 兩種變體語法骨架相同）。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.3。
 *
 * <p><strong>純文字掃描，絕不 eval</strong>：只找出第一個字串字面量當 URL，取其後
 * 第一個 {@code {} 到對應 {@code }} 的區段丟給 Jackson 解析——{@code raw} 從頭到尾
 * 不會被當成 JavaScript 執行，不會進 {@code ScriptEngine} 或任何表達式求值器。
 * {@code fetch(...)} 呼叫本身可能有的 {@code await}、分號、變數賦值一概不理會，
 * 也不需要理會——只要抓得到「第一個字串」與「其後第一個大括號區塊」就夠。
 */
final class FetchParser {

    private FetchParser() {
    }

    /** 寬鬆模式：容忍尾逗號（doc §2.3），不需要其餘的 JSON5 寬鬆規則。 */
    private static final ObjectMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    static ImportedRequest parse(String raw) {
        int urlStart = indexOfFirstQuote(raw, 0);
        if (urlStart < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "fetch(...) call is missing a URL string literal.");
        }
        StringLiteral url = readStringLiteral(raw, urlStart);

        int braceStart = raw.indexOf('{', url.endExclusive());
        if (braceStart < 0) {
            // 沒有第二個參數（options 物件）：等同 fetch(url)——GET、沒有 header、沒有 body。
            return new ImportedRequest(url.value(), "GET", Map.of(), null);
        }
        int braceEnd = matchBrace(raw, braceStart);
        if (braceEnd < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "fetch(...) options object has no matching '}'.");
        }

        JsonNode options;
        try {
            options = LENIENT_MAPPER.readTree(raw.substring(braceStart, braceEnd + 1));
        } catch (JacksonException e) {
            // Jackson 的例外訊息可能夾帶片段輸入，不可原樣外流（規則同 JsonExtractor）。
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "fetch(...) options object is not valid JSON.");
        }

        String method = ImportJsonSupport.readMethodOrDefault(options.get("method"), "GET");
        Map<String, String> headers = ImportJsonSupport.readHeaders(options.get("headers"));
        String body = ImportJsonSupport.readBody(options.get("body"));

        return new ImportedRequest(url.value(), method, headers, body);
    }

    // -------------------------------------------------------- 字串字面量 / 大括號配對

    private static int indexOfFirstQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                return i;
            }
        }
        return -1;
    }

    /** 從 {@code quoteIndex}（指向開頭引號）讀出一段 JS 字串字面量，處理常見跳脫。 */
    private static StringLiteral readStringLiteral(String s, int quoteIndex) {
        char quote = s.charAt(quoteIndex);
        StringBuilder value = new StringBuilder();
        int i = quoteIndex + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                value.append(unescapeJs(s.charAt(i + 1)));
                i += 2;
                continue;
            }
            if (c == quote) {
                return new StringLiteral(value.toString(), i + 1);
            }
            value.append(c);
            i++;
        }
        throw new ApiException(ErrorCode.VALIDATION_ERROR, "fetch(...) URL string literal is not terminated.");
    }

    private static char unescapeJs(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            default -> escaped; // \" \' \\ 以及其餘一律照字面：足夠涵蓋 DevTools 會產生的內容
        };
    }

    /** 從 {@code openIndex}（指向 {@code {}）找出對應的 {@code }}，正確跳過字串字面量內的大括號。 */
    private static int matchBrace(String s, int openIndex) {
        int depth = 0;
        int i = openIndex;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipStringLiteral(s, i);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static int skipStringLiteral(String s, int quoteIndex) {
        char quote = s.charAt(quoteIndex);
        int i = quoteIndex + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        throw new ApiException(ErrorCode.VALIDATION_ERROR, "fetch(...) options object has an unterminated string.");
    }

    private record StringLiteral(String value, int endExclusive) {
    }
}
