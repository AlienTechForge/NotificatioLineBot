package com.jason.notifyline.monitor.parse;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 JsonPointer（RFC 6901）從 JSON 樹取值。見 {@code Docs/plan/11-API監控輪詢設計.md} §6.1。
 *
 * <p>純粹的樹狀取值工具，不碰網路也不碰資料庫 —— 抓取（{@code fetch} 套件）與比對
 * （{@link ChangeDetector}）都疊在它上面，寫成沒有副作用的類別，兩邊都能放心
 * 重複呼叫、平行呼叫，也才能像本波次要求的那樣被「窮舉式」測試覆蓋。
 *
 * <p>用 {@code tools.jackson.core.JsonPointer}（Jackson 3），不是
 * {@code com.fasterxml.jackson.*}（Jackson 2）—— 專案內兩套 Jackson 並存
 * （見 {@code pom.xml} 對 {@code jackson-2-bom.version} 的註解：Spring MVC 走
 * Jackson 3，LINE SDK 走自己的 Jackson 2），監控功能屬於 Spring MVC 這一側，
 * 混用兩套型別只會讓同一段解析邏輯出現兩種 {@code JsonNode}，徒增混淆。
 */
@Component
public class JsonExtractor {

    private final ObjectMapper objectMapper;

    public JsonExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把原始 JSON 文字解析成樹。
     *
     * @throws ApiException 不是合法 JSON（{@code VALIDATION_ERROR}）—— 目標 API 雖然
     *                       回了 {@code application/json} 的 Content-Type（見
     *                       §5.1 第 7 點），body 本身仍可能不是合法 JSON，這裡是
     *                       最後一道檢查
     */
    public JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Response body is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 依 JsonPointer 解析出節點，不做任何「取不到就回 null」之類的轉換 ——
     * 呼叫端有時需要知道原始節點型別（例如 {@link ChangeDetector} 要檢查
     * {@code item_pointer} 是不是真的指向陣列），這種情況用 {@link #extract}
     * 拿到的字串就分不出「陣列序列化後長這樣」跟「其實不是陣列」。
     *
     * @throws ApiException pointer 語法不合法（{@code VALIDATION_ERROR}）
     */
    public JsonNode at(JsonNode root, String pointerExpr) {
        return root.at(compile(pointerExpr));
    }

    /**
     * 依 JsonPointer 取值並轉成字串，供模板 / 指紋使用。
     *
     * <p>取不到（路徑不存在，或值本身是 JSON {@code null}）回傳 {@code null}；
     * 取到容器（物件／陣列）回傳緊湊 JSON 字串；取到純量回傳
     * {@link JsonNode#asString()}。
     *
     * @throws ApiException pointer 語法不合法（{@code VALIDATION_ERROR}）
     */
    public String extract(JsonNode root, String pointerExpr) {
        JsonNode node = at(root, pointerExpr);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            return node.asString();
        }
        return serialize(node);
    }

    /**
     * 依序取出多條規則的值，用規則的 {@code name} 當鍵。
     *
     * <p>回傳的 map 保留規則清單的原始順序（{@link LinkedHashMap}），值可能是
     * {@code null}——要不要把它顯示成 {@code —}、要不要拿去排序算指紋，都是
     * 呼叫端（{@link ChangeDetector}、{@link MessageTemplate}）的事，這裡只負責
     * 「照規則取值」。
     */
    public Map<String, String> extractAll(JsonNode root, List<ExtractRule> rules) {
        Map<String, String> values = new LinkedHashMap<>();
        for (ExtractRule rule : rules) {
            values.put(rule.name(), extract(root, rule.pointer()));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 序列化任意值成 JSON 字串。package-private —— 只有 {@link ChangeDetector}
     * 組 {@code EXTRACTED} 模式的 canonical 指紋字串時需要借用同一顆
     * {@link ObjectMapper}，這個套件外不必知道序列化細節。
     */
    String serialize(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static JsonPointer compile(String pointerExpr) {
        try {
            return JsonPointer.compile(pointerExpr);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Invalid JsonPointer syntax: " + pointerExpr, e);
        }
    }
}
