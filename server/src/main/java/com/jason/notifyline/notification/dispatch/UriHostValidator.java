package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 檢查訊息內容中的連結是否在白名單內。見缺口 G5。
 *
 * <p><strong>威脅模型</strong>：一組外洩的金鑰若能夾帶任意連結，就是一個掛著官方
 * 帳號名義的釣魚訊息發送器 —— 使用者信任這個帳號，點擊率遠高於一般釣魚。
 *
 * <h2>掃描規則</h2>
 *
 * <p><strong>1. 對每個字串葉節點掃描，不看鍵名。</strong> LINE 把連結放在多個不同
 * 鍵名下：imagemap 的 action 用 {@code linkUri}、imagemap 本體用 {@code baseUrl}、
 * 影音訊息用 {@code originalContentUrl}、Flex 的 icon 用 {@code iconUrl}。
 * 列舉鍵名一定會漏。
 *
 * <p><strong>2. 連結也可能內嵌在文字中間。</strong> LINE 客戶端會自動把純文字裡的
 * {@code https://…} 變成可點的連結，釣魚效果與 uri action 完全相同。所以掃描的是
 * 字串內的<strong>每一個</strong> http(s) 出現位置，不是「整串是不是一個 URI」。
 *
 * <p><strong>3. 解析失敗或取不到 host 時「拒絕」而不是「略過」。</strong>
 * 我們解析失敗不代表使用者的 LINE app 也會解析失敗，略過等於為解析器差異開後門。
 *
 * <p><strong>4. 比對不能用 {@code endsWith}。</strong> 白名單有 {@code example.com}
 * 時，{@code endsWith} 會放行 {@code evil-example.com}。要嘛完全相等，要嘛帶點號邊界。
 * 大小寫正規化必須指定 {@link Locale#ROOT} —— 土耳其語系的 JVM 會把 {@code I} 轉成
 * {@code ı}，讓合法網域比對失敗。
 *
 * <h2>為什麼只管 http(s)</h2>
 *
 * <p>早期版本試圖對「整串看起來像 URI」的字串做 scheme 白名單，結果
 * {@code "Warning: disk full"} 這種再普通不過的通知內容也會被判定成 URI 而遭拒 ——
 * 一個安全控制若會擋下日常的正常用法，最後一定會被整個關掉。
 *
 * <p>現在的界線是：釣魚風險來自<strong>可點的 http(s) 連結</strong>，那是唯一需要
 * 白名單管制的東西。{@code tel:}、{@code line://}、{@code mailto:} 是 LINE 原生支援
 * 的合法 action，放行。只有一小撮永遠不該出現在訊息裡的 scheme
 * （{@code javascript:}、{@code data:} 等）走明確的黑名單。
 */
@Component
public class UriHostValidator {

    private static final Logger log = LoggerFactory.getLogger(UriHostValidator.class);

    /**
     * 內嵌連結掃描。刻意寬鬆地吃到結尾，再由
     * {@link #stripTrailingPunctuation(String)} 修掉句尾標點。
     */
    private static final Pattern HTTP_LINK =
            Pattern.compile("(?i)https?://[^\\s<>\"'\\\\|^`{}]+");

    /**
     * 永遠不該出現在 LINE 訊息裡的 scheme。只在「整個字串就是它」時才判定，
     * 避免誤傷內文。
     */
    private static final Pattern DANGEROUS_SCHEME =
            Pattern.compile("(?i)^(javascript|data|vbscript|file|blob|jar):.*");

    /** 連結後面常見的句尾標點，中英文都要含。 */
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}>'\"。，、；：！？）】》」』";

    /** 遞迴深度上限，防止惡意的深度巢狀造成 stack overflow 變成 500。 */
    private static final int MAX_DEPTH = 32;

    private final AppProperties appProperties;

    public UriHostValidator(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * @throws ApiException 任何一個連結不在白名單內（400 URI_HOST_NOT_ALLOWED）
     */
    public void validate(List<Map<String, Object>> messages) {
        List<String> allowed = appProperties.allowedUriHostList();
        for (Object message : messages) {
            scan(message, allowed, 0);
        }
    }

    private void scan(Object node, List<String> allowed, int depth) {
        if (node == null) {
            return;
        }
        if (depth > MAX_DEPTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Message object is nested too deeply (max " + MAX_DEPTH + ").");
        }

        switch (node) {
            case Map<?, ?> map -> map.values().forEach(v -> scan(v, allowed, depth + 1));
            case Iterable<?> list -> list.forEach(v -> scan(v, allowed, depth + 1));
            case String text -> checkString(text, allowed);
            default -> {
                // 數字、布林等葉節點不可能是連結
            }
        }
    }

    private void checkString(String value, List<String> allowed) {
        if (DANGEROUS_SCHEME.matcher(value.trim()).matches()) {
            throw deny(value.trim(), "scheme not allowed");
        }

        Matcher matcher = HTTP_LINK.matcher(value);
        while (matcher.find()) {
            checkLink(stripTrailingPunctuation(matcher.group()), allowed);
        }
    }

    private void checkLink(String link, List<String> allowed) {
        URI uri;
        try {
            uri = new URI(link);
        } catch (Exception e) {
            // 畸形的連結一律拒絕
            throw deny(link, "not a valid URI");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // 例如 https://exa_mple.com —— 底線讓 getHost() 回 null。拒絕而不是略過。
            throw deny(link, "could not determine host");
        }
        if (!isAllowed(host, allowed)) {
            throw deny(link, "host not in allow-list");
        }
    }

    /**
     * 白名單比對。
     *
     * <p><strong>空白名單代表不允許任何外部連結</strong> —— 這是安全的預設值，
     * 絕不可寫成「沒設定就全部放行」。
     */
    private static boolean isAllowed(String host, List<String> allowed) {
        if (allowed.isEmpty()) {
            return false;
        }
        String normalised = normalise(host);
        for (String entry : allowed) {
            String candidate = normalise(entry);
            // 完全相等，或帶點號邊界的子網域。
            // 不可只用 endsWith —— 那會放行 evil-example.com。
            if (normalised.equals(candidate) || normalised.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String host) {
        String h = host.trim();
        // 去掉 FQDN 的結尾點：example.com. 與 example.com 是同一個網域
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        try {
            // 國際化網域名稱轉 ASCII，避免用 Unicode 同形字繞過
            h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            // 轉不了就用原字串比，反正比不中就是拒絕
            log.debug("IDN 轉換失敗，改用原字串比對：{}", h);
        }
        return h.toLowerCase(Locale.ROOT);
    }

    /**
     * 修掉句尾標點。
     *
     * <p>「詳見 https://example.com。」的句號會被貪婪的比對吃進去，
     * 讓 host 變成 {@code example.com。} 而解析失敗 —— 一個合法連結因為中文標點
     * 被判成釣魚，是最容易讓人放棄整個機制的那種誤判。
     */
    private static String stripTrailingPunctuation(String link) {
        int end = link.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(link.charAt(end - 1)) >= 0) {
            end--;
        }
        return link.substring(0, end);
    }

    private static ApiException deny(String uri, String reason) {
        // 錯誤訊息帶上那個連結，呼叫端才知道是哪一個被擋。
        // 這是呼叫端自己送來的內容，回傳給他不算洩漏。
        return new ApiException(ErrorCode.URI_HOST_NOT_ALLOWED,
                "Link not permitted (" + reason + "): " + abbreviate(uri)
                        + ". Ask the administrator to add the host to app.allowed-uri-hosts.");
    }

    private static String abbreviate(String value) {
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }
}
