package com.jason.notifyline.monitor.parse;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.Ids;
import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 三種比對模式的變更偵測。見 {@code Docs/plan/11-API監控輪詢設計.md} §6.2。
 *
 * <p>{@code WHOLE_BODY} / {@code EXTRACTED} 兩種模式走指紋比對
 * （{@link #detectByFingerprint}），{@code NEW_ITEMS} 走項目集合比對
 * （{@link #detectNewItems}）——兩者需要的輸入形狀差太多（一個是「上次的指紋」，
 * 一個是「已看過的鍵集合」），硬塞進同一個方法只會讓一堆參數在另一種模式下永遠是
 * {@code null}，所以拆成兩個方法，各自的參數都是「這個模式真的會用到的」。
 *
 * <p><strong>首次執行只記錄基準，不通知</strong>：{@code last_fingerprint IS NULL}
 * （或 {@code NEW_ITEMS} 模式呼叫端傳入 {@code firstRun = true}）時回傳
 * {@link ChangeResult.Unchanged}。否則每建立一個新監控，第一次輪詢就會因為
 * 「跟不存在的上一次比起來變了」而發一則通知——全部都是雜訊。
 */
@Component
public class ChangeDetector {

    private static final HexFormat HEX = HexFormat.of();

    /** {@code api_monitor_seen_item.item_key} 的欄位長度，見 migration §3.3。 */
    private static final int MAX_ITEM_KEY_LENGTH = 200;

    private final JsonExtractor jsonExtractor;

    public ChangeDetector(JsonExtractor jsonExtractor) {
        this.jsonExtractor = jsonExtractor;
    }

    /**
     * {@code WHOLE_BODY} / {@code EXTRACTED} 模式。
     *
     * <p>{@code extractRules} 在兩種模式下都會被拿來取值，供
     * {@code {{value.NAME}}} 使用——差別只在「拿什麼來算指紋」：{@code WHOLE_BODY}
     * 一律 hash 整個 body（不需要解析 JSON，即使 body 剛好不是合法 JSON 也能正常
     * 運作，這正是「不在乎結構、只在乎有沒有變」這個模式存在的意義）；
     * {@code EXTRACTED} hash canonical 化後的取出值。<strong>只有在
     * {@code extractRules} 非空時才會解析 body</strong>——{@code WHOLE_BODY}
     * 監控多半不會填 extract_rules，這時完全不必承擔「body 剛好不是合法 JSON
     * 就整輪失敗」的風險。
     *
     * @param mode                {@code WHOLE_BODY} 或 {@code EXTRACTED}
     *                            （{@code NEW_ITEMS} 一律呼叫 {@link #detectNewItems}）
     * @param body                本次抓到的原始回應 body
     * @param extractRules        取值規則，可為空清單
     * @param previousFingerprint 上次的指紋；{@code null} 代表這個監控還沒有基準
     *                            （第一次執行，或至今每次都被 §8 防洗版擋下而沒能寫入）
     * @param previousValues      上次的 {@code last_state}，{@code {{old.NAME}}} 用；
     *                            首次執行時傳什麼都無妨（回傳的是 Unchanged，不會被拿去渲染訊息）
     * @throws IllegalArgumentException {@code mode} 是 {@code NEW_ITEMS}
     * @throws ApiException             body 不是合法 JSON，但 {@code extractRules} 非空
     *                                  （{@code VALIDATION_ERROR}）
     */
    public ChangeResult detectByFingerprint(CompareMode mode,
                                             String body,
                                             List<ExtractRule> extractRules,
                                             byte[] previousFingerprint,
                                             Map<String, String> previousValues) {
        if (mode == CompareMode.NEW_ITEMS) {
            throw new IllegalArgumentException(
                    "NEW_ITEMS mode must use detectNewItems(), not detectByFingerprint()");
        }
        List<ExtractRule> rules = extractRules == null ? List.of() : extractRules;

        Map<String, String> currentValues = rules.isEmpty()
                ? Map.of()
                : jsonExtractor.extractAll(jsonExtractor.parse(body), rules);
        byte[] fingerprint = mode == CompareMode.WHOLE_BODY
                ? Ids.sha256(body)
                : fingerprintOfValues(currentValues);

        if (previousFingerprint == null || Arrays.equals(previousFingerprint, fingerprint)) {
            return new ChangeResult.Unchanged(fingerprint, currentValues, List.of());
        }
        Map<String, String> previous = previousValues == null ? Map.of() : previousValues;
        return new ChangeResult.Changed(fingerprint, currentValues, previous, List.of());
    }

    /**
     * {@code NEW_ITEMS} 模式。
     *
     * @param body           本次抓到的原始回應 body（必須能解析成 JSON）
     * @param itemPointer    陣列位置
     * @param itemKeyPointer 每個元素的鍵位置，相對於該元素
     * @param itemFieldRules 逐項渲染 {@code {{item.NAME}}} 用的欄位規則，相對於該元素，
     *                       可為空清單（模板不引用 {@code {{item.NAME}}} 時不需要）
     * @param seenKeys       已看過的鍵集合，呼叫端從 {@code api_monitor_seen_item} 載入。
     *                       這裡只讀不改——寫回（新增本輪發現的鍵）是呼叫端（W3）的職責
     * @param firstRun       這個監控是否第一次執行。<strong>不可用
     *                       {@code seenKeys.isEmpty()} 代替</strong>——如果目標 API
     *                       過去每次都回空陣列，{@code seenKeys} 也會是空的，但那不是
     *                       第一次執行，這次冒出來的項目是真的新項目，該通知。呼叫端要用
     *                       {@code monitor.getLastRunAt() == null} 這類結構性訊號來判斷，
     *                       不能從 {@code seenKeys} 反推
     * @return {@link ChangeResult.Changed} 帶著本次新項目（該通知）；或
     *         {@link ChangeResult.Unchanged}——首次執行時帶著「本次看到的全部項目」
     *         （呼叫端要整批寫入 {@code seen_item} 但不通知），非首次執行且沒有新項目時
     *         帶空清單
     * @throws ApiException {@code item_pointer} 沒有指向陣列（{@code VALIDATION_ERROR}）——
     *                      這是設定錯誤，不是「沒有項目」，兩者不可混淆：空陣列仍然是陣列，
     *                      應該正常回傳「沒有新項目」，只有型別不對才算失敗
     */
    public ChangeResult detectNewItems(String body,
                                        String itemPointer,
                                        String itemKeyPointer,
                                        List<ExtractRule> itemFieldRules,
                                        Set<String> seenKeys,
                                        boolean firstRun) {
        List<ExtractRule> fieldRules = itemFieldRules == null ? List.of() : itemFieldRules;

        JsonNode root = jsonExtractor.parse(body);
        JsonNode array = jsonExtractor.at(root, itemPointer);
        if (!array.isArray()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "item_pointer does not resolve to a JSON array: " + itemPointer);
        }

        List<ChangeResult.NewItem> discovered = new ArrayList<>();
        for (JsonNode element : array) {
            String rawKey = jsonExtractor.extract(element, itemKeyPointer);
            if (rawKey == null) {
                // 這個元素沒有可用的鍵，無法追蹤新舊——略過而不是讓整輪失敗，
                // 一個壞掉的元素不該讓陣列裡其他正常項目都收不到通知。
                continue;
            }
            String key = normalizeItemKey(rawKey);
            if (!seenKeys.contains(key)) {
                Map<String, String> fields = jsonExtractor.extractAll(element, fieldRules);
                discovered.add(new ChangeResult.NewItem(key, fields));
            }
        }

        if (firstRun || discovered.isEmpty()) {
            // 首次執行：discovered 此時等於「本次回應裡的全部項目」（因為 seenKeys
            // 理當是空的），呼叫端要整批寫入 seen_item 但不通知。
            // 非首次執行且 discovered 為空：真的沒有新項目，沒有東西要寫。
            return new ChangeResult.Unchanged(null, Map.of(), discovered);
        }
        return new ChangeResult.Changed(null, Map.of(), Map.of(), discovered);
    }

    /**
     * {@code item_key} 正規化：超過 200 字元時取 SHA-256 十六進位字串，絕不截斷。
     *
     * <p>截斷會讓兩個不同項目的 key 前 200 字元恰好相同時撞成同一個 key——那個
     * 「新」項目因為 key 已經在 {@code seen_item} 裡而被當成看過的，永遠不會通知。
     * hash 理論上也有碰撞機率，但 SHA-256 的碰撞機率低到可忽略；而截斷的碰撞是
     * 「只要前綴相同」這種現實中真的會發生的情況（例如 key 取自一段描述文字）。
     */
    private static String normalizeItemKey(String rawKey) {
        if (rawKey.length() <= MAX_ITEM_KEY_LENGTH) {
            return rawKey;
        }
        return HEX.formatHex(Ids.sha256(rawKey));
    }

    /**
     * {@code EXTRACTED} 模式的 canonical 指紋：依 name 排序後序列化成 JSON，
     * 對整個結果取 SHA-256。
     *
     * <p>用 {@link TreeMap}（依 key 的自然順序排序）而不是直接對輸入的
     * {@link Map} 序列化——{@code extract_rules} 在後台被使用者重新排序後，
     * 如果 canonical 化沒有自己排序，同一組值會因為序列化順序不同而算出不同的
     * SHA-256，變成「使用者只是調整了規則順序」卻觸發一次假的變更通知。
     * 序列化本身走 JSON（而不是手刻 {@code name=value} 字串拼接）是為了讓
     * {@code null} 值與空字串值有明確、無歧義的區別。
     */
    private byte[] fingerprintOfValues(Map<String, String> values) {
        Map<String, String> sorted = new TreeMap<>(values);
        return Ids.sha256(jsonExtractor.serialize(sorted));
    }
}
