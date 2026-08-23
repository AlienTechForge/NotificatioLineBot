package com.jason.notifyline.monitor;

import java.util.List;
import java.util.Map;

/**
 * {@link ApiMonitorTestRunner#run} 的結果：後台「立即測試」按鈕背後的資料形狀。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §10、§11。
 *
 * <p>密封成四種情況，呼叫端（{@code AdminService}）用 {@code switch} 窮舉映射成
 * {@code AdminDto.MonitorTestResult}，不會漏掉某個分支。刻意<strong>不含目標 API 的
 * 原始回應內容</strong>——{@link Success} 只帶抽出的值、預覽項目與渲染後的訊息；
 * {@link ParseFailed} 只帶安全的分類描述，理由與 {@code ApiMonitorRunner} 的
 * {@code PARSE_ERROR} 分支相同：Jackson 的解析例外訊息常夾帶原始 JSON 片段，
 * 那就是目標 API 的回應內容，不可原樣回給前端。
 */
public sealed interface MonitorTestOutcome {

    /** 被 {@code OutboundUrlGuard} 擋下——網址是 http、指向內網位址等等。完全沒有呼叫 {@code ApiFetcher}。 */
    record Blocked(String message) implements MonitorTestOutcome {
    }

    /** 通過 guard 檢查，但抓取本身失敗（逾時、3xx、非 JSON content-type、超過大小上限、4xx/5xx）。 */
    record FetchFailed(String reason, String detail, Integer httpStatus) implements MonitorTestOutcome {
    }

    /** 抓到回應但不是合法 JSON，或 {@code item_pointer} 沒有指向陣列。{@code detail} 只有分類與長度。 */
    record ParseFailed(String detail) implements MonitorTestOutcome {
    }

    /**
     * 成功：抽出了值（或 {@code NEW_ITEMS} 模式的項目預覽），並渲染出訊息文字。
     *
     * @param values          {@code WHOLE_BODY} / {@code EXTRACTED} 模式抽出的值；
     *                        {@code NEW_ITEMS} 模式恆為空 map
     * @param items           {@code NEW_ITEMS} 模式：這次回應裡的全部項目（試跑沒有
     *                        「已看過」的基準可比，所以視同全部都是新的，用來預覽
     *                        {@code item_pointer} / {@code item_key_pointer} 抓得對不對）。
     *                        其他模式恆為空清單
     * @param renderedMessage 渲染後的訊息；{@code NEW_ITEMS} 模式是多個項目串接後的結果，
     *                        規則同 {@code ApiMonitorRunner.buildNewItemsMessage}
     */
    record Success(Integer httpStatus,
                    Map<String, String> values,
                    List<ItemPreview> items,
                    String renderedMessage) implements MonitorTestOutcome {

        public Success {
            values = values == null ? Map.of() : Map.copyOf(values);
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** {@code NEW_ITEMS} 模式下一個項目的預覽：鍵 + 欄位值。 */
    record ItemPreview(String itemKey, Map<String, String> fields) {

        public ItemPreview {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
