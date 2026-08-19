package com.jason.notifyline.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import org.springframework.http.HttpStatus;

/**
 * 管理介面的認證。與 API 的 HMAC 完全分離的第二條 filter chain。
 *
 * <h2>三件容易出事的設定</h2>
 *
 * <p><strong>1. CSRF 必須開著。</strong> API chain 關掉 CSRF 是因為它無 cookie、無
 * session —— 沒有「瀏覽器會自動附上憑證」這回事，CSRF 就不成立。這條 chain 用 session
 * cookie，前提整個反過來：任何網站都能讓你的瀏覽器對這裡發出帶 cookie 的請求。
 * 照抄 API chain 的 {@code csrf.disable()} 會讓管理後台變成一個 CSRF 靶子。
 *
 * <p><strong>2. 這條 chain 必須排在 API chain 前面</strong>（{@code @Order}），
 * 並用 {@code securityMatcher} 限定路徑。順序錯了，{@code /admin/**} 會被
 * API chain 先接手，然後因為沒有 HMAC header 而回 401 —— 登入頁永遠打不開。
 *
 * <p><strong>3. 沒設定帳密時整個 chain 不註冊。</strong> 見
 * {@link AdminAuthProperties}：忘記設定的後果是「後台不存在」，不是「後台沒密碼」。
 *
 * <p>條件用 {@code @ConditionalOnExpression} 而不是 {@code @ConditionalOnProperty}：
 * {@code application.yml} 裡寫的是 {@code ${APP_ADMIN_USERNAME:}}，沒設環境變數時
 * 該屬性<strong>存在但為空字串</strong>。{@code @ConditionalOnProperty} 認定
 * 「存在即成立」，於是條件通過、bean 帶著空密碼被建立，應用直接啟動失敗。
 */
@Configuration
@ConditionalOnExpression("'${app.admin.username:}'.trim().length() > 0")
public class AdminSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityConfig.class);

    /** 管理者只有一個人，用固定的角色名即可。 */
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * bcrypt。密碼從環境變數進來是明文，但<strong>只在啟動時存在</strong> ——
     * 立刻雜湊，記憶體裡不留明文，比對走 bcrypt 自身的常數時間實作。
     */
    @Bean
    PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService adminUserDetailsService(AdminAuthProperties properties,
                                               PasswordEncoder encoder) {
        properties.validate();
        log.info("管理介面已啟用：帳號={}", properties.username());

        return new InMemoryUserDetailsManager(
                User.withUsername(properties.username())
                        .password(encoder.encode(properties.password()))
                        .roles(ROLE_ADMIN)
                        .build());
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                 UserDetailsService adminUserDetailsService)
            throws Exception {

        // XSRF-TOKEN cookie 讓前端讀得到 token 並回填成 X-XSRF-TOKEN header。
        // httpOnly 必須是 false —— 前端 JS 讀不到就沒辦法附上，所有寫入請求都會 403。
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();

        return http
                .securityMatcher("/admin/**")
                .userDetailsService(adminUserDetailsService)
                .csrf(c -> c
                        .csrfTokenRepository(csrf)
                        // 用原始 token（非 XOR 遮罩版），前端才能直接把 cookie 值
                        // 回填成 header/欄位
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                // 見 CsrfCookieFilter 的說明：少了它，cookie 永遠不會被寫出
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/login", "/admin/login.html",
                                "/admin/assets/**").permitAll()
                        .anyRequest().hasRole(ROLE_ADMIN))
                .formLogin(form -> form
                        .loginPage("/admin/login.html")
                        .loginProcessingUrl("/admin/login")
                        .defaultSuccessUrl("/admin/", true)
                        .failureUrl("/admin/login.html?error"))
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login.html?logout")
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(e -> e
                        // XHR 未登入時要拿到 401 而不是登入頁的 HTML，
                        // 否則前端會把整頁 HTML 當成 JSON 去解析而爆出無關的錯誤
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                r -> r.getRequestURI().startsWith("/admin/api/")))
                .build();
    }

    /**
     * 強制把 CSRF token 寫進 cookie。
     *
     * <p><strong>沒有這個 filter，整個登入流程是壞的。</strong>
     * Spring Security 6 的 {@code CsrfFilter} 改成<em>延遲</em>解析 token：
     * token 只有在「真的有人去讀它」的時候才會產生，也才會被
     * {@link CookieCsrfTokenRepository} 寫進回應的 cookie。
     *
     * <p>而我們的登入頁是<strong>靜態 HTML</strong>，沒有伺服器端樣板去讀
     * {@code ${_csrf}}。於是 {@code GET /admin/login.html} 全程沒有人碰過 token，
     * cookie 不會出現，前端的 JS 找不到值就不附 {@code _csrf}，
     * {@code POST /admin/login} 一律 403。
     *
     * <p>症狀特別難查：帳密完全正確，回應卻是 403，而錯誤頁完全沒有提到 CSRF。
     *
     * <p>{@code getToken()} 這行看起來像沒有作用的呼叫，實際上正是它觸發
     * token 產生與寫入 cookie。不要「清理」掉。
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {

            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
