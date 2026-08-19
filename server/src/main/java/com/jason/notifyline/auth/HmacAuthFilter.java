package com.jason.notifyline.auth;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.client.ClientService;
import com.jason.notifyline.common.ApiErrorWriter;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * HMAC-SHA256 認證。規格見 {@code Docs/plan/03-權限與認證設計.md} §2。
 *
 * <p>驗證順序有意義 —— 先做便宜的檢查，避免對每個垃圾請求都去解密 secret：
 *
 * <ol>
 *   <li>必要 header 齊全？</li>
 *   <li>時間戳偏移在容忍範圍內？</li>
 *   <li>查 client（找不到時<strong>回傳與簽章錯誤相同的碼</strong>，避免被列舉）</li>
 *   <li>client 狀態是 ACTIVE？</li>
 *   <li>解密 secret → 重算簽章 → 常數時間比對</li>
 *   <li>寫入 nonce（唯一鍵衝突 = 重放）</li>
 *   <li>建立 principal</li>
 * </ol>
 *
 * <p><strong>步驟 6 放在簽章驗證之後是刻意的</strong>：若放前面，任何人都能用隨機
 * nonce 灌爆 {@code request_nonce} 表。放後面則必須先持有正確金鑰才寫得進去。
 */
@Component
public class HmacAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HmacAuthFilter.class);

    public static final String HEADER_CLIENT_ID = "X-Client-Id";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    /** 容忍呼叫端時鐘偏移。 */
    public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(300);

    /** request body 上限。body 會整包讀進記憶體以供驗簽，必須有上限。 */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final String PROTECTED_PREFIX = "/api/v1/";

    private final ClientRepository clientRepository;
    private final ClientService clientService;
    private final NonceStore nonceStore;
    private final ApiErrorWriter errorWriter;
    private final Clock clock;

    public HmacAuthFilter(ClientRepository clientRepository,
                          ClientService clientService,
                          NonceStore nonceStore,
                          ApiErrorWriter errorWriter,
                          Clock clock) {
        this.clientRepository = clientRepository;
        this.clientService = clientService;
        this.nonceStore = nonceStore;
        this.errorWriter = errorWriter;
        this.clock = clock;
    }

    /**
     * 只保護 {@code /api/v1/**}。
     *
     * <p>{@code /line/webhook} 有 LINE 自己的 x-line-signature 驗簽，
     * {@code /enroll/**} 的 token 本身即憑證 —— 兩者都不該被這個 filter 攔。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        CachedBodyHttpServletRequest cached;
        try {
            cached = new CachedBodyHttpServletRequest(request, MAX_BODY_BYTES);
        } catch (CachedBodyHttpServletRequest.RequestBodyTooLargeException e) {
            errorWriter.write(response, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Request body exceeds " + MAX_BODY_BYTES + " bytes.");
            return;
        }

        AuthResult result = authenticate(cached);
        if (!result.success()) {
            log.warn("認證失敗：code={} clientId={} path={} reason={}",
                    result.errorCode(), request.getHeader(HEADER_CLIENT_ID),
                    request.getRequestURI(), result.message());
            errorWriter.write(response, result.errorCode(), result.message());
            return;
        }

        ClientPrincipal principal = result.principal();
        MDC.put(RequestContext.CLIENT_ID, principal.clientId());
        cached.setAttribute(RequestContext.PRINCIPAL_ATTRIBUTE, principal);

        // 讓 Spring Security 的 authorizeHttpRequests 認得這是已驗證的請求
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, AuthorityUtils.NO_AUTHORITIES));

        try {
            chain.doFilter(cached, response);
        } finally {
            SecurityContextHolder.clearContext();
            MDC.remove(RequestContext.CLIENT_ID);
        }
    }

    private AuthResult authenticate(CachedBodyHttpServletRequest request) {
        // ── 1. 必要 header ───────────────────────────────────────────────
        String clientId = request.getHeader(HEADER_CLIENT_ID);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);

        if (isBlank(clientId) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return AuthResult.failure(ErrorCode.AUTH_MISSING_HEADER,
                    "Missing one of: " + HEADER_CLIENT_ID + ", " + HEADER_TIMESTAMP
                            + ", " + HEADER_NONCE + ", " + HEADER_SIGNATURE + ".");
        }

        // ── 2. 時間戳 ────────────────────────────────────────────────────
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return AuthResult.failure(ErrorCode.AUTH_TIMESTAMP_SKEW,
                    HEADER_TIMESTAMP + " must be epoch seconds.");
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - epochSeconds);
        if (skew > MAX_CLOCK_SKEW.toSeconds()) {
            // 訊息要能讓對方自行診斷 —— 回應的 Date header 就是伺服器時間
            return AuthResult.failure(ErrorCode.AUTH_TIMESTAMP_SKEW,
                    "Timestamp differs from server time by " + skew + "s (max "
                            + MAX_CLOCK_SKEW.toSeconds() + "s). Check your system clock against the Date header.");
        }

        // ── 3~4. 查 client ───────────────────────────────────────────────
        Optional<Client> found = clientRepository.findByClientId(clientId);
        if (found.isEmpty()) {
            // 刻意與簽章錯誤同碼，避免攻擊者列舉出哪些 client id 存在
            return AuthResult.failure(ErrorCode.AUTH_INVALID_SIGNATURE, "Invalid signature.");
        }
        Client client = found.get();
        if (!client.isUsable()) {
            return AuthResult.failure(ErrorCode.AUTH_CLIENT_DISABLED,
                    "This client credential has been disabled or revoked.");
        }

        // ── 5. 驗簽 ──────────────────────────────────────────────────────
        String canonical;
        try {
            canonical = CanonicalRequest.of(
                    request.getMethod(),
                    request.getRequestURI(),
                    timestamp.trim(),
                    nonce.trim(),
                    request.getCachedBody()).toCanonicalString();
        } catch (IllegalArgumentException e) {
            return AuthResult.failure(ErrorCode.AUTH_INVALID_SIGNATURE, "Invalid signature.");
        }

        String secret;
        try {
            secret = clientService.decryptSecret(client);
        } catch (SecretCipherException e) {
            // 解不開代表加密 key 設定錯誤或資料損毀 —— 是伺服器端問題，要留下痕跡
            log.error("無法解密 client secret：clientId={}", clientId, e);
            return AuthResult.failure(ErrorCode.AUTH_INVALID_SIGNATURE, "Invalid signature.");
        }

        if (!HmacSigner.verify(secret, canonical, signature.trim())) {
            return AuthResult.failure(ErrorCode.AUTH_INVALID_SIGNATURE, "Invalid signature.");
        }

        // ── 6. nonce ─────────────────────────────────────────────────────
        if (!nonceStore.tryConsume(nonce.trim(), client.getId())) {
            return AuthResult.failure(ErrorCode.AUTH_NONCE_REPLAY,
                    "This nonce has already been used. Generate a fresh nonce for every request.");
        }

        // ── 7. principal ─────────────────────────────────────────────────
        return AuthResult.success(ClientPrincipal.from(client));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AuthResult(boolean success, ClientPrincipal principal,
                              ErrorCode errorCode, String message) {

        static AuthResult success(ClientPrincipal principal) {
            return new AuthResult(true, principal, null, null);
        }

        static AuthResult failure(ErrorCode code, String message) {
            return new AuthResult(false, null, code, message);
        }
    }
}
