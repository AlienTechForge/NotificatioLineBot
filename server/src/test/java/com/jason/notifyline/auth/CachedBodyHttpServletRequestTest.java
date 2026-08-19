package com.jason.notifyline.auth;

import com.jason.notifyline.client.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Body 快取與 {@link ClientPrincipal}。
 *
 * <p>驗簽需要原始 body，controller 需要反序列化後的物件 —— 但
 * {@code HttpServletRequest} 的 InputStream 只能讀一次。
 */
@DisplayName("CachedBodyHttpServletRequest / ClientPrincipal")
class CachedBodyHttpServletRequestTest {

    private static final String BODY = "{\"hello\":\"世界\"}";

    private static CachedBodyHttpServletRequest wrap(String body, int maxBytes) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return new CachedBodyHttpServletRequest(request, maxBytes);
    }

    @Test
    @DisplayName("body 可以被讀「兩次」—— 一次驗簽、一次反序列化")
    void bodyCanBeReadTwice() throws Exception {
        CachedBodyHttpServletRequest request = wrap(BODY, 1024);

        byte[] first = request.getCachedBody();
        String second = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String third = request.getReader().readLine();

        assertThat(new String(first, StandardCharsets.UTF_8)).isEqualTo(BODY);
        assertThat(second).isEqualTo(BODY);
        assertThat(third).isEqualTo(BODY);
    }

    @Test
    @DisplayName("getCachedBody 回傳副本，下游改不到內部狀態")
    void cachedBodyIsDefensivelyCopied() throws Exception {
        CachedBodyHttpServletRequest request = wrap(BODY, 1024);

        byte[] body = request.getCachedBody();
        body[0] = 'X';

        assertThat(request.getCachedBody()[0]).isNotEqualTo((byte) 'X');
    }

    @Test
    @DisplayName("UTF-8 內容不因快取而失真 —— 驗簽對 bytes 敏感")
    void utf8ContentIsPreservedByteForByte() throws Exception {
        CachedBodyHttpServletRequest request = wrap(BODY, 1024);

        assertThat(request.getCachedBody()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空 body 也能正常處理")
    void emptyBodyIsSupported() throws Exception {
        assertThat(wrap("", 1024).getCachedBody()).isEmpty();
    }

    @Test
    @DisplayName("超過上限時拒絕，不會先把整包吃進記憶體")
    void oversizedBodyIsRejected() {
        assertThatThrownBy(() -> wrap("a".repeat(2048), 1024))
                .isInstanceOf(CachedBodyHttpServletRequest.RequestBodyTooLargeException.class)
                .hasMessageContaining("1024");
    }

    @Test
    @DisplayName("ServletInputStream 的狀態方法行為正確")
    void inputStreamStateMethods() throws Exception {
        var stream = wrap("ab", 1024).getInputStream();

        assertThat(stream.isReady()).isTrue();
        assertThat(stream.isFinished()).isFalse();
        stream.readAllBytes();
        assertThat(stream.isFinished()).isTrue();

        // 已快取的 body 不支援非同步讀取；靜默忽略會讓錯誤延後爆發
        assertThatThrownBy(() -> stream.setReadListener(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------- ClientPrincipal

    @Test
    @DisplayName("ClientPrincipal：scope 判斷與綁定狀態")
    void clientPrincipal_scopesAndBinding() {
        ClientPrincipal bound = new ClientPrincipal(
                1L, "cli_x", "U000", Set.of(Scope.NOTIFY_SELF), null, null);
        ClientPrincipal service = new ClientPrincipal(
                2L, "cli_y", null, Set.of(Scope.NOTIFY_OWNER), 30, 100);

        assertThat(bound.isBound()).isTrue();
        assertThat(bound.hasScope(Scope.NOTIFY_SELF)).isTrue();
        assertThat(bound.hasScope(Scope.NOTIFY_ALL)).isFalse();

        assertThat(service.isBound()).isFalse();
        assertThat(service.rateLimitPerMin()).isEqualTo(30);
        assertThat(service.dailyMessageQuota()).isEqualTo(100);
    }

    @Test
    @DisplayName("ClientPrincipal：scopes 對外唯讀，空集合也不例外")
    void clientPrincipal_scopesAreImmutable() {
        ClientPrincipal empty = new ClientPrincipal(1L, "cli_x", null, Set.of(), null, null);

        assertThat(empty.scopes()).isEmpty();
        assertThatThrownBy(() -> empty.scopes().add(Scope.NOTIFY_ALL))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
