package com.jason.notifyline.monitor.importer;

import com.jason.notifyline.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShellTokenizer} 的分詞規則——這是匯入解析最容易出錯的地方，見
 * {@code Docs/plan/12-API監控易用性升級.md} §2.2 的提醒：「這是最麻煩的部分，
 * 一定要仔細測試」。
 */
@DisplayName("ShellTokenizer")
class ShellTokenizerTest {

    @Test
    @DisplayName("最簡單的情況：空白分隔的未加引號 token")
    void simpleUnquotedTokens() {
        assertThat(ShellTokenizer.tokenize("curl https://example.com -X GET"))
                .containsExactly("curl", "https://example.com", "-X", "GET");
    }

    // ------------------------------------------------------------ bash：單引號

    @Test
    @DisplayName("bash：單引號字串內容照字面，不認識任何跳脫")
    void singleQuoted_literalContent() {
        assertThat(ShellTokenizer.tokenize("curl 'https://example.com/api?x=1&y=2'"))
                .containsExactly("curl", "https://example.com/api?x=1&y=2");
    }

    @Test
    @DisplayName("bash：空的單引號字串是一個空字串 token（例如 -d ''）")
    void emptySingleQuoted_isEmptyToken() {
        assertThat(ShellTokenizer.tokenize("curl -d ''"))
                .containsExactly("curl", "-d", "");
    }

    @Test
    @DisplayName("bash：'\\'' 跳脫序列——三個 word 無縫接成一個 token，還原成 it's")
    void escapedSingleQuoteIdiom() {
        assertThat(ShellTokenizer.tokenize("curl -H 'name: it'\\''s a test'"))
                .containsExactly("curl", "-H", "name: it's a test");
    }

    @Test
    @DisplayName("bash：跳脫的單引號出現在字串最前面（\\''hello'，代表值 'hello）")
    void escapedSingleQuote_atStart() {
        // \' 在 NORMAL 狀態（前面沒有任何引號）：反斜線跳脫下一個字元，得到字面上的
        // 單引號，接著 'hello' 是一般的單引號字串——組起來代表值 "'hello"。
        assertThat(ShellTokenizer.tokenize("curl -d \\''hello'"))
                .containsExactly("curl", "-d", "'hello");
    }

    @Test
    @DisplayName("bash：未終止的單引號拋錯")
    void unterminatedSingleQuote_throws() {
        assertThatThrownBy(() -> ShellTokenizer.tokenize("curl 'https://example.com"))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------ bash：續行

    @Test
    @DisplayName("bash：行尾反斜線接續下一行，跟沒有換行時的結果一樣")
    void backslashLineContinuation() {
        String raw = "curl 'https://example.com/api' \\\n  -H 'accept: application/json' \\\n  --compressed";
        assertThat(ShellTokenizer.tokenize(raw))
                .containsExactly("curl", "https://example.com/api", "-H", "accept: application/json", "--compressed");
    }

    @Test
    @DisplayName("bash：CRLF 行尾的續行一樣要正確接續")
    void backslashLineContinuation_crlf() {
        String raw = "curl 'https://example.com/api' \\\r\n  --compressed";
        assertThat(ShellTokenizer.tokenize(raw)).containsExactly("curl", "https://example.com/api", "--compressed");
    }

    // ------------------------------------------------------------ cmd：雙引號

    @Test
    @DisplayName("cmd：雙引號字串，\\\" 是跳脫的引號")
    void doubleQuoted_escapedQuote() {
        String raw = "curl \"https://example.com/api\" -H \"content-type: application/json\" "
                + "--data-raw \"{\\\"foo\\\":\\\"bar\\\"}\"";
        List<String> tokens = ShellTokenizer.tokenize(raw);
        assertThat(tokens).containsExactly(
                "curl", "https://example.com/api",
                "-H", "content-type: application/json",
                "--data-raw", "{\"foo\":\"bar\"}");
    }

    @Test
    @DisplayName("cmd：行尾 ^ 接續下一行")
    void caretLineContinuation() {
        String raw = "curl \"https://example.com/api\" ^\r\n  -H \"accept: application/json\" ^\r\n  --compressed";
        assertThat(ShellTokenizer.tokenize(raw))
                .containsExactly("curl", "https://example.com/api", "-H", "accept: application/json", "--compressed");
    }

    @Test
    @DisplayName("cmd：未終止的雙引號拋錯")
    void unterminatedDoubleQuote_throws() {
        assertThatThrownBy(() -> ShellTokenizer.tokenize("curl \"https://example.com"))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------ 多個旗標

    @Test
    @DisplayName("多個 -H 各自成獨立 token 對")
    void multipleHeaderFlags() {
        String raw = "curl 'https://example.com' -H 'accept: application/json' "
                + "-H 'x-api-key: secret123' -H 'user-agent: test-agent'";
        assertThat(ShellTokenizer.tokenize(raw)).containsExactly(
                "curl", "https://example.com",
                "-H", "accept: application/json",
                "-H", "x-api-key: secret123",
                "-H", "user-agent: test-agent");
    }
}
