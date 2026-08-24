package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 匯入解析的統一入口：自動辨識貼上的內容是 cURL、{@code fetch(...)}，還是自訂 JSON
 * 格式，分派給對應的解析器。見 {@code Docs/plan/12-API監控易用性升級.md} §2.1、§2.6。
 *
 * <h2>純文字解析，絕不 eval</h2>
 *
 * <p>三個底層解析器（{@link CurlParser}、{@link FetchParser}、
 * {@link MonitorBundleParser}）都只做字串掃描／shell 分詞／JSON 樹狀解析，
 * {@code raw} 的內容從頭到尾不會被當成程式碼執行——不會進 {@code ScriptEngine}，
 * 也不會用任何形式的表達式求值器。這是這個套件存在的前提，不是其中一個選項。
 *
 * <h2>格式偵測</h2>
 *
 * <p>三種格式在開頭就能明確區分，不需要「試著解析、失敗就換下一種」這種回溯：
 * 自訂 JSON 一定以 {@code {} 開頭（cURL 與 fetch 都不會）；{@code fetch(...)} 一定以
 * {@code fetch(} 開頭；其餘視為 cURL（真的不是 cURL 的話，{@link CurlParser} 自己會
 * 因為找不到 URL 而拋錯，訊息足夠使用者理解問題出在哪）。
 *
 * <h2>絕不記錄輸入內容</h2>
 *
 * <p>這個類別、以及它呼叫的三個解析器，<strong>完全不寫任何 log</strong>——貼上的
 * 內容可能含 cookie 與 API token，見 {@code Docs/plan/12-API監控易用性升級.md} §2.6。
 * 解析失敗時拋出的 {@link ApiException} 訊息也刻意只用固定文字或（未知佔位符那種）
 * 使用者自己打的樣板片段，絕不回顯 {@code raw} 的任何片段。
 */
@Component
public class RequestImporter {

    /**
     * @param raw 貼上的原始內容。大小上限（64 KB）由呼叫端（{@code AdminService}）在
     *            呼叫這裡之前檢查——那是傳輸層級的防護，不是解析邏輯的一部分
     * @throws ApiException 空白輸入、無法辨識格式，或格式辨識出來但內容本身解析失敗
     *                       （均 {@code VALIDATION_ERROR}）
     */
    public ImportedRequest importRequest(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Pasted content is empty.");
        }
        String trimmed = raw.strip();

        if (trimmed.startsWith("{")) {
            return MonitorBundleParser.parse(trimmed);
        }
        if (startsWithIgnoreCase(trimmed, "fetch(")) {
            return FetchParser.parse(trimmed);
        }
        return CurlParser.parse(trimmed);
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
