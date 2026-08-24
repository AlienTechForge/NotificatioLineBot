package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 極簡 shell 分詞器，只涵蓋 Chrome DevTools「Copy as cURL」實際會產生的語法子集——
 * 不是完整的 shell 文法實作。見 {@code Docs/plan/12-API監控易用性升級.md} §2.2。
 *
 * <p>兩種形式的規則同時支援，不特別分辨來源（真正餵進來的內容只會是其中一種，
 * 讓分詞器同時認得兩者不會製造歧義，換來的是不必先偵測「這是 bash 還是 cmd」）：
 *
 * <ul>
 *   <li><strong>bash</strong>：單引號字串（{@code '...'}，內容一律照字面，不認識任何
 *       跳脫）；{@code '\''} 跳脫序列——三個 shell word（{@code '...'} + {@code \'} +
 *       {@code '...'}）無縫相接，其中 {@code \'} 是「不在引號內時，反斜線跳脫下一個
 *       字元」這條通用規則的特例，不需要另外特殊處理；雙引號字串（{@code \" \\} 是
 *       跳脫，其餘反斜線照字面）；行尾 {@code \} 接續下一行</li>
 *   <li><strong>cmd</strong>：雙引號字串（{@code \"} 是跳脫的引號，Chrome 產生 cmd
 *       形式時就是這樣跳脫內部引號）；行尾 {@code ^} 接續下一行</li>
 * </ul>
 */
final class ShellTokenizer {

    private ShellTokenizer() {
    }

    private static final int NORMAL = 0;
    private static final int SINGLE_QUOTE = 1;
    private static final int DOUBLE_QUOTE = 2;

    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean tokenStarted = false;
        int state = NORMAL;
        int i = 0;
        int len = raw.length();

        while (i < len) {
            char c = raw.charAt(i);
            switch (state) {
                case SINGLE_QUOTE -> {
                    if (c == '\'') {
                        state = NORMAL;
                    } else {
                        current.append(c);
                    }
                    i++;
                }
                case DOUBLE_QUOTE -> {
                    if (c == '"') {
                        state = NORMAL;
                        i++;
                    } else if (c == '\\' && i + 1 < len && isDoubleQuoteEscapable(raw.charAt(i + 1))) {
                        current.append(raw.charAt(i + 1));
                        i += 2;
                    } else {
                        current.append(c);
                        i++;
                    }
                }
                default -> {
                    if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                        if (tokenStarted) {
                            tokens.add(current.toString());
                            current.setLength(0);
                            tokenStarted = false;
                        }
                        i++;
                    } else if (c == '\'') {
                        state = SINGLE_QUOTE;
                        tokenStarted = true;
                        i++;
                    } else if (c == '"') {
                        state = DOUBLE_QUOTE;
                        tokenStarted = true;
                        i++;
                    } else if ((c == '\\' || c == '^') && isLineContinuation(raw, i)) {
                        // 行尾續行：bash 用 \，cmd 用 ^。兩者都整個跳過（含換行本身），
                        // 不當成 token 分隔字元、也不留下任何字元——這樣接續行的內容
                        // 才會跟上一行「無縫」接起來，續行本身的縮排空白會自然形成
                        // 下一個 token 的分隔。
                        i = skipLineContinuation(raw, i);
                    } else if (c == '\\' && i + 1 < len) {
                        // NORMAL 狀態下的反斜線：跳脫下一個字元，照字面附加。這正是
                        // 'it'\''s' 這種寫法中間 \' 的處理方式——不在引號內時，
                        // backslash 跳脫的是「下一個字元」本身，不是某種特殊語法。
                        current.append(raw.charAt(i + 1));
                        tokenStarted = true;
                        i += 2;
                    } else {
                        current.append(c);
                        tokenStarted = true;
                        i++;
                    }
                }
            }
        }

        if (state != NORMAL) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "cURL command has an unterminated quote.");
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static boolean isDoubleQuoteEscapable(char next) {
        return next == '"' || next == '\\';
    }

    /** 行尾續行字元：{@code \} 或 {@code ^} 後面（跳過可能夾在中間的 {@code \r}）緊接著換行。 */
    private static boolean isLineContinuation(String raw, int i) {
        int j = i + 1;
        if (j < raw.length() && raw.charAt(j) == '\r') {
            j++;
        }
        return j < raw.length() && raw.charAt(j) == '\n';
    }

    private static int skipLineContinuation(String raw, int i) {
        int j = i + 1;
        if (j < raw.length() && raw.charAt(j) == '\r') {
            j++;
        }
        return j + 1; // 跳過 \n 本身
    }
}
