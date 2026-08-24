package com.jason.notifyline.monitor.compute;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.domain.ComputedField;
import com.jason.notifyline.monitor.domain.ComputedStep;
import com.jason.notifyline.monitor.request.RequestTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 存檔時驗證 {@code computed_fields}：名稱格式、steps 是否合法、前向引用／自我引用、
 * 未知佔位符——全部在這裡擋下並回 400，<strong>不等到輪詢才發現</strong>。見
 * {@code Docs/plan/13-監控計算欄位設計.md} §4、§5。
 *
 * <p>不做「真的算一次」的驗證（那是 {@link ComputedFieldEvaluator} 的職責，且存檔當下
 * 未必所有 secret 都已經有值可以拿來實際跑）——這裡只做結構檢查。{@code now}/
 * {@code uuid} 佔位符的語法檢查（含 {@code now.format:PATTERN} 是否合法）直接重用
 * {@link RequestTemplate#resolveNowOrUuid}，用一個丟棄結果的假 {@link RequestTemplate.Session}
 * 呼叫——只在乎它會不會拋例外，不必自己重刻一份一樣的正規表示式與白名單。
 */
@Component
public class ComputedFieldValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private static final String SECRET_PREFIX = "secret.";
    private static final String COMPUTED_PREFIX = "computed.";

    private final Clock clock;

    public ComputedFieldValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param fields           待驗證的計算欄位，依原始順序；{@code null} 或空清單視為
     *                         合法（沒有計算欄位）
     * @param knownSecretNames 這個監控目前「已知」的 secret 名稱集合——DB 既有的
     *                         ∪ 這次請求新增／覆寫的非空白項目，組法見呼叫端
     *                         {@code AdminService}
     * @throws ApiException 名稱格式不合法、重複名稱、{@code steps} 為空、演算法/編碼
     *                       缺漏、HMAC 缺 {@code keySecret} 或 {@code keySecret} 不是已知
     *                       secret、非 HMAC 卻帶 {@code keySecret}、{@code input} 空白、
     *                       佔位符 scope 未知，或 {@code computed.*} 前向／自我引用（均 400）
     */
    public void validate(List<ComputedField> fields, Set<String> knownSecretNames) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Set<String> safeKnownSecrets = knownSecretNames == null ? Set.of() : knownSecretNames;

        // 只包含「更早」定義的欄位名稱——驗證第 N 個欄位時還沒把它自己加進來，
        // 這樣「引用尚未在這個集合裡的名稱」自然涵蓋了自我引用與前向引用兩種情況，
        // 不必另外寫一條 name.equals(fieldName) 的特例判斷。
        Set<String> earlierNames = new LinkedHashSet<>();
        RequestTemplate.Session dummySession = new RequestTemplate.Session(clock.instant(), UUID.randomUUID());

        for (ComputedField field : fields) {
            validateName(field.name());
            if (earlierNames.contains(field.name())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "duplicate computed field name: " + field.name());
            }
            validateSteps(field, safeKnownSecrets);
            validatePlaceholders(field, safeKnownSecrets, earlierNames, dummySession);
            earlierNames.add(field.name());
        }
    }

    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "computed field name must match [A-Za-z0-9_]{1,32}: " + name);
        }
    }

    private void validateSteps(ComputedField field, Set<String> knownSecretNames) {
        if (field.steps().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "computed field \"" + field.name() + "\" must have at least one step");
        }
        for (ComputedStep step : field.steps()) {
            if (step.algorithm() == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "computed field \"" + field.name() + "\" has a step with no algorithm");
            }
            if (step.encoding() == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "computed field \"" + field.name() + "\" has a step with no encoding");
            }
            if (step.algorithm().isHmac()) {
                if (step.keySecret() == null || step.keySecret().isBlank()) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR,
                            "computed field \"" + field.name() + "\": " + step.algorithm()
                                    + " step requires keySecret");
                }
                if (!knownSecretNames.contains(step.keySecret())) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR,
                            "computed field \"" + field.name() + "\": keySecret \"" + step.keySecret()
                                    + "\" is not a known secret name");
                }
            } else if (step.keySecret() != null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "computed field \"" + field.name() + "\": keySecret is only valid for HMAC steps");
            }
        }
    }

    private void validatePlaceholders(ComputedField field, Set<String> knownSecretNames, Set<String> earlierNames,
                                      RequestTemplate.Session dummySession) {
        String input = field.input();
        if (input == null || input.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "computed field \"" + field.name() + "\" must have a non-empty input");
        }
        Matcher matcher = PLACEHOLDER.matcher(input);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            validatePlaceholder(field.name(), key, knownSecretNames, earlierNames, dummySession);
        }
    }

    private void validatePlaceholder(String fieldName, String key, Set<String> knownSecretNames,
                                     Set<String> earlierNames, RequestTemplate.Session dummySession) {
        if (key.startsWith(SECRET_PREFIX)) {
            String name = key.substring(SECRET_PREFIX.length());
            if (!knownSecretNames.contains(name)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "computed field \"" + fieldName + "\" references unknown secret: " + name);
            }
            return;
        }
        if (key.startsWith(COMPUTED_PREFIX)) {
            String name = key.substring(COMPUTED_PREFIX.length());
            if (!earlierNames.contains(name)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "computed field \"" + fieldName + "\" references \"" + name
                                + "\" which is not defined earlier in the list (forward or self reference)");
            }
            return;
        }
        // now.*／uuid：借用 RequestTemplate 的解析邏輯，語法不合法（畸形 format pattern、
        // 未知子指令）會直接拋 ApiException，跟這裡其餘的驗證失敗走同一條路徑；
        // resolveNowOrUuid 只對「完全不像 now/uuid」的 key 回傳 empty。
        if (RequestTemplate.resolveNowOrUuid(key, dummySession).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "computed field \"" + fieldName + "\" has unknown placeholder: {{" + key + "}}");
        }
    }
}
