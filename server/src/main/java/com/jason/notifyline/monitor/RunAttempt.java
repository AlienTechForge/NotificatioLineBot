package com.jason.notifyline.monitor;

import com.jason.notifyline.monitor.parse.ChangeResult;

import java.time.Instant;

/**
 * {@code guard.check → fetcher.fetch → extractor → detector} 這段<strong>無交易</strong>
 * 流程（{@code ApiMonitorRunner}）的執行結果，交給
 * {@code ApiMonitorStore.recordSuccess}／{@code recordFailure} 回寫。
 *
 * <p>密封成 {@link Success} / {@link Failure}，呼叫端用 {@code switch} 窮舉分派到
 * {@code ApiMonitorStore} 對應的兩個方法——刻意不用單一個「outcome 是不是 FAILED」的
 * boolean 旗標，因為那樣一來每次新增欄位都要同時考慮「這個欄位在失敗時該是什麼」，
 * 而失敗案例壓根不該擁有 {@link ChangeResult} 或渲染好的訊息。
 */
public sealed interface RunAttempt {

    Instant startedAt();

    int durationMs();

    /**
     * 抓取與比對都成功。<strong>不代表有變更</strong>——{@code changeResult} 可能是
     * {@link ChangeResult.Changed} 或 {@link ChangeResult.Unchanged}，防洗版與是否
     * 真的送出通知留給 {@code ApiMonitorStore} 判斷。
     *
     * @param httpStatus      目標 API 回應的狀態碼
     * @param changeResult    {@code ChangeDetector} 的原始結果，{@code ApiMonitorStore}
     *                        照它的欄位形狀寫回資料庫（見該型別的類別註解）
     * @param renderedMessage 只有 {@code changeResult} 是 {@link ChangeResult.Changed}
     *                        時才非 {@code null}——多項目（{@code NEW_ITEMS}）的組裝
     *                        （最多 20 筆 + 「還有 N 筆」）在這裡就做完，{@code Store}
     *                        只需要決定要不要送出，不需要再懂訊息怎麼拼
     */
    record Success(Instant startedAt,
                    int durationMs,
                    int httpStatus,
                    ChangeResult changeResult,
                    String renderedMessage) implements RunAttempt {
    }

    /**
     * 抓取或解析失敗。
     *
     * @param classification 短分類碼（例如 {@code BLOCKED_URL}、{@code TIMEOUT}、
     *                       {@code PARSE_ERROR}），寫進 {@code api_monitor_run.error_message}
     * @param detail         補充說明，<strong>絕不可含目標 API 回應內容本身</strong>——
     *                       只放狀態碼、逾時、內容型別等 metadata，或內容長度。呼叫端
     *                       （{@code ApiMonitorRunner}）在建立這個 record 前就要把這件事
     *                       做對，{@code Store} 不會、也不能再幫忙過濾一次
     * @param httpStatus     有實際 HTTP 回應時才有值，被 {@code OutboundUrlGuard}
     *                       擋下或連線失敗時為 {@code null}
     * @param host           實際要打的請求 host（樣板替換後）；只有連 URI 都還沒解析出來
     *                       就失敗的情況（{@code TEMPLATE_ERROR}）才是 {@code null}。
     *                       {@code ApiMonitorStore} 用它組「{host} 登入已過期」的通知——
     *                       見 {@code Docs/plan/12-API監控易用性升級.md} §3.3
     */
    record Failure(Instant startedAt,
                    int durationMs,
                    String classification,
                    String detail,
                    Integer httpStatus,
                    String host) implements RunAttempt {

        /** 沒有已解析 host 時的簡便建構子（例如 {@code TEMPLATE_ERROR}），也是既有測試沿用的形狀。 */
        public Failure(Instant startedAt, int durationMs, String classification, String detail, Integer httpStatus) {
            this(startedAt, durationMs, classification, detail, httpStatus, null);
        }
    }
}
