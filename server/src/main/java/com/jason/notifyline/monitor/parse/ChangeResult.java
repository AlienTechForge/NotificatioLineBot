package com.jason.notifyline.monitor.parse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link ChangeDetector} 的比對結果：有變更該通知，或沒有 —— 「沒有」底下其實蓋了
 * 兩種不同成因：真的沒變，以及首次執行只記錄基準（見 {@code Docs/plan/11-API監控輪詢設計.md}
 * §6.2「首次執行只記錄不通知」）。兩者都不該發通知，所以都歸在 {@link Unchanged}。
 *
 * <p>兩個變體刻意共用同一組欄位形狀，即使某些欄位在特定比對模式下用不到
 * （例如 {@code NEW_ITEMS} 模式的 {@link #Changed(byte[], Map, Map, List) fingerprint}
 * 恆為 {@code null}）——這樣呼叫端（W3 的 {@code ApiMonitorStore}）不必為三種模式
 * 各寫一套「這次要存什麼」的邏輯，一路照著這兩個 record 的欄位寫回資料庫即可。
 *
 * <p>{@code fingerprint}、{@code currentValues}、{@code newItems} 三個欄位兩個變體都有
 * （型別、語意都相同，只是某些模式下恆為空／null），所以拉到介面上宣告，呼叫端不必
 * 為了讀這三個欄位而先 {@code switch}／{@code instanceof} 判斷是哪個變體。
 * {@code previousValues} 只有 {@link Changed} 有——{@link Unchanged} 從不渲染訊息，
 * 沒有「上次的值」可用的必要，所以刻意不拉到介面上。
 */
public sealed interface ChangeResult {

    /** {@code WHOLE_BODY} / {@code EXTRACTED} 模式的指紋；{@code NEW_ITEMS} 模式恆為 {@code null}。 */
    byte[] fingerprint();

    /** 本次取出的值，{@code {{value.NAME}}} 用；{@code NEW_ITEMS} 模式恆為空 map。 */
    Map<String, String> currentValues();

    /**
     * {@code NEW_ITEMS} 模式相關的項目清單。{@link Changed} 上是「本次新出現、該通知的項目」；
     * {@link Unchanged} 上多半是空清單，唯一例外是首次執行——這時是「本次看到的全部項目」，
     * 呼叫端要整批寫入 {@code seen_item} 但不通知。其他兩種模式恆為空清單。
     */
    List<NewItem> newItems();

    /**
     * 偵測到變更，應該（在通過 §8 防洗版檢查後）發通知。
     *
     * @param fingerprint    {@code WHOLE_BODY} / {@code EXTRACTED} 模式：要寫回
     *                       {@code last_fingerprint} 的新指紋。{@code NEW_ITEMS}
     *                       模式不使用指紋，恆為 {@code null}
     * @param currentValues  本次取出的值，渲染 {@code {{value.NAME}}} 用。
     *                       {@code NEW_ITEMS} 模式恆為空 map（項目層級的值在
     *                       {@code newItems} 裡）
     * @param previousValues 上次的值（{@code last_state}），渲染 {@code {{old.NAME}}} 用。
     *                       {@code NEW_ITEMS} 模式恆為空 map
     * @param newItems       {@code NEW_ITEMS} 模式：本次新出現、之前沒看過的項目。
     *                       其他模式恆為空清單
     */
    record Changed(byte[] fingerprint,
                    Map<String, String> currentValues,
                    Map<String, String> previousValues,
                    List<NewItem> newItems) implements ChangeResult {

        public Changed {
            fingerprint = fingerprint == null ? null : fingerprint.clone();
            currentValues = currentValues == null
                    ? Map.of() : Collections.unmodifiableMap(currentValues);
            previousValues = previousValues == null
                    ? Map.of() : Collections.unmodifiableMap(previousValues);
            newItems = newItems == null ? List.of() : List.copyOf(newItems);
        }

        /** 防禦性複製，理由同 {@code ApiMonitor.getLastFingerprint()}：不能讓外部改到內部狀態。 */
        @Override
        public byte[] fingerprint() {
            return fingerprint == null ? null : fingerprint.clone();
        }
    }

    /**
     * 沒有變更、或有變更但不該通知。依模式與情境有三種成因：
     * <ul>
     *   <li>{@code WHOLE_BODY} / {@code EXTRACTED} 首次執行 —— 只記錄基準</li>
     *   <li>{@code WHOLE_BODY} / {@code EXTRACTED} 真的沒變</li>
     *   <li>{@code NEW_ITEMS} 首次執行，或沒有新項目</li>
     * </ul>
     *
     * @param fingerprint   {@code WHOLE_BODY} / {@code EXTRACTED} 模式仍要寫回
     *                      {@code last_fingerprint}（首次執行時是「第一次算出的基準」，
     *                      非首次執行時等於算出來的當次指紋——理論上會跟上次相同，寫回
     *                      是一次無害的覆蓋）。{@code NEW_ITEMS} 模式恆為 {@code null}
     * @param currentValues 理由同上，寫回 {@code last_state}。{@code NEW_ITEMS} 模式恆為空 map
     * @param newItems      {@code NEW_ITEMS} 模式<strong>首次執行</strong>時：本次看到的
     *                      所有項目，呼叫端要整批寫入 {@code seen_item} 但不通知。
     *                      非首次執行且真的沒有新項目時為空清單（沒有東西要寫）。
     *                      其他兩種模式恆為空清單
     */
    record Unchanged(byte[] fingerprint,
                      Map<String, String> currentValues,
                      List<NewItem> newItems) implements ChangeResult {

        public Unchanged {
            fingerprint = fingerprint == null ? null : fingerprint.clone();
            currentValues = currentValues == null
                    ? Map.of() : Collections.unmodifiableMap(currentValues);
            newItems = newItems == null ? List.of() : List.copyOf(newItems);
        }

        @Override
        public byte[] fingerprint() {
            return fingerprint == null ? null : fingerprint.clone();
        }
    }

    /**
     * {@code NEW_ITEMS} 模式下一個新項目。
     *
     * @param itemKey {@code item_key_pointer} 取出的鍵，已正規化（超過 200 字元時是
     *                SHA-256 十六進位字串，見 {@link ChangeDetector} 的說明）
     * @param fields  該項目的欄位，渲染 {@code {{item.NAME}}} 用；沒有設定
     *                {@code item_field_rules} 時為空 map
     */
    record NewItem(String itemKey, Map<String, String> fields) {

        public NewItem {
            fields = fields == null ? Map.of() : Collections.unmodifiableMap(fields);
        }
    }
}
