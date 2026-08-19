package com.jason.notifyline.notification.dispatch;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 把 LINE 的回應與例外分成 {@link SendOutcome.Kind} 四類。
 *
 * <p>分類寫錯的代價不對稱：把終局錯誤當成可重試，只是白白多打 4 次
 * 註定失敗的請求；把可重試的當成終局，訊息就永久遺失了。所以<strong>預設偏向
 * 重試</strong>，只有明確知道重試無用的情況才判 FATAL。
 */
@Component
public class LineErrorClassifier {

    /** LINE 月額度用罄的訊息特徵。見 06 §1.4。 */
    private static final String MONTHLY_LIMIT_MARKER = "monthly limit";

    /**
     * 依 HTTP 狀態碼與回應內容分類。
     *
     * @param body LINE 的錯誤回應內容，可為 null
     */
    public SendOutcome classify(HttpStatusCode status, String body) {
        int code = status.value();
        String message = abbreviate(body);

        if (code == 429) {
            // 同樣是 429，兩種完全不同的意思：
            //   月額度用罄 → 重試到下個月都不會成功，是 FATAL
            //   短時間打太快 → 等一下就好，是 RETRY
            // 不分開會讓「額度用完」的批次安靜地重試 5 次然後失敗，
            // 而真正的原因（該加額度了）不會出現在任何地方。
            if (containsMonthlyLimit(body)) {
                return SendOutcome.fatal("LINE_MONTHLY_QUOTA", message);
            }
            return SendOutcome.retry("LINE_RATE_LIMITED", message);
        }

        if (code == 401 || code == 403) {
            // channel token 失效或被撤銷。重試只會用同一把壞掉的憑證再打一次。
            return SendOutcome.fatal("LINE_UNAUTHORIZED", message);
        }

        if (code == 400) {
            // 請求本身有問題（訊息格式、user id 無效）。內容不會自己變好。
            return SendOutcome.fatal("LINE_BAD_REQUEST", message);
        }

        if (status.is5xxServerError()) {
            return SendOutcome.retry("LINE_SERVER_ERROR", message);
        }

        if (status.is2xxSuccessful()) {
            // 呼叫端不該走到這裡；留著讓分類函式對所有輸入都有定義
            return SendOutcome.sent(null);
        }

        // 沒見過的狀態碼：偏向重試
        return SendOutcome.retry("LINE_UNEXPECTED_STATUS", "HTTP " + code + " " + message);
    }

    /**
     * 網路層例外：逾時、連線被拒、DNS 失敗。
     *
     * <p>這些一律可重試 —— 而且<strong>逾時特別重要</strong>：請求可能其實已經
     * 送達 LINE，只是回應沒回來。重試時沿用同一把 {@code X-Line-Retry-Key}，
     * LINE 就會認出那是同一則訊息而不會重複發送。
     */
    public SendOutcome classify(Throwable error) {
        return SendOutcome.retry("LINE_IO_ERROR", abbreviate(error.getClass().getSimpleName()
                + ": " + error.getMessage()));
    }

    private static boolean containsMonthlyLimit(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains(MONTHLY_LIMIT_MARKER);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() <= 500 ? single : single.substring(0, 497) + "...";
    }
}
