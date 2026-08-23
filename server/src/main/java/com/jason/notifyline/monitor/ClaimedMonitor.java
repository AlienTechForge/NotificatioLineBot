package com.jason.notifyline.monitor;

import com.jason.notifyline.monitor.domain.CompareMode;
import com.jason.notifyline.monitor.domain.ExtractRule;

import java.net.URI;
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
 * @param headers        解密後的明文 header，供 {@code ApiFetcher} 直接使用
 * @param extractRules   {@code compareMode = NEW_ITEMS} 時，這組規則相對於<strong>每個
 *                       陣列元素</strong>解讀；其他模式相對於<strong>整個回應 body</strong>
 *                       解讀。同一個資料庫欄位、兩種解讀方式，見 §6.2 與本波次的既定決策
 * @param firstRun       {@code monitor.getLastRunAt() == null}——<strong>不可</strong>用
 *                       {@code seenKeys.isEmpty()} 代替，理由見 {@code ChangeDetector.detectNewItems}
 * @param seenKeys       僅 {@code compareMode = NEW_ITEMS} 時非空
 */
public record ClaimedMonitor(
        Long id,
        String name,
        URI uri,
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

    public ClaimedMonitor {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        extractRules = extractRules == null ? List.of() : List.copyOf(extractRules);
        lastFingerprint = lastFingerprint == null ? null : lastFingerprint.clone();
        lastState = lastState == null ? Map.of() : Map.copyOf(lastState);
        seenKeys = seenKeys == null ? Set.of() : Set.copyOf(seenKeys);
    }

    /** 防禦性複製，理由同 {@code ApiMonitor.getLastFingerprint()}。 */
    @Override
    public byte[] lastFingerprint() {
        return lastFingerprint == null ? null : lastFingerprint.clone();
    }
}
