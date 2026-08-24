package com.jason.notifyline.monitor;

import java.util.List;
import java.util.Map;

/**
 * {@link ApiMonitorTestRunner#run} 的結果：後台「立即測試」按鈕背後的資料形狀。見
 * {@code Docs/plan/11-API監控輪詢設計.md} §10、§11、{@code Docs/plan/12-API監控易用性升級.md} §4.2。
 *
 * <p>密封成四種情況，呼叫端（{@code AdminService}）用 {@code switch} 窮舉映射成
 * {@code AdminDto.MonitorTestResult}，不會漏掉某個分支。
 *
 * <h2>{@link Success} 帶原始回應 body——這是刻意放寬的例外，不是疏漏</h2>
 *
 * <p>{@code Docs/plan/11-API監控輪詢設計.md} §10「回傳的 DTO 絕不可包含…」與
 * {@code api_monitor_run.error_message} 的規則講的是<strong>持久化的執行紀錄</strong>：
 * 那些資料會長期留在資料庫、被排程反覆寫入。試跑（dry run）是完全不同的資料生命週期——
 * 送出這次試跑的管理者本來就已經看過這份請求（多半是剛從自己的 DevTools 複製出來的），
 * 回應本身只存在於這一次 HTTP 往返、不寫進任何資料表、不進任何 log，且端點回應帶
 * {@code Cache-Control: no-store}。把「持久化路徑禁止回顯回應內容」的規則套用到這個
 * 完全不持久化的端點上，只是讓後台「點選欄位」這個核心 UX 變得不可能做，換不到任何
 * 額外的安全收斂——{@link ApiMonitorTestRunner} 的類別註解稱之為「試跑」而非「執行」正是
 * 因為這個資料生命週期的差異。因此{@link Success} 帶著（可能被截斷的）原始 body，供後台把
 * 回應渲染成可展開的樹、點節點插入 JsonPointer；{@link ParseFailed} 只帶安全的分類描述，
 * 理由與 {@code ApiMonitorRunner} 的 {@code PARSE_ERROR} 分支相同：Jackson 的解析例外訊息
 * 常夾帶原始 JSON 片段的位置資訊，那不是乾淨的 body 內容，不可原樣回給前端。
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
     * 成功：抽出了值（或 {@code NEW_ITEMS} 模式的項目預覽），並渲染出訊息文字，並帶著
     * （可能被截斷的）原始回應 body 供前端畫欄位選取樹。
     *
     * @param values           {@code WHOLE_BODY} / {@code EXTRACTED} 模式抽出的值；
     *                         {@code NEW_ITEMS} 模式恆為空 map
     * @param items            {@code NEW_ITEMS} 模式：這次回應裡的全部項目（試跑沒有
     *                         「已看過」的基準可比，所以視同全部都是新的，用來預覽
     *                         {@code item_pointer} / {@code item_key_pointer} 抓得對不對）。
     *                         其他模式恆為空清單；{@code item_pointer} / {@code item_key_pointer}
     *                         尚未設定時也是空清單（見 {@link ApiMonitorTestRunner} 的說明——
     *                         這時前端要靠 {@code body} 畫樹，還沒有項目可以預覽）
     * @param renderedMessage  渲染後的訊息；{@code NEW_ITEMS} 模式是多個項目串接後的結果，
     *                         規則同 {@code ApiMonitorRunner.buildNewItemsMessage}
     * @param body             抓到的原始回應內容，超過 256 KB（{@code ApiMonitorTestRunner}
     *                         的 {@code MAX_TEST_BODY_BYTES}）時被截斷；見類別註解「這是刻意
     *                         放寬的例外」。<strong>絕不寫入任何資料表、絕不出現在任何 log</strong>
     * @param bodyTruncated    {@code body} 是否已被截斷。截斷只影響回給瀏覽器的這一份拷貝，
     *                         {@code values} / {@code items} / {@code renderedMessage} 全部
     *                         是從完整、未截斷的回應算出來的，不受影響
     * @param bodyOriginalLength 截斷前的原始長度（UTF-8 位元組數）；{@code bodyTruncated} 為
     *                         {@code false} 時等於 {@code body} 本身的位元組數
     */
    record Success(Integer httpStatus,
                    Map<String, String> values,
                    List<ItemPreview> items,
                    String renderedMessage,
                    String body,
                    boolean bodyTruncated,
                    int bodyOriginalLength) implements MonitorTestOutcome {

        public Success {
            values = values == null ? Map.of() : Map.copyOf(values);
            items = items == null ? List.of() : List.copyOf(items);
            body = body == null ? "" : body;
        }
    }

    /** {@code NEW_ITEMS} 模式下一個項目的預覽：鍵 + 欄位值。 */
    record ItemPreview(String itemKey, Map<String, String> fields) {

        public ItemPreview {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
