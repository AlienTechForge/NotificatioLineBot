package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析 Chrome DevTools「Copy as cURL」的 bash／cmd 兩種輸出。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.2。
 *
 * <p>先用 {@link ShellTokenizer} 把整段文字拆成 shell word，再依已知旗標逐一解讀。
 * <strong>只認識文件列出的這幾個旗標</strong>——curl 本身有上百個旗標，但 Chrome
 * 實際只會產生這一小組。任何不認識的旗標一律<strong>忽略旗標本身、不消耗下一個
 * token</strong>：這是刻意保守的選擇——如果錯把某個不認識的旗標當成「會帶參數」，
 * 可能誤吃真正的 URL 或別的旗標值；錯把它當「不帶參數」頂多留下一個沒被解讀的
 * flag，不會弄丟任何資訊。
 */
final class CurlParser {

    private CurlParser() {
    }

    private static final Set<String> HEADER_FLAGS = Set.of("-H", "--header");
    private static final Set<String> METHOD_FLAGS = Set.of("-X", "--request");
    private static final Set<String> DATA_FLAGS = Set.of("-d", "--data", "--data-raw", "--data-binary");
    private static final Set<String> COOKIE_FLAGS = Set.of("-b", "--cookie");
    private static final Set<String> USER_AGENT_FLAGS = Set.of("-A", "--user-agent");
    // -g/--globoff：curl 關掉 URL 大括號／方括號 range 展開的旗標——正是
    // stripCurlGlobEscapes() 存在的原因（見那個方法的說明）。這裡不需要真的「關閉」
    // 什麼（我們從來不會展開 range），單純認得這個旗標、不要把它當成不認識的旗標。
    private static final Set<String> NO_ARG_IGNORED_FLAGS = Set.of("--compressed", "-g", "--globoff");
    private static final String URL_FLAG = "--url";
    private static final String URL_FLAG_PREFIX = "--url=";

    /**
     * 這個功能永不跟隨 redirect（{@code Docs/plan/11-API監控輪詢設計.md} §5.1 第 2 點）。
     * 靜靜忽略 {@code -L}/{@code --location} 會讓使用者以為有跟隨，所以一定要用明確
     * 錯誤擋下，不能像其他不認識的旗標一樣悄悄放過。
     */
    private static final Set<String> REJECTED_FLAGS = Set.of("-L", "--location");

    static ImportedRequest parse(String raw) {
        List<String> tokens = ShellTokenizer.tokenize(raw);

        String url = null;
        String method = null;
        StringBuilder body = null; // null = 完全沒看過任何 -d/--data* 旗標
        Map<String, String> headers = new LinkedHashMap<>();

        int i = (!tokens.isEmpty() && tokens.get(0).equalsIgnoreCase("curl")) ? 1 : 0;

        while (i < tokens.size()) {
            String token = tokens.get(i);

            if (REJECTED_FLAGS.contains(token)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "-L/--location is not supported: this feature never follows redirects. "
                                + "Remove the flag and paste the command again.");
            }
            if (HEADER_FLAGS.contains(token)) {
                putHeader(headers, requireArg(tokens, i, "-H/--header"));
                i += 2;
            } else if (METHOD_FLAGS.contains(token)) {
                method = requireArg(tokens, i, "-X/--request");
                i += 2;
            } else if (DATA_FLAGS.contains(token)) {
                String value = requireArg(tokens, i, token);
                // 多個 -d/--data* 旗標時，真正的 curl 用 & 串接（form-encoded 語意）。
                body = body == null ? new StringBuilder(value) : body.append('&').append(value);
                i += 2;
            } else if (COOKIE_FLAGS.contains(token)) {
                headers.put("cookie", requireArg(tokens, i, "-b/--cookie"));
                i += 2;
            } else if (USER_AGENT_FLAGS.contains(token)) {
                headers.put("user-agent", requireArg(tokens, i, "-A/--user-agent"));
                i += 2;
            } else if (token.equals(URL_FLAG)) {
                // 明確支援 --url：目前為止能運作純屬巧合——不認識的旗標本來就不消耗
                // 下一個 token，所以 --url 後面的值剛好落進下面「第一個非旗標參數」
                // 那條路徑。一旦改成 --url=value 這種帶等號的形式就會整個失效（等號
                // 版本從頭到尾只有一個 token，不會有「下一個 token」可撿），所以還是
                // 得在這裡明確處理，兩種形式共用同一個 URL glob 跳脫清理。
                String value = stripCurlGlobEscapes(requireArg(tokens, i, "--url"));
                if (url == null) {
                    url = value;
                }
                i += 2;
            } else if (token.startsWith(URL_FLAG_PREFIX)) {
                String value = stripCurlGlobEscapes(token.substring(URL_FLAG_PREFIX.length()));
                if (url == null) {
                    url = value;
                }
                i += 1;
            } else if (NO_ARG_IGNORED_FLAGS.contains(token)) {
                i += 1;
            } else if (token.startsWith("-")) {
                i += 1; // 不認識的旗標：見類別註解，忽略旗標本身，不消耗下一個 token
            } else {
                if (url == null) {
                    url = stripCurlGlobEscapes(token); // URL = 第一個非旗標參數
                }
                i += 1;
            }
        }

        if (url == null || url.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cURL command is missing a URL.");
        }

        String finalBody = body == null ? null : body.toString();
        String finalMethod = method != null
                ? method.toUpperCase(Locale.ROOT)
                : (finalBody != null ? "POST" : "GET"); // 有 body 沒明講方法時，跟真的 curl 一樣預設 POST

        return new ImportedRequest(url, finalMethod, headers, finalBody);
    }

    /**
     * 去掉 curl 自己的 URL glob 跳脫（{@code \[ \] \{ \}}）——這跟 shell 跳脫是兩回事。
     * curl 把 URL 裡的 {@code [1-10]}／{@code {a,b}} 當成 range／list 展開語法（除非帶
     * {@code -g}/{@code --globoff}），所以 Chrome DevTools「Copy as cURL」匯出時，如果
     * URL 本來就含字面上的方括號或大括號（很常見——例如把 JSON 塞進 query string 的
     * API），就會在前面加反斜線防止真的 curl 誤展開。{@link ShellTokenizer} 只負責把
     * 外層的 bash/cmd 引號脫掉，這裡看到的反斜線是 shell 那層已經處理完、只有 curl
     * 自己認得的第二層語法，真正的 curl 執行前就會把它們吃掉；我們不呼叫真正的
     * curl，所以要自己補這一步，否則反斜線原封不動留在 URL 裡，會讓
     * {@code URI}/{@code new URI(String)} 判定成不合法字元而整段解析失敗。
     *
     * <p><strong>只能用在 URL 上</strong>：呼叫端必須只對 {@code url} 呼叫這個方法，
     * 絕不能套用到 header 值或 body——body 常常是 JSON，裡面的反斜線可能是
     * {@code \d}、{@code \n} 這種有意義的跳脫（或跳脫過的引號），照 curl 的 URL glob
     * 規則亂剝會直接改變使用者貼上的內容。
     */
    private static String stripCurlGlobEscapes(String url) {
        return url.replace("\\[", "[").replace("\\]", "]").replace("\\{", "{").replace("\\}", "}");
    }

    private static void putHeader(Map<String, String> headers, String rawHeaderLine) {
        int colon = rawHeaderLine.indexOf(':');
        if (colon < 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "-H/--header value must be in \"Name: Value\" form.");
        }
        String name = rawHeaderLine.substring(0, colon).trim();
        String value = rawHeaderLine.substring(colon + 1).trim();
        if (name.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "-H/--header value has an empty header name.");
        }
        headers.put(name, value);
    }

    private static String requireArg(List<String> tokens, int flagIndex, String flagLabel) {
        if (flagIndex + 1 >= tokens.size()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, flagLabel + " is missing its value.");
        }
        return tokens.get(flagIndex + 1);
    }
}
