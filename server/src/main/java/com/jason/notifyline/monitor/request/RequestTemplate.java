package com.jason.notifyline.monitor.request;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 送出前套用在 URL、每個 header value、body 的動態值替換。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.5。
 *
 * <h2>封閉集合——這是安全邊界，不是偷懶</h2>
 *
 * <p>可用的佔位符寫死在這個類別裡，<strong>絕不提供讓使用者自訂變數的機制</strong>。
 * 開放了使用者自訂變數，等於讓貼上的內容間接控制 URL 的組成，而
 * {@link com.jason.notifyline.monitor.fetch.OutboundUrlGuard} 檢查的是「替換後」的
 * 網址——一個開放式變數集合就是繞過那道檢查最自然的路徑。今天這組變數（時間、
 * UUID）產不出 host，但這個限制要維持在「這個類別能做什麼」的層次，不能只是
 * 「今天沒人這樣用」。
 *
 * <h2>呼叫端的義務：替換後要重新過 guard</h2>
 *
 * <p>這個類別本身<strong>不會、也不能</strong>呼叫
 * {@link com.jason.notifyline.monitor.fetch.OutboundUrlGuard}——它不知道呼叫端是
 * 排程輪詢（{@code ApiMonitorRunner}）還是後台試跑（{@code AdminService}），這兩條
 * 路徑本來就分屬不同的交易/非交易邊界，把 guard 檢查放在這裡反而會讓職責混在一起。
 * <strong>每一個呼叫 {@link #render} 處理 URL 的呼叫端，都必須在替換完成後、實際送出
 * 之前，重新呼叫一次 {@code OutboundUrlGuard.check()}</strong>——這是縱深防禦，
 * 目前的變數集合產不出 host，但這道檢查要在日後有人加新變數的那天之前就已經在那裡。
 *
 * <h2>未知佔位符與畸形 pattern 一律拋錯</h2>
 *
 * <p>跟 {@link com.jason.notifyline.monitor.parse.MessageTemplate} 刻意相反：訊息模板
 * 遇到未知佔位符會替換成 {@code —} 繼續送，因為訊息模板打錯字只會讓「這一則」訊息
 * 讀起來怪；請求模板打錯字會讓「每一輪」都打到錯的網址，早點在存檔當下失敗，
 * 好過每輪安靜地打錯地方。{@code {{now.format:PATTERN}}} 的 {@code PATTERN} 同理：
 * 呼叫 {@link DateTimeFormatter#ofPattern} 前先限制字元集合與長度，避免畸形 pattern
 * 讓 {@code ofPattern} 拋例外變成每輪必失敗。
 */
@Component
public class RequestTemplate {

    /** 比對 {@code {{ ... }}}，語法與掃描規則抄 {@code MessageTemplate.PLACEHOLDER}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");

    /**
     * {@code now}、選配的位移（{@code ±N[smhd]}）、以及子指令。子指令要嘛是固定關鍵字，
     * 要嘛是 {@code format:PATTERN}——{@code PATTERN} 本身可能含 {@code .}（例如
     * {@code yyyy.MM.dd}），所以子指令用貪婪的 {@code (.+)} 吃到結尾，不能用第一個
     * {@code .} 切分。
     */
    private static final Pattern NOW_TOKEN =
            Pattern.compile("^now(?:([+-])(\\d{1,6})([smhd]))?\\.(.+)$");

    /** {@code now.format:PATTERN} 只允許的字元集合，見類別註解與 doc §2.5。 */
    private static final Pattern FORMAT_PATTERN_CHARS = Pattern.compile("[yMdHmsSa\\-/:.\\s']*");

    private static final int MAX_FORMAT_PATTERN_LENGTH = 32;

    /** 未知佔位符錯誤訊息裡回顯的佔位符文字上限，避免異常長的輸入撐爆錯誤訊息。 */
    private static final int MAX_ECHOED_KEY_LENGTH = 100;

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    /** {@code {{now.iso8601}}}：UTC，{@code 2026-08-24T03:00:00Z}——秒精度，不含小數。 */
    private static final DateTimeFormatter ISO8601_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Clock clock;

    public RequestTemplate(Clock clock) {
        this.clock = clock;
    }

    /**
     * 渲染一份模板文字（URL 或單一 header value 或 body）。
     *
     * @param template {@code null} 原樣回傳 {@code null}（body 可能真的是 {@code null}，
     *                 跟「空字串 body」是不同語意，不可混為一談）
     * @throws ApiException 未知佔位符，或 {@code now.format:PATTERN} 不合法（均
     *                       {@code VALIDATION_ERROR}）
     */
    public String render(String template) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        int lastEnd = 0;
        while (matcher.find()) {
            rendered.append(template, lastEnd, matcher.start());
            rendered.append(resolve(matcher.group(1).trim()));
            lastEnd = matcher.end();
        }
        rendered.append(template, lastEnd, template.length());
        return rendered.toString();
    }

    /**
     * 對一組 header 逐一套用 {@link #render}，保留原始鍵與插入順序。
     *
     * @param headers {@code null} 視同空 map
     */
    public Map<String, String> renderHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> rendered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            rendered.put(entry.getKey(), render(entry.getValue()));
        }
        // Map.copyOf() 不保證保留插入順序（其底層實作可能重新雜湊），這裡要保序，
        // 改用 unmodifiableMap 包住原本的 LinkedHashMap。
        return Collections.unmodifiableMap(rendered);
    }

    private String resolve(String key) {
        if (key.equals("uuid")) {
            return UUID.randomUUID().toString();
        }

        Matcher nowMatcher = NOW_TOKEN.matcher(key);
        if (!nowMatcher.matches()) {
            throw unknownPlaceholder(key);
        }

        Instant instant = Instant.now(clock);
        if (nowMatcher.group(1) != null) {
            long amount = Long.parseLong(nowMatcher.group(2));
            ChronoUnit unit = unitFor(nowMatcher.group(3));
            instant = "-".equals(nowMatcher.group(1)) ? instant.minus(amount, unit) : instant.plus(amount, unit);
        }

        String subcommand = nowMatcher.group(4);
        if (subcommand.equals("epochSeconds")) {
            return String.valueOf(instant.getEpochSecond());
        }
        if (subcommand.equals("epochMillis")) {
            return String.valueOf(instant.toEpochMilli());
        }
        if (subcommand.equals("iso8601")) {
            return ISO8601_UTC.format(instant);
        }
        if (subcommand.startsWith("format:")) {
            return formatWithPattern(instant, subcommand.substring("format:".length()));
        }
        throw unknownPlaceholder(key);
    }

    /**
     * {@code now.format:PATTERN}。先做字元集合與長度的白名單檢查，再呼叫
     * {@link DateTimeFormatter#ofPattern}——即使字元都合法，仍可能組成語法不合法的
     * pattern（例如單引號沒有成對），這裡兩層都要擋，任何一層失敗都不可以讓例外
     * 原樣往上拋到排程迴圈，見類別註解。
     */
    private String formatWithPattern(Instant instant, String pattern) {
        if (pattern.isEmpty() || pattern.length() > MAX_FORMAT_PATTERN_LENGTH
                || !FORMAT_PATTERN_CHARS.matcher(pattern).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Invalid now.format pattern: only [yMdHmsSa-/:. and space/quote] are allowed, max "
                            + MAX_FORMAT_PATTERN_LENGTH + " characters.");
        }
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            // 字元集合合法不保證語法合法（例如單引號沒有成對收尾）。
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid now.format pattern syntax.");
        }
        return formatter.withZone(TAIPEI_ZONE).format(instant);
    }

    private static ChronoUnit unitFor(String unit) {
        return switch (unit) {
            case "s" -> ChronoUnit.SECONDS;
            case "m" -> ChronoUnit.MINUTES;
            case "h" -> ChronoUnit.HOURS;
            case "d" -> ChronoUnit.DAYS;
            default -> throw new IllegalStateException("unreachable: NOW_TOKEN 已限制 unit 只能是 [smhd]");
        };
    }

    private static ApiException unknownPlaceholder(String key) {
        String echoed = key.length() > MAX_ECHOED_KEY_LENGTH ? key.substring(0, MAX_ECHOED_KEY_LENGTH) + "..." : key;
        return new ApiException(ErrorCode.VALIDATION_ERROR,
                "Unknown request template placeholder: {{" + echoed + "}}. Allowed: now.epochSeconds, "
                        + "now.epochMillis, now.iso8601, now.format:PATTERN, now±N[smhd]..., uuid.");
    }
}
