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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 送出前套用在 URL、每個 header value、body 的動態值替換。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.5、{@code Docs/plan/13-監控計算欄位設計.md} §3、§4。
 *
 * <h2>封閉集合——這是安全邊界，不是偷懶</h2>
 *
 * <p>可用的佔位符寫死在這個類別裡，<strong>絕不提供讓使用者自訂變數的機制</strong>。
 * 開放了使用者自訂變數，等於讓貼上的內容間接控制 URL 的組成，而
 * {@link com.jason.notifyline.monitor.fetch.OutboundUrlGuard} 檢查的是「替換後」的
 * 網址——一個開放式變數集合就是繞過那道檢查最自然的路徑。今天這組變數（時間、
 * UUID、計算欄位）產不出 host，但這個限制要維持在「這個類別能做什麼」的層次，不能只是
 * 「今天沒人這樣用」。<strong>刻意不接受 {@code {{secret.*}}}</strong>——secret 只能
 * 透過計算欄位（{@code monitor.compute.ComputedFieldEvaluator}）間接使用，這樣「secret
 * 可能出現在哪裡」的範圍被限制在那一個類別內，不會散落到 URL／header 組裝邏輯裡。
 *
 * <h2>凍結時間戳／{{uuid}}——{@link Session} 存在的唯一理由</h2>
 *
 * <p>早期版本在 {@code resolve()} 裡直接呼叫 {@code Instant.now(clock)}／
 * {@code UUID.randomUUID()}，<strong>每個佔位符各取一次</strong>。沒有簽章時這只是
 * 小瑕疵；<strong>有簽章時是硬故障</strong>：計算欄位用 T1 算出 sign、{@code timestamp}
 * header 卻送出 T2，簽章跟時間戳對不上，第三方 API 回 401，而且是幾百次輪詢才發生
 * 一次的間歇性失敗，長得像對方不穩定，其實是我方同一次請求裡用了兩個不同的「現在」。
 * 見 {@code Docs/plan/13-監控計算欄位設計.md} §3。
 *
 * <p>{@link Session} 是修法：<strong>一次請求只取一次 {@code Instant}、一次
 * {@code UUID}</strong>，呼叫端（{@code ApiMonitorRunner}、{@code AdminService} 的試算路徑）
 * 在處理一個請求的最開始呼叫一次 {@link #newSession()}，之後 URL、每個 header、body、
 * 全部計算欄位都共用同一個 {@link Session}。無參數的 {@link #render(String)} /
 * {@link #renderHeaders(Map)} 仍然存在，但每次呼叫各自建立一個新的 ad-hoc session——
 * 只適合「只在乎語法對不對、不在乎實際算出的值」的場合（例如
 * {@code AdminService.validateRequestTemplate} 存檔時的語法驗證），<strong>絕不可用在
 * 真的要送出去的請求上</strong>，那種情境一律要顯式建立一個 {@link Session} 並在整個
 * 請求範圍內重複使用。
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

    private static final String COMPUTED_PREFIX = "computed.";

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    /** {@code {{now.iso8601}}}：UTC，{@code 2026-08-24T03:00:00Z}——秒精度，不含小數。 */
    private static final DateTimeFormatter ISO8601_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Clock clock;

    public RequestTemplate(Clock clock) {
        this.clock = clock;
    }

    /**
     * 一次請求凍結的瞬間——見類別註解「凍結時間戳／{{uuid}}」。
     *
     * @param instant 這次請求裡所有 {@code {{now...}}} 佔位符共用的瞬間
     * @param uuid    這次請求裡所有 {@code {{uuid}}} 佔位符共用的值
     */
    public record Session(Instant instant, UUID uuid) {
    }

    /** 建立一個新的 {@link Session}：取一次目前時間、產生一個 UUID。呼叫端在一個請求的範圍內只呼叫一次。 */
    public Session newSession() {
        return new Session(Instant.now(clock), UUID.randomUUID());
    }

    /**
     * 渲染一份模板文字（URL 或單一 header value 或 body），使用 ad-hoc 的一次性
     * {@link Session} 且不接受 {@code {{computed.*}}}。
     *
     * <p><strong>只適合純語法驗證的場合</strong>（例如存檔時確認樣板寫得對不對）——
     * 見類別註解。真的要送出去的請求一律呼叫 {@link #render(String, Session, Map)}。
     *
     * @param template {@code null} 原樣回傳 {@code null}（body 可能真的是 {@code null}，
     *                 跟「空字串 body」是不同語意，不可混為一談）
     * @throws ApiException 未知佔位符，或 {@code now.format:PATTERN} 不合法（均
     *                       {@code VALIDATION_ERROR}）
     */
    public String render(String template) {
        return render(template, newSession(), Map.of());
    }

    /**
     * 渲染一份模板文字，使用呼叫端提供的 {@link Session}（URL／headers／body／計算欄位
     * 共用同一份）與已求值的計算欄位結果。
     *
     * @param template       同 {@link #render(String)}
     * @param session        這次請求凍結的瞬間，見類別註解
     * @param computedValues {@code {{computed.NAME}}} 可查到的值，通常是
     *                       {@code ComputedFieldEvaluator.evaluate(...)} 的結果；
     *                       {@code null} 視同空 map
     * @throws ApiException 未知佔位符（含引用不存在的 {@code computed.NAME}），或
     *                       {@code now.format:PATTERN} 不合法（均 {@code VALIDATION_ERROR}）
     */
    public String render(String template, Session session, Map<String, String> computedValues) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Map<String, String> safeComputed = computedValues == null ? Map.of() : computedValues;

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        int lastEnd = 0;
        while (matcher.find()) {
            rendered.append(template, lastEnd, matcher.start());
            rendered.append(resolve(matcher.group(1).trim(), session, safeComputed));
            lastEnd = matcher.end();
        }
        rendered.append(template, lastEnd, template.length());
        return rendered.toString();
    }

    /**
     * 對一組 header 逐一套用 {@link #render(String)}（ad-hoc session，純語法驗證用），
     * 保留原始鍵與插入順序。
     *
     * @param headers {@code null} 視同空 map
     */
    public Map<String, String> renderHeaders(Map<String, String> headers) {
        return renderHeaders(headers, newSession(), Map.of());
    }

    /**
     * 對一組 header 逐一套用 {@link #render(String, Session, Map)}，保留原始鍵與插入順序。
     *
     * @param headers {@code null} 視同空 map
     */
    public Map<String, String> renderHeaders(Map<String, String> headers, Session session,
                                             Map<String, String> computedValues) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> rendered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            rendered.put(entry.getKey(), render(entry.getValue(), session, computedValues));
        }
        // Map.copyOf() 不保證保留插入順序（其底層實作可能重新雜湊），這裡要保序，
        // 改用 unmodifiableMap 包住原本的 LinkedHashMap。
        return Collections.unmodifiableMap(rendered);
    }

    private String resolve(String key, Session session, Map<String, String> computedValues) {
        if (key.startsWith(COMPUTED_PREFIX)) {
            String name = key.substring(COMPUTED_PREFIX.length());
            String value = computedValues.get(name);
            if (value == null) {
                throw unknownPlaceholder(key);
            }
            return value;
        }
        return resolveNowOrUuid(key, session).orElseThrow(() -> unknownPlaceholder(key));
    }

    /**
     * 解析 {@code now...} / {@code uuid} 這兩類佔位符，供這個類別自己與
     * {@code ComputedFieldEvaluator} / {@code ComputedFieldValidator} 共用——計算欄位的
     * {@code input} 允許同一組 {@code now}/{@code uuid} 語法，不必重刻一份一樣的正規
     * 表示式與白名單檢查。
     *
     * <p>純函式，不依賴任何 bean 狀態，所以是 {@code static}。
     *
     * @return {@code key} 不是 {@code now...} 也不是 {@code uuid} 時回傳
     *         {@link Optional#empty()}，讓呼叫端決定要不要嘗試其他 scope；{@code key}
     *         看起來像 {@code now...}（前綴符合語法）但子指令未知，或
     *         {@code now.format:PATTERN} 不合法，直接拋 {@link ApiException}——這種情況
     *         明顯是想用 {@code now} 佔位符只是打錯，不該被當成「可能是別的 scope」
     *         而放行到下一層判斷
     */
    public static Optional<String> resolveNowOrUuid(String key, Session session) {
        if (key.equals("uuid")) {
            return Optional.of(session.uuid().toString());
        }

        Matcher nowMatcher = NOW_TOKEN.matcher(key);
        if (!nowMatcher.matches()) {
            return Optional.empty();
        }

        Instant instant = session.instant();
        if (nowMatcher.group(1) != null) {
            long amount = Long.parseLong(nowMatcher.group(2));
            ChronoUnit unit = unitFor(nowMatcher.group(3));
            instant = "-".equals(nowMatcher.group(1)) ? instant.minus(amount, unit) : instant.plus(amount, unit);
        }

        String subcommand = nowMatcher.group(4);
        if (subcommand.equals("epochSeconds")) {
            return Optional.of(String.valueOf(instant.getEpochSecond()));
        }
        if (subcommand.equals("epochMillis")) {
            return Optional.of(String.valueOf(instant.toEpochMilli()));
        }
        if (subcommand.equals("iso8601")) {
            return Optional.of(ISO8601_UTC.format(instant));
        }
        if (subcommand.startsWith("format:")) {
            return Optional.of(formatWithPattern(instant, subcommand.substring("format:".length())));
        }
        throw unknownPlaceholder(key);
    }

    /**
     * {@code now.format:PATTERN}。先做字元集合與長度的白名單檢查，再呼叫
     * {@link DateTimeFormatter#ofPattern}——即使字元都合法，仍可能組成語法不合法的
     * pattern（例如單引號沒有成對），這裡兩層都要擋，任何一層失敗都不可以讓例外
     * 原樣往上拋到排程迴圈，見類別註解。
     */
    private static String formatWithPattern(Instant instant, String pattern) {
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
                        + "now.epochMillis, now.iso8601, now.format:PATTERN, now±N[smhd]..., uuid, computed.NAME.");
    }
}
