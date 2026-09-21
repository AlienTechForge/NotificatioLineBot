package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原始 message object 的連結白名單。見缺口 G5。
 *
 * <p>威脅模型：一組外洩的 {@code notify:raw} 金鑰若能夾帶任意連結，就是一個掛著
 * 官方帳號名義的釣魚訊息發送器。這組測試的每一項都對應一種真實的繞過手法。
 */
@DisplayName("UriHostValidator")
class UriHostValidatorTest {

    private static UriHostValidator withHosts(String csv) {
        return new UriHostValidator(new AppProperties("https://x", "", csv));
    }

    private static final UriHostValidator VALIDATOR = withHosts("example.com, example.org");

    private static List<Map<String, Object>> msg(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return List.of(m);
    }

    // ---------------------------------------------------------------- 放行

    @Test
    @DisplayName("白名單內的網域通過")
    void allowedHost_passes() {
        assertThatCode(() -> VALIDATOR.validate(
                msg("type", "text", "uri", "https://example.com/a/b?c=1")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("子網域通過（帶點號邊界）")
    void subdomain_passes() {
        assertThatCode(() -> VALIDATOR.validate(msg("uri", "https://docs.example.com/x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("大小寫不同的網域視為相同")
    void hostCaseInsensitive_passes() {
        assertThatCode(() -> VALIDATOR.validate(msg("uri", "https://EXAMPLE.COM/x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FQDN 的結尾點被正規化")
    void trailingDot_passes() {
        assertThatCode(() -> VALIDATOR.validate(msg("uri", "https://example.com./x")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "備份完成，耗時 42 秒。比例 3:1",
            "Warning: disk full",              // 冒號後有空白
            "Error:timeout after 30s",         // 冒號後沒空白
            "TODO: check the logs",
            "ratio 16:9",
            "09:30 開始",
    })
    @DisplayName("含冒號的普通文字不會被誤判成連結 —— 會擋下日常用法的安全控制註定被關掉")
    void plainTextWithColon_isNotTreatedAsUri(String text) {
        assertThatCode(() -> VALIDATOR.validate(msg("type", "text", "text", text)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("內嵌在文字中間的連結要掃到 —— LINE 會自動把它變成可點連結")
    void embeddedLink_isCaught() {
        assertThatThrownBy(() -> VALIDATOR.validate(
                msg("type", "text", "text", "部署失敗，詳見 https://evil.example.net/x 的紀錄")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.URI_HOST_NOT_ALLOWED);
    }

    @Test
    @DisplayName("同一段文字裡的第二個連結也要掃到")
    void secondEmbeddedLink_isCaught() {
        assertThatThrownBy(() -> VALIDATOR.validate(msg("text",
                "正常 https://example.com/a 與 https://evil.example.net/b")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("evil.example.net");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "詳見 https://example.com。",
            "詳見 https://example.com/x，稍後確認",
            "see https://example.com/x.",
            "see (https://example.com/x)",
            "「https://example.com」",
    })
    @DisplayName("連結後的中英文句尾標點不影響判定")
    void trailingPunctuation_doesNotBreakAllowedHost(String text) {
        assertThatCode(() -> VALIDATOR.validate(msg("text", text)))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------- 鍵名不是 uri

    @ParameterizedTest
    @ValueSource(strings = {
            "linkUri",            // imagemap 的 URI action
            "baseUrl",            // imagemap 本體
            "originalContentUrl", // 影音訊息
            "previewImageUrl",
            "thumbnailImageUrl",
            "iconUrl",            // Flex 的 icon
            "backgroundImage",
            "somethingCompletelyNew",
    })
    @DisplayName("連結不在 uri 欄位時「也要」被掃到 —— 列舉鍵名一定會漏")
    void linksInOtherKeys_areStillChecked(String key) {
        assertThatThrownBy(() -> VALIDATOR.validate(msg(key, "https://evil.example.net/phish")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.URI_HOST_NOT_ALLOWED);
    }

    @Test
    @DisplayName("深層巢狀在 Flex Message 內的連結仍被掃到")
    void deeplyNestedLink_isCaught() {
        List<Map<String, Object>> flex = List.of(Map.of(
                "type", "flex",
                "contents", Map.of(
                        "type", "bubble",
                        "body", Map.of(
                                "type", "box",
                                "contents", List.of(
                                        Map.of("type", "text", "text", "點我"),
                                        Map.of("type", "button", "action", Map.of(
                                                "type", "uri",
                                                "label", "前往",
                                                "uri", "https://evil.example.net/phish")))))));

        assertThatThrownBy(() -> VALIDATOR.validate(flex))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("evil.example.net");
    }

    @Test
    @DisplayName("陣列中的物件也被掃到")
    void linkInsideArray_isCaught() {
        assertThatThrownBy(() -> VALIDATOR.validate(
                msg("actions", List.of(Map.of("uri", "https://evil.example.net")))))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------ 繞過手法

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "blob:https://example.com/uuid",
            "jar:https://example.com/a.jar!/",
    })
    @DisplayName("永遠不該出現在訊息裡的 scheme 拒絕")
    void dangerousSchemes_rejected(String uri) {
        assertThatThrownBy(() -> VALIDATOR.validate(msg("uri", uri)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("scheme not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tel:+886912345678",
            "mailto:someone@example.com",
            "line://nv/camera",
    })
    @DisplayName("LINE 原生支援的 action scheme 放行 —— 它們不是釣魚連結的載體")
    void lineNativeSchemes_allowed(String uri) {
        assertThatCode(() -> VALIDATOR.validate(msg("uri", uri)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("取不到 host 的畸形 URI 拒絕，不是略過")
    void malformedUri_rejectedNotSkipped() {
        // 底線讓 URI.getHost() 回 null
        assertThatThrownBy(() -> VALIDATOR.validate(msg("uri", "https://exa_mple.com/x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("could not determine host");
    }

    @Test
    @DisplayName("無法解析的 URI 拒絕，不是略過")
    void unparseableUri_rejected() {
        assertThatThrownBy(() -> VALIDATOR.validate(msg("uri", "https://exa mple.com/ x")))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil-example.com/x",        // endsWith 會誤放行
            "https://example.com.attacker.net/x", // 後綴偽裝
            "https://notexample.com/x",
            "https://xexample.com/x",
    })
    @DisplayName("後綴偽裝的網域被擋 —— 比對不可用 endsWith")
    void suffixSpoofing_rejected(String uri) {
        assertThatThrownBy(() -> VALIDATOR.validate(msg("uri", uri)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("host not in allow-list");
    }

    @Test
    @DisplayName("帶 port 與 userinfo 的 URI 仍以 host 判定")
    void portAndUserInfo_useHostOnly() {
        assertThatCode(() -> VALIDATOR.validate(msg("uri", "https://example.com:8443/x")))
                .doesNotThrowAnyException();
        // userinfo 裡放白名單網域是經典繞過：https://example.com@evil.net
        assertThatThrownBy(() -> VALIDATOR.validate(msg("uri", "https://example.com@evil.net/x")))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------ 預設拒絕

    @Test
    @DisplayName("白名單為空：不允許任何外部連結（安全的預設值）")
    void emptyAllowList_deniesEverything() {
        UriHostValidator strict = withHosts("");

        assertThatThrownBy(() -> strict.validate(msg("uri", "https://example.com/x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("host not in allow-list");

        // 但純文字仍然可以
        assertThatCode(() -> strict.validate(msg("text", "沒有連結的訊息")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("巢狀過深時拒絕，不讓 stack overflow 變成 500")
    void excessiveNesting_rejected() {
        Object node = "https://example.com";
        for (int i = 0; i < 40; i++) {
            node = Map.of("contents", node);
        }
        Object deep = node;

        assertThatThrownBy(() -> VALIDATOR.validate(List.of((Map<String, Object>) deep)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("錯誤訊息指出是哪個連結被擋，並說明怎麼處理")
    void errorMessage_isActionable() {
        assertThatThrownBy(() -> VALIDATOR.validate(msg("uri", "https://evil.example.net/phish")))
                .hasMessageContaining("evil.example.net")
                .hasMessageContaining("app.allowed-uri-hosts");
    }

    @Test
    @DisplayName("設定值前後有空白也能正確比對")
    void allowListWhitespace_isTrimmed() {
        UriHostValidator v = withHosts("  example.com ,  example.org  ");

        assertThatCode(() -> v.validate(msg("uri", "https://example.org/x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AppProperties 的白名單解析：轉小寫、去空白、忽略空項")
    void appProperties_parsesAllowList() {
        assertThat(new AppProperties("", "", "Example.COM, , example.org ,").allowedUriHostList())
                .containsExactly("example.com", "example.org");
        assertThat(new AppProperties("", "", "").allowedUriHostList()).isEmpty();
        assertThat(new AppProperties("", "", null).allowedUriHostList()).isEmpty();
    }
}
