package com.jason.notifyline.monitor.importer;

import java.util.Map;

/**
 * 從貼上的內容（cURL / {@code fetch(...)} / 自訂 JSON）解析出的一次請求。見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.1、§2.6。
 *
 * <p>刻意跟 {@code AdminDto.CreateMonitorRequest} 的請求相關欄位（url／method／
 * headers／requestBody）形狀一致——前端（W7）預期能直接把這裡的欄位塞進編輯抽屜
 * 對應的欄位，不需要額外轉換。
 *
 * <p><strong>這是解析結果，不是已驗證可以送出的請求。</strong> {@code url} 沒有經過
 * {@link com.jason.notifyline.monitor.fetch.OutboundUrlGuard} 以外的任何檢查——匯入端點
 * （{@code AdminService#importMonitorRequest}）在回傳這個物件之前會先過一次 guard，
 * 但這個型別本身不強制這件事，呼叫端不可以假設拿到的實例已經安全。
 *
 * @param method  一律大寫（{@code GET}/{@code POST}/...）。解析器沒看到明確方法時依
 *                內容推斷（有 body 就預設 {@code POST}，否則 {@code GET}）——實際能否
 *                存檔仍由 {@code ApiMonitor} 的 GET/POST 限制把關，這裡只忠實回報
 *                解析結果，不做「哪些方法合法」的判斷
 * @param headers 解析出的原始 header（含 {@code cookie}——W5 尚未實作 site_session
 *                cookie jar，見 {@code Docs/plan/12-API監控易用性升級.md} §0 的決策，
 *                cookie 目前當成一般 header 處理，W6 才會把它移出去）
 * @param body    {@code null} 代表沒有 body（GET 或沒帶 {@code -d}/{@code body} 的請求）
 */
public record ImportedRequest(String url, String method, Map<String, String> headers, String body) {

    public ImportedRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
