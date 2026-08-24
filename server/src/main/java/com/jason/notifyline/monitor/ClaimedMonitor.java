package com.jason.notifyline.monitor;

import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ComputedField;
import com.jason.notifyline.monitor.domain.ExtractRule;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已取件的一筆監控快照。見 {@code Docs/plan/11-API監控輪詢設計.md} §7。
 *
 * <p>刻意是<strong>不可變的快照</strong>而不是 JPA entity——理由跟
 * {@code notification.dispatch.ClaimedBatch} 完全一樣：抓取目標 API 發生在交易之外，
 * 手上若拿著 entity，任何一次 getter 都可能觸發 lazy loading 而炸出
 * {@code LazyInitializationException}，或者更糟——在沒有交易的情況下悄悄開一條新連線。
 *
 * <p><strong>刻意不帶</strong> {@code cooldownSeconds}、{@code maxNotificationsPerDay}、
 * {@code consecutiveFailures}、{@code failureNotified}、{@code lastNotifiedAt}、
 * {@code notifiedCount}、{@code notifiedDay} 這些防洗版相關欄位：它們只在
 * {@code ApiMonitorStore.recordSuccess}／{@code recordFailure} 的交易內被讀取與更新，
 * 那時會重新用 {@code id} 讀出當下最新的 managed entity，用這裡快照下來的舊值做判斷
 * 只會製造「取件當下」與「回寫當下」之間的過期資料風險——而這正是租約機制要避免的。
 *
 * @param url            <strong>原始樣板文字</strong>（{@code RequestTemplate} 意義下的
 *                       模板，見 {@code Docs/plan/12-API監控易用性升級.md} §2.5），
 *                       <strong>不是</strong>已經過變數替換與 {@code URI} 解析的結果。
 *                       刻意延後到 {@code ApiMonitorRunner.execute()} 才做替換與解析，
 *                       有兩個理由：(1) {@code URI.create()} 對字面的 {@code {{ }}}
 *                       語法直接拋 {@code IllegalArgumentException}（Java 的 URI 解析器
 *                       不接受 {@code { }} 這兩個字元），必須先替換完才能解析；
 *                       (2) 更重要的是失敗隔離——{@code claim()} 一次取件一整批到期的
 *                       監控，若在那筆交易內就做替換，單一監控的樣板錯誤（未知佔位符、
 *                       畸形 pattern）會讓整個 {@code claim()} 交易失敗，拖累同一輪
 *                       其他到期的監控全部取不到件。延後到 {@code execute()}（每筆監控
 *                       各自在獨立的非同步工作上處理）才能讓樣板錯誤跟現有的
 *                       {@code PARSE_ERROR} 一樣，只讓「這一筆」監控記一次失敗
 * @param headers        解密後的明文 header（同樣是尚未替換的樣板文字），供
 *                       {@code RequestTemplate} 替換後再交給 {@code ApiFetcher}
 * @param extractRules   {@code compareMode = NEW_ITEMS} 時，這組規則相對於<strong>每個
 *                       陣列元素</strong>解讀；其他模式相對於<strong>整個回應 body</strong>
 *                       解讀。同一個資料庫欄位、兩種解讀方式，見 §6.2 與本波次的既定決策
 * @param firstRun       {@code monitor.getLastRunAt() == null}——<strong>不可</strong>用
 *                       {@code seenKeys.isEmpty()} 代替，理由見 {@code ChangeDetector.detectNewItems}
 * @param seenKeys       僅 {@code compareMode = NEW_ITEMS} 時非空
 * @param secrets        這個監控的全部 secret 明文（{@code name -> value}），{@code claim()}
 *                       時已解密——理由跟 {@code headers} 一樣：抓取發生在交易之外，不能拿著
 *                       entity 或密文到處傳。<strong>絕不可再往外傳</strong>（DTO、log、
 *                       {@code api_monitor_run}、LINE 訊息），只給
 *                       {@code ComputedFieldEvaluator} 使用。見
 *                       {@code Docs/plan/13-監控計算欄位設計.md} §5
 * @param computedFields 依序求值的計算欄位定義（{@code claim()} 時已從 JSONB 解析）
 */
public record ClaimedMonitor(
        Long id,
        String name,
        String url,
        String method,
        String requestBody,
        Map<String, String> headers,
        CompareMode compareMode,
        List<ExtractRule> extractRules,
        String itemPointer,
        String itemKeyPointer,
        String messageTemplate,
        byte[] lastFingerprint,
        Map<String, String> lastState,
        boolean firstRun,
        Set<String> seenKeys,
        Map<String, String> secrets,
        List<ComputedField> computedFields) {

    public ClaimedMonitor {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        extractRules = extractRules == null ? List.of() : List.copyOf(extractRules);
        lastFingerprint = lastFingerprint == null ? null : lastFingerprint.clone();
        lastState = lastState == null ? Map.of() : Map.copyOf(lastState);
        seenKeys = seenKeys == null ? Set.of() : Set.copyOf(seenKeys);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        computedFields = computedFields == null ? List.of() : List.copyOf(computedFields);
    }

    /**
     * 舊有呼叫端（不涉及 secret／計算欄位的既有測試）的簡便建構子：{@code secrets} /
     * {@code computedFields} 預設空，行為等同「這個監控沒有設定計算欄位」。
     */
    public ClaimedMonitor(Long id,
                          String name,
                          String url,
                          String method,
                          String requestBody,
                          Map<String, String> headers,
                          CompareMode compareMode,
                          List<ExtractRule> extractRules,
                          String itemPointer,
                          String itemKeyPointer,
                          String messageTemplate,
                          byte[] lastFingerprint,
                          Map<String, String> lastState,
                          boolean firstRun,
                          Set<String> seenKeys) {
        this(id, name, url, method, requestBody, headers, compareMode, extractRules, itemPointer, itemKeyPointer,
                messageTemplate, lastFingerprint, lastState, firstRun, seenKeys, Map.of(), List.of());
    }

    /** 防禦性複製，理由同 {@code ApiMonitor.getLastFingerprint()}。 */
    @Override
    public byte[] lastFingerprint() {
        return lastFingerprint == null ? null : lastFingerprint.clone();
    }
}
