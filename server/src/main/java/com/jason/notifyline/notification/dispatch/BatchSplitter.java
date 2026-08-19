package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.common.LineLimits;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把收件人切成 multicast 批次。
 *
 * <p>LINE multicast 單次最多 500 人（見 {@code Docs/plan/06-LINE整合設計.md} §1.1）。
 */
public final class BatchSplitter {

    private BatchSplitter() {
    }

    /**
     * 去重後依序切批。
     *
     * <p><strong>先去重</strong>：呼叫端在 {@code userIds} 裡放了兩次同一個人時，
     * 不去重會讓對方收到兩則一模一樣的通知，而且是在兩個不同批次裡 —— 連
     * {@code X-Line-Retry-Key} 都擋不住，因為那兩批確實是不同的請求。
     *
     * <p>用 {@link LinkedHashSet} 保留順序，讓切批結果可預期、測試可寫死。
     *
     * @return 每批 1..500 人；輸入為空時回傳空清單
     */
    public static List<List<String>> split(List<String> recipients) {
        List<String> unique = List.copyOf(new LinkedHashSet<>(recipients));
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < unique.size(); from += LineLimits.MULTICAST_MAX_RECIPIENTS) {
            int to = Math.min(from + LineLimits.MULTICAST_MAX_RECIPIENTS, unique.size());
            batches.add(unique.subList(from, to));
        }
        return List.copyOf(batches);
    }
}
