package com.jason.notifyline.monitor.domain;

import java.util.List;

/**
 * {@code api_monitor.computed_fields} 陣列裡的一條計算欄位：輸入模板 + 依序套用的
 * 雜湊步驟。見 {@code Docs/plan/13-監控計算欄位設計.md} §2.2。
 *
 * <p>純值物件，跟 {@link ExtractRule} 一樣：反序列化與驗證留給使用端
 * （{@code ComputedFieldValidator}、{@code ComputedFieldEvaluator}）負責。
 *
 * @param name  模板佔位符引用的鍵，如 {@code {{computed.sign}}} 中的 {@code sign}
 *              （限 {@code [A-Za-z0-9_]{1,32}}，驗證由使用端負責，理由同 {@link ExtractRule}）
 * @param input 求值前的模板文字，可引用 {@code {{secret.NAME}}}、{@code {{now...}}}、
 *              {@code {{uuid}}}，以及<strong>陣列中更早</strong>已定義的
 *              {@code {{computed.NAME}}}——不可前向引用、不可自我引用，驗證由使用端負責
 * @param steps 依序套用的雜湊步驟，前一段的輸出字串就是後一段的輸入，至少一段
 */
public record ComputedField(String name, String input, List<ComputedStep> steps) {

    public ComputedField {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
