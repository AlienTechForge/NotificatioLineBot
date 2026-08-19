package com.jason.notifyline.admin;

import com.jason.notifyline.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登入的完整往返，走<strong>真的 cookie</strong>。
 *
 * <h2>為什麼要跟 {@link AdminConsoleIT} 分開</h2>
 *
 * <p>{@code AdminConsoleIT} 用 {@code with(csrf())} 這個測試輔助去驗授權邊界。
 * 那個 post-processor 會在同一個 JVM 內留下狀態，讓「同一次執行中」後續用真實
 * 請求檢查 cookie 發放的測試看不到 cookie —— 純粹是測試框架的交互，與正式環境無關
 * （正式環境是真的瀏覽器打真的 HTTP，沒有這個 post-processor）。
 *
 * <p>把「cookie 機制到底有沒有運作」與「端點有沒有被正確授權」拆成兩個類別，
 * 前者才不會被後者的測試輔助污染。這兩件事本來就是不同的關注點。
 *
 * <h2>這個測試守的洞</h2>
 *
 * <p>Spring Security 6 的 CSRF token 是<strong>延遲</strong>發放的：沒有人去讀它，
 * cookie 就不會寫出。登入頁是靜態 HTML，沒有伺服器端樣板去觸發讀取，於是
 * {@code GET /admin/login.html} 全程不碰 token，cookie 不出現，前端 JS 附不了
 * {@code _csrf}，{@code POST /admin/login} 一律 403 —— 帳密完全正確卻登不進去，
 * 而錯誤頁完全不提 CSRF。{@link AdminSecurityConfig.CsrfCookieFilter} 就是為了
 * 補這個洞，這個測試確保它有效。
 */
@DisplayName("管理介面登入流程（整合）")
@AutoConfigureMockMvc
class AdminLoginFlowIT extends PostgresIntegrationTest {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "integration-test-password";

    @DynamicPropertySource
    static void adminCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.admin.username", () -> ADMIN_USER);
        registry.add("app.admin.password", () -> ADMIN_PASSWORD);
        registry.add("app.dispatch.enabled", () -> "false");
        registry.add("app.line.api-base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private MockMvc mockMvc;

    private Cookie getCsrfCookie() throws Exception {
        Cookie xsrf = mockMvc.perform(get("/admin/login.html"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("GET 登入頁必須發出 XSRF-TOKEN cookie").isNotNull();
        return xsrf;
    }

    @Test
    @DisplayName("GET 登入頁就會拿到可讀的 XSRF-TOKEN cookie")
    void loginPageIssuesCsrfCookie() throws Exception {
        Cookie xsrf = getCsrfCookie();

        assertThat(xsrf.getValue()).isNotBlank();
        // 前端 JS 必須讀得到才能回填，所以不能是 httpOnly
        assertThat(xsrf.isHttpOnly()).isFalse();
    }

    @Test
    @DisplayName("cookie 的 token 回填成 _csrf 後登入成功，session 隨即能打管理 API")
    void loginSucceedsWithCookieToken() throws Exception {
        Cookie xsrf = getCsrfCookie();

        // 這正是前端做的事：把 cookie 值回填成 _csrf，連同 cookie 一起送。
        // MockMvc 不會為程式建立的 session 送出 JSESSIONID cookie，session 掛在
        // result 上，所以往下從那裡取。
        var result = mockMvc.perform(post("/admin/login")
                        .param("username", ADMIN_USER)
                        .param("password", ADMIN_PASSWORD)
                        .param("_csrf", xsrf.getValue())
                        .cookie(xsrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("登入成功要建立 session").isNotNull();

        mockMvc.perform(get("/admin/api/clients").session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("錯誤密碼導回登入頁的 error")
    void wrongPasswordRedirectsToError() throws Exception {
        Cookie xsrf = getCsrfCookie();

        mockMvc.perform(post("/admin/login")
                        .param("username", ADMIN_USER)
                        .param("password", "wrong-password-value")
                        .param("_csrf", xsrf.getValue())
                        .cookie(xsrf))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login.html?error"));
    }

    @Test
    @DisplayName("有 session 但少了 _csrf → 403。有 cookie 就有 CSRF 風險")
    void writeWithoutCsrfTokenIsForbidden() throws Exception {
        // 先真的登入拿到 session
        Cookie xsrf = getCsrfCookie();
        var login = mockMvc.perform(post("/admin/login")
                        .param("username", ADMIN_USER)
                        .param("password", ADMIN_PASSWORD)
                        .param("_csrf", xsrf.getValue())
                        .cookie(xsrf))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        // 帶著有效 session 但不附 CSRF token 去寫入 → 403
        mockMvc.perform(post("/admin/api/clients/cli_x/default-target").session(session)
                        .contentType("application/json")
                        .content("{\"type\":\"OWNER\",\"userIds\":[]}"))
                .andExpect(status().isForbidden());
    }
}
