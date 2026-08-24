package com.jason.notifyline.monitor.compute;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.domain.ComputedField;
import com.jason.notifyline.monitor.domain.ComputedStep;
import com.jason.notifyline.monitor.domain.HashEncoding;
import com.jason.notifyline.monitor.request.RequestTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 依序求值 {@code computed_fields}。見 {@code Docs/plan/13-監控計算欄位設計.md} §4。
 *
 * <h2>steps 是串接的，不是整體套用一次</h2>
 *
 * <p>每一段的字串輸出就是下一段的輸入，{@code encoding} 掛在每一段——這是從真實站台
 * 反推出來的規格（雙重 MD5，中間轉大寫會改變第二次雜湊的輸入），見
 * {@link ComputedStep} 的類別註解。{@link #evaluate} 用一個 {@code String value} 依序
 * 穿過每一段 {@link #applyStep}，正是為了讓「上一段輸出字串＝下一段輸入」這件事在
 * 型別上就是自然發生的，不需要額外的邏輯去「記得」串接。
 *
 * <h2>封閉的佔位符集合，比 {@link RequestTemplate} 多一種 scope</h2>
 *
 * <p>{@code input} 可引用 {@code {{secret.NAME}}}、{@code {{now...}}}、{@code {{uuid}}}，
 * 以及<strong>前面已定義</strong>的 {@code {{computed.NAME}}}——不可前向引用、不可自我
 * 引用。反過來，{@link RequestTemplate}（URL／header／body）<strong>不</strong>接受
 * {@code {{secret.*}}}：secret 只能透過計算欄位間接使用，這樣「secret 可能出現在哪裡」
 * 的範圍被限制在這一個類別內。{@code now}/{@code uuid} 的語法直接重用
 * {@link RequestTemplate#resolveNowOrUuid}，不重刻一份一樣的正規表示式。
 *
 * <h2>絕不記錄任何東西</h2>
 *
 * <p>這裡處理的是 secret 明文與計算過程中的中間值，任何一行 log 都可能把機敏資料
 * 寫進伺服器日誌——這個類別完全不依賴 {@code Logger}，也不把任何引數原樣塞進例外
 * 訊息以外的地方（例外訊息本身只回顯佔位符的<strong>名稱</strong>，不含值，見
 * {@link #unknownPlaceholder}）。
 */
@Component
public class ComputedFieldEvaluator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();
    private static final HexFormat HEX_LOWER = HexFormat.of();
    private static final int MAX_ECHOED_KEY_LENGTH = 100;

    private static final String SECRET_PREFIX = "secret.";
    private static final String COMPUTED_PREFIX = "computed.";

    /**
     * 依序求值全部計算欄位。
     *
     * @param fields  依序求值的欄位定義；理論上已經通過存檔時的
     *                {@code ComputedFieldValidator} 驗證，但這裡不假設「一定合法」——
     *                遇到問題一律拋 {@link ApiException}，讓呼叫端（{@code ApiMonitorRunner}）
     *                照既有的 {@code TEMPLATE_ERROR} 失敗路徑處理，不會讓排程整個掛掉
     * @param secrets 這個監控的全部 secret 明文（{@code name -> value}），呼叫端已解密
     * @param session 這次請求凍結的 {@code Instant}／{@code uuid}，必須跟 URL／header／body
     *                共用同一份，見 {@link RequestTemplate.Session} 的類別註解
     * @return {@code name -> 最終值} 的有序 map（插入順序 = {@code fields} 的順序）
     */
    public Map<String, String> evaluate(List<ComputedField> fields,
                                        Map<String, String> secrets,
                                        RequestTemplate.Session session) {
        Map<String, String> safeSecrets = secrets == null ? Map.of() : secrets;
        Map<String, String> computed = new LinkedHashMap<>();
        for (ComputedField field : fields) {
            String value = renderInput(field.input(), safeSecrets, computed, session);
            for (ComputedStep step : field.steps()) {
                value = applyStep(value, step, safeSecrets);
            }
            computed.put(field.name(), value);
        }
        return computed;
    }

    private String renderInput(String template,
                               Map<String, String> secrets,
                               Map<String, String> computedSoFar,
                               RequestTemplate.Session session) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        int lastEnd = 0;
        while (matcher.find()) {
            rendered.append(template, lastEnd, matcher.start());
            rendered.append(resolve(matcher.group(1).trim(), secrets, computedSoFar, session));
            lastEnd = matcher.end();
        }
        rendered.append(template, lastEnd, template.length());
        return rendered.toString();
    }

    private String resolve(String key,
                           Map<String, String> secrets,
                           Map<String, String> computedSoFar,
                           RequestTemplate.Session session) {
        if (key.startsWith(SECRET_PREFIX)) {
            String name = key.substring(SECRET_PREFIX.length());
            String value = secrets.get(name);
            if (value == null) {
                throw unknownPlaceholder(key);
            }
            return value;
        }
        if (key.startsWith(COMPUTED_PREFIX)) {
            String name = key.substring(COMPUTED_PREFIX.length());
            String value = computedSoFar.get(name);
            if (value == null) {
                // 涵蓋前向引用、自我引用、引用不存在的欄位——這三種存檔時就該被
                // ComputedFieldValidator 擋下，這裡是縱深防禦。
                throw unknownPlaceholder(key);
            }
            return value;
        }
        return RequestTemplate.resolveNowOrUuid(key, session).orElseThrow(() -> unknownPlaceholder(key));
    }

    private String applyStep(String input, ComputedStep step, Map<String, String> secrets) {
        byte[] raw = digest(input, step, secrets);
        return encode(raw, step.encoding());
    }

    private byte[] digest(String input, ComputedStep step, Map<String, String> secrets) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        try {
            return switch (step.algorithm()) {
                case MD5 -> MessageDigest.getInstance("MD5").digest(data);
                case SHA1 -> MessageDigest.getInstance("SHA-1").digest(data);
                case SHA256 -> MessageDigest.getInstance("SHA-256").digest(data);
                case SHA512 -> MessageDigest.getInstance("SHA-512").digest(data);
                case HMAC_SHA1 -> hmac("HmacSHA1", data, hmacKey(step, secrets));
                case HMAC_SHA256 -> hmac("HmacSHA256", data, hmacKey(step, secrets));
            };
        } catch (NoSuchAlgorithmException e) {
            // 六種演算法都是 JDK 標準 provider 必備演算法，實務上走不到這裡。
            throw new IllegalStateException("hash algorithm not available: " + step.algorithm(), e);
        }
    }

    private byte[] hmacKey(ComputedStep step, Map<String, String> secrets) {
        String keySecretName = step.keySecret();
        if (keySecretName == null || keySecretName.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "HMAC step requires keySecret: " + step.algorithm());
        }
        String key = secrets.get(keySecretName);
        if (key == null) {
            throw unknownPlaceholder(SECRET_PREFIX + keySecretName);
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmac(String macAlgorithm, byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(key, macAlgorithm));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC algorithm not available: " + macAlgorithm, e);
        }
    }

    private static String encode(byte[] raw, HashEncoding encoding) {
        return switch (encoding) {
            case HEX_UPPER -> HEX_UPPER.formatHex(raw);
            case HEX_LOWER -> HEX_LOWER.formatHex(raw);
            case BASE64 -> Base64.getEncoder().encodeToString(raw);
        };
    }

    private static ApiException unknownPlaceholder(String key) {
        String echoed = key.length() > MAX_ECHOED_KEY_LENGTH ? key.substring(0, MAX_ECHOED_KEY_LENGTH) + "..." : key;
        return new ApiException(ErrorCode.VALIDATION_ERROR,
                "Unknown computed field placeholder: {{" + echoed + "}}. Allowed: secret.NAME, now.epochSeconds, "
                        + "now.epochMillis, now.iso8601, now.format:PATTERN, now±N[smhd]..., uuid, "
                        + "computed.NAME (only fields defined earlier in the list).");
    }
}
