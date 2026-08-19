package com.jason.notifyline.config;

import com.jason.notifyline.auth.HmacAuthFilter;
import com.jason.notifyline.auth.RateLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 路徑分流。設計見 {@code Docs/plan/03-權限與認證設計.md} §6。
 *
 * <p>Phase 2 會加上第二條 chain（{@code /admin/**}，LINE Login + session，
 * 見 ADR-0009），兩者以 {@code securityMatcher} 分流。<strong>那條 chain 必須
 * 重新啟用 CSRF</strong> —— 有 cookie 就有 CSRF 風險，而這條 API chain 沒有。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 這些路徑有自己的驗證機制或本來就該公開。 */
    static final String[] PUBLIC_PATHS = {
            // LINE 用自己的 x-line-signature 驗簽（由 SDK 處理），
            // 絕不可套用我們對呼叫端的 HMAC filter，否則 webhook 永遠收不到。
            "/line/webhook",
            // 一次性連結，token 本身即憑證
            "/enroll/**",
            // 供 Docker healthcheck 與部署驗證
            "/actuator/health",
            "/actuator/health/**",
    };

    private final HmacAuthFilter hmacAuthFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(HmacAuthFilter hmacAuthFilter, RateLimitFilter rateLimitFilter) {
        this.hmacAuthFilter = hmacAuthFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 無 cookie、無 session，CSRF 不適用。
                // Phase 2 的 /admin/** chain 必須「重新啟用」CSRF。
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(hmacAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, HmacAuthFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Spring Security 6+ 預設讓所有 dispatcher type 都經過 filter chain，
                        // 包含容器轉發到 /error 的 ERROR dispatch。不放行的話，任何
                        // 404/410/400 都會在錯誤轉發時被 denyAll 攔下，對外變成 403。
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 身分由 HmacAuthFilter 建立；到得了這裡就代表驗簽通過
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * 兩個 filter 都已經是 {@code @Component}，Spring Boot 預設會把它們額外註冊到
     * servlet container 上，變成「每個請求跑兩次」。這裡明確關掉自動註冊，
     * 只保留 Security chain 內的那一份。
     *
     * <p>症狀很隱晦：nonce 會在同一個請求內被消費兩次，第二次判定為重放，
     * 於是<strong>每個正確的請求都回 401 nonce replay</strong>。
     */
    @Bean
    FilterRegistrationBean<HmacAuthFilter> disableHmacAutoRegistration(HmacAuthFilter filter) {
        FilterRegistrationBean<HmacAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> disableRateLimitAutoRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
