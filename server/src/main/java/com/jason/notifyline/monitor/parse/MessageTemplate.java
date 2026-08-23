package com.jason.notifyline.monitor.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code message_template} 渲染成實際要發送的文字。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §6.3。
 *
 * <p>這裡只負責<strong>一次渲染</strong>（一份 body / 一個 item）。{@code NEW_ITEMS}
 * 模式「一則訊息最多列 20 筆，其餘寫『還有 N 筆』」的組裝——對多個新項目各呼叫一次
 * {@link #render}、把結果串起來、附加摘要文字——是呼叫端（W3 的 {@code ApiMonitorRunner}）
 * 的職責，不屬於這個類別：那是「怎麼組一則訊息」的編排邏輯，跟「一個模板+一組值
 * 怎麼渲染成字串」是兩層不同的關注點，硬塞進來只會讓這個類別同時要處理字串替換
 * 又要處理「第幾筆」「還剩幾筆」這種與模板語法無關的計數邏輯。
 *
 * <p><strong>目標 API 回傳的內容會被塞進這裡渲染的文字，而這段文字最終會透過
 * {@code NotificationService.submit()} 送出去。</strong>不可繞過 {@code submit()}
 * 直接送出渲染結果——{@code submit()} 內建的 {@code UriHostValidator} 連結白名單
 * 是擋住「第三方 API 回傳釣魚連結」這條攻擊路徑的唯一防線。
 */
@Component
public class MessageTemplate {

    private static final Logger log = LoggerFactory.getLogger(MessageTemplate.class);

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter NOW_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.ROOT);

    /**
     * LINE 文字訊息上限 5000 字（{@code LineLimits.MAX_TEXT_LENGTH}）。這裡截到
     * 4500，留 500 字給呼叫端加前後綴——例如 {@code NEW_ITEMS} 模式把多個項目的
     * 渲染結果串起來後，還要附加「還有 N 筆」。如果這裡直接截到 5000，串接與摘要
     * 文字一疊上去就整包超過上限，變成要嘛在 LINE API 那端才被拒（那時通知已經
     * 判定要發，太晚了），要嘛得在呼叫端重算一次截斷長度。
     */
    private static final int MAX_LENGTH = 4500;

    /** 未知佔位符與 null 值的替代字元。刻意不是空字串——空字串會讓使用者以為欄位存在但剛好是空的。 */
    private static final String FALLBACK = "—";

    /**
     * 比對 {@code {{ ... }}}，中間不可再含大括號（避免貪婪比對吃穿多個佔位符）。
     * 允許內容前後有空白，方便使用者寫 {@code {{ value.status }}} 也能正確辨識。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");

    private final Clock clock;

    public MessageTemplate(Clock clock) {
        this.clock = clock;
    }

    /**
     * 渲染一份模板。
     *
     * <p>規則（見 §6.3）：
     * <ul>
     *   <li>未知佔位符 → {@code —}，並記一筆 WARN。<strong>不可原樣輸出</strong>
     *       {@code {{...}}}，那會讓使用者以為模板沒生效而反覆重設</li>
     *   <li>值為 {@code null} → {@code —}（不記 WARN——這是資料本身的狀態，
     *       不是模板設定錯誤）</li>
     *   <li>剝除控制字元（U+0000–U+001F），保留 {@code \n}</li>
     *   <li>渲染後截到 4500 字</li>
     * </ul>
     *
     * @param template 原始模板文字；{@code null} 或空字串回傳空字串
     * @param context  這次渲染可用的值
     */
    public String render(String template, RenderContext context) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        int lastEnd = 0;
        while (matcher.find()) {
            rendered.append(template, lastEnd, matcher.start());
            rendered.append(resolve(matcher.group(1).trim(), context));
            lastEnd = matcher.end();
        }
        rendered.append(template, lastEnd, template.length());

        String cleaned = stripControlChars(rendered.toString());
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    private String resolve(String key, RenderContext context) {
        if (key.equals("now")) {
            return Instant.now(clock).atZone(TAIPEI_ZONE).format(NOW_FORMAT);
        }
        if (key.equals("monitor.name")) {
            return context.monitorName() == null ? FALLBACK : context.monitorName();
        }

        int dot = key.indexOf('.');
        String name = dot > 0 ? key.substring(dot + 1) : "";
        Map<String, String> scopeValues = dot > 0 ? valuesForScope(key.substring(0, dot), context) : null;

        if (scopeValues == null || name.isEmpty()) {
            return unknown(key);
        }
        if (!scopeValues.containsKey(name)) {
            return unknown(key);
        }
        String value = scopeValues.get(name);
        return value == null ? FALLBACK : value;
    }

    private static Map<String, String> valuesForScope(String scope, RenderContext context) {
        return switch (scope) {
            case "value" -> context.currentValues();
            case "old" -> context.previousValues();
            case "item" -> context.itemValues();
            default -> null;
        };
    }

    private static String unknown(String placeholder) {
        // 不可原樣輸出 {{...}}——那會讓使用者以為模板沒生效而反覆重設。
        log.warn("Unknown message template placeholder: {{{}}}", placeholder);
        return FALLBACK;
    }

    /** 剝除 U+0000–U+001F 的控制字元，保留 {@code \n}（讓多行模板可讀）。 */
    private static String stripControlChars(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c > 0x1F) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * 一次渲染可用的值。
     *
     * @param monitorName    {@code {{monitor.name}}}
     * @param currentValues  {@code {{value.NAME}}}——本次取出的值
     * @param previousValues {@code {{old.NAME}}}——上次的值
     * @param itemValues     {@code {{item.NAME}}}——{@code NEW_ITEMS} 模式逐項渲染時，
     *                       該項目的欄位；非逐項渲染時傳空 map
     */
    public record RenderContext(String monitorName,
                                 Map<String, String> currentValues,
                                 Map<String, String> previousValues,
                                 Map<String, String> itemValues) {

        public RenderContext {
            currentValues = currentValues == null ? Map.of() : Collections.unmodifiableMap(currentValues);
            previousValues = previousValues == null ? Map.of() : Collections.unmodifiableMap(previousValues);
            itemValues = itemValues == null ? Map.of() : Collections.unmodifiableMap(itemValues);
        }

        /** 只需要 {@code {{monitor.name}}} / {@code {{now}}} 的場合（例如失敗通知）的簡便寫法。 */
        public static RenderContext of(String monitorName) {
            return new RenderContext(monitorName, Map.of(), Map.of(), Map.of());
        }
    }
}
