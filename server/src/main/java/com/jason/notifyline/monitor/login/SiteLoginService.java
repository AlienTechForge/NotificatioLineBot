package com.jason.notifyline.monitor.login;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 取得可用的 token 並組出要注入的 header。見 {@code Docs/plan/15-監控站台登入設計.md} §5。
 *
 * <h2>流程</h2>
 *
 * <pre>
 * 1. SELECT ... FOR UPDATE 鎖住這一列
 * 2. 快取仍有效（扣掉安全邊際）        → 直接用
 * 3. 有 refresh token                 → REFRESH_TOKEN_AUTH
 * 4. 沒有，或 refresh 失敗            → 完整 SRP 登入
 * 5. 寫回加密後的 token 與到期時間
 * </pre>
 *
 * <h2>為什麼敢在交易裡打網路</h2>
 *
 * <p>這個方法握著資料庫的列鎖去做 HTTP —— 一般來說是要避免的模式。這裡是刻意的：
 * 序列化登入正是目的，而呼叫有 {@code readTimeout} 上限（預設 10 秒）。代價是同一組
 * 登入到期時，等待的監控會排隊；好處是對方永遠只看到一次登入，不會誤判為暴力嘗試。
 * 登入設定的數量是個位數，連線池不會因此枯竭。
 *
 * <h2>機密的流向</h2>
 *
 * <p>密碼、refresh token、idToken 的明文只存在於這個類別的方法堆疊內。
 * <strong>不記錄、不回傳、不放進任何 DTO</strong>；離開這裡的只有
 * {@link ResolvedLoginHeader}，而它會直接交給 {@code ApiFetcher}。
 */
@Service
public class SiteLoginService {

    private static final Logger log = LoggerFactory.getLogger(SiteLoginService.class);

    private static final String AAD_PREFIX = "monitor_login:";

    /**
     * token 只剩這麼多秒時就當作已過期。
     *
     * <p>沒有這個邊際，會出現「檢查時還有 1 秒、送到對方手上已過期」的間歇性 401 ——
     * 那種失敗長得像對方不穩定，其實是我們自己算得太剛好。
     */
    private static final int EXPIRY_SKEW_SECONDS = 120;

    private final MonitorLoginRepository repository;
    private final CognitoClient cognitoClient;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public SiteLoginService(MonitorLoginRepository repository,
                            CognitoClient cognitoClient,
                            SecretCipher secretCipher,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.repository = repository;
        this.cognitoClient = cognitoClient;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.secureRandom = new SecureRandom();
        this.clock = clock;
    }

    /**
     * 取得這組登入現在該注入的 header。
     *
     * @throws CognitoAuthException 登入失敗；{@link CognitoAuthException.Reason#permanent()}
     *                              為 true 時這組登入已經被停用（呼叫端負責通知使用者）
     */
    @Transactional
    public ResolvedLoginHeader resolve(Long loginId) {
        MonitorLogin login = repository.findByIdForUpdate(loginId)
                .orElseThrow(() -> new CognitoAuthException(
                        CognitoAuthException.Reason.CONFIGURATION, "登入設定不存在: " + loginId));

        if (!login.isEnabled()) {
            throw new CognitoAuthException(CognitoAuthException.Reason.CONFIGURATION,
                    "登入設定已停用: " + login.getName());
        }

        Instant now = clock.instant();
        if (login.hasFreshToken(now, EXPIRY_SKEW_SECONDS)) {
            return header(login, decrypt(login.getTokenCiphertext(), login.getTokenIv(),
                    login.getTokenKeyVersion(), aad(login, "token")));
        }

        try {
            String idToken = obtainToken(login, now);
            return header(login, idToken);
        } catch (CognitoAuthException e) {
            recordFailure(login, e, now);
            throw e;
        }
    }

    /**
     * 這組登入現在能不能用（後台「測試登入」）。強制走一次真的登入以確認帳密正確，
     * 不吃快取——否則按了等於什麼都沒驗證。
     *
     * @return token 的到期時間
     */
    @Transactional
    public Instant verify(Long loginId) {
        MonitorLogin login = repository.findByIdForUpdate(loginId)
                .orElseThrow(() -> new CognitoAuthException(
                        CognitoAuthException.Reason.CONFIGURATION, "登入設定不存在: " + loginId));

        Instant now = clock.instant();
        try {
            fullLogin(login, endpointOf(login), now);
            return login.getTokenExpiresAt();
        } catch (CognitoAuthException e) {
            recordFailure(login, e, now);
            throw e;
        }
    }

    // ------------------------------------------------------------------ 內部

    /** refresh 優先，失敗才走完整登入。回傳明文 idToken。 */
    private String obtainToken(MonitorLogin login, Instant now) {
        CognitoEndpoint endpoint = endpointOf(login);

        if (login.hasRefreshToken()) {
            try {
                String refreshToken = decrypt(login.getRefreshCiphertext(), login.getRefreshIv(),
                        login.getRefreshKeyVersion(), aad(login, "refresh"));
                AuthTokens tokens = cognitoClient.refresh(endpoint, refreshToken);
                storeTokens(login, tokens, now);
                return tokens.idToken();
            } catch (CognitoAuthException e) {
                // refresh token 過期時 Cognito 回的也是 NotAuthorizedException，與密碼錯
                // 同一個型別。但這裡的上下文明確：這是 refresh 失敗，不是帳密錯，
                // 退回完整登入才是對的，停用會是嚴重誤判。
                if (e.reason() == CognitoAuthException.Reason.INVALID_CREDENTIALS) {
                    log.info("login={} refresh token 已失效，改走完整登入", login.getName());
                } else {
                    throw e;
                }
            }
        }
        fullLogin(login, endpoint, now);
        return decrypt(login.getTokenCiphertext(), login.getTokenIv(),
                login.getTokenKeyVersion(), aad(login, "token"));
    }

    /**
     * 完整 SRP 登入，結果直接寫進 {@code login}。
     *
     * <h2>salt 編碼的一次重試</h2>
     *
     * <p>Cognito 送來的 salt hex 以 {@code 00} 開頭、且下一個位元組最高位元為 0 時，
     * 「保留前導零」與「BigInteger 最小表示」兩種解讀會產生不同的 x，因而不同的簽章
     * ——只有一種是對的，而我們無法事先知道是哪一種（見 {@link CognitoSrp} 類別註解）。
     * 約 1/512 的帳號會踩到。
     *
     * <p>與其擲骰子，不如在那個情況下換另一種再試一次：兩次嘗試遠低於任何鎖定門檻，
     * 卻把「這個帳號永遠登不進去」變成「自動走通」。不具歧義時完全不會有第二次嘗試。
     */
    private void fullLogin(MonitorLogin login, CognitoEndpoint endpoint, Instant now) {
        String password = decrypt(login.getPasswordCiphertext(), login.getPasswordIv(),
                login.getPasswordKeyVersion(), aad(login, "password"));

        try {
            attemptLogin(login, endpoint, password, CognitoSrp.SaltEncoding.PRESERVE, now);
        } catch (CognitoAuthException e) {
            if (e.reason() != CognitoAuthException.Reason.INVALID_CREDENTIALS) {
                throw e;
            }
            log.info("login={} 以 PRESERVE 編碼登入失敗，檢查 salt 是否具歧義", login.getName());
            attemptLogin(login, endpoint, password, CognitoSrp.SaltEncoding.MINIMAL, now);
        }
    }

    private void attemptLogin(MonitorLogin login,
                              CognitoEndpoint endpoint,
                              String password,
                              CognitoSrp.SaltEncoding saltEncoding,
                              Instant now) {
        CognitoSrp.KeyPair keyPair = CognitoSrp.generateKeyPair(secureRandom);
        SrpChallenge challenge = cognitoClient.initiateSrp(endpoint, login.getUsername(), keyPair.bigAHex());

        if (saltEncoding == CognitoSrp.SaltEncoding.MINIMAL && !saltIsAmbiguous(challenge.saltHex())) {
            // salt 沒有歧義 → 第二種編碼算出來會和第一次一模一樣，重試沒有意義，
            // 只會多送一次錯誤密碼。直接把原本的失敗還原成正確的結論。
            throw new CognitoAuthException(CognitoAuthException.Reason.INVALID_CREDENTIALS,
                    "帳號或密碼不正確");
        }

        byte[] derivedKey = CognitoSrp.passwordAuthenticationKey(
                endpoint.poolName(), challenge.userIdForSrp(), password,
                keyPair, new BigInteger(challenge.srpBHex(), 16), challenge.saltHex(), saltEncoding);

        String timestamp = CognitoSrp.timestamp(now);
        String signature = CognitoSrp.passwordClaimSignature(
                derivedKey, endpoint.poolName(), challenge.userIdForSrp(),
                Base64.getDecoder().decode(challenge.secretBlock()), timestamp);

        AuthTokens tokens = cognitoClient.respondToPasswordVerifier(
                endpoint, challenge.userIdForSrp(), challenge.secretBlock(), signature, timestamp);

        storeTokens(login, tokens, now);
    }

    /** 兩種 salt 編碼會不會算出不同結果。見 {@link #fullLogin} 的說明。 */
    private static boolean saltIsAmbiguous(String saltHex) {
        return !CognitoSrp.padHex(saltHex).equals(CognitoSrp.padHex(new BigInteger(saltHex, 16)));
    }

    private void storeTokens(MonitorLogin login, AuthTokens tokens, Instant now) {
        Instant expiresAt = JwtExpiry.parse(tokens.idToken())
                .orElseGet(() -> MonitorLogin.fallbackExpiry(now, tokens.expiresInSeconds()));

        EncryptedSecret token = secretCipher.encrypt(tokens.idToken(), aad(login, "token"));
        EncryptedSecret refresh = tokens.refreshToken() == null
                ? null
                : secretCipher.encrypt(tokens.refreshToken(), aad(login, "refresh"));

        login.applyTokens(
                token.ciphertext(), token.iv(), token.keyVersion(), expiresAt,
                refresh == null ? null : refresh.ciphertext(),
                refresh == null ? null : refresh.iv(),
                refresh == null ? null : refresh.keyVersion(),
                now);
        repository.save(login);
    }

    /**
     * 永久性失敗停用、暫時性失敗只計數。見 {@code Docs/plan/15-監控站台登入設計.md} §5.1。
     */
    private void recordFailure(MonitorLogin login, CognitoAuthException e, Instant now) {
        if (e.reason().permanent()) {
            log.warn("login={} 永久性登入失敗（{}），已停用", login.getName(), e.reason());
            login.disableAfterPermanentFailure(e.reason() + ": " + e.getMessage(), now);
        } else {
            log.warn("login={} 暫時性登入失敗（{}）", login.getName(), e.reason());
            login.recordTransientFailure(e.reason() + ": " + e.getMessage(), now);
        }
        repository.save(login);
    }

    private ResolvedLoginHeader header(MonitorLogin login, String idToken) {
        return new ResolvedLoginHeader(login.getHeaderName(),
                login.getHeaderValueTemplate().replace("{token}", idToken));
    }

    private CognitoEndpoint endpointOf(MonitorLogin login) {
        try {
            JsonNode config = objectMapper.readTree(login.getConfig());
            return new CognitoEndpoint(
                    textOf(config, "region"),
                    textOf(config, "userPoolId"),
                    textOf(config, "clientId"));
        } catch (RuntimeException e) {
            throw new CognitoAuthException(CognitoAuthException.Reason.CONFIGURATION,
                    "登入設定內容不正確: " + e.getMessage(), e);
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asString();
    }

    private String decrypt(byte[] ciphertext, byte[] iv, Integer keyVersion, String aad) {
        if (ciphertext == null || iv == null || keyVersion == null) {
            throw new CognitoAuthException(CognitoAuthException.Reason.CONFIGURATION,
                    "缺少必要的加密欄位");
        }
        return secretCipher.decrypt(ciphertext, iv, keyVersion, aad);
    }

    /** AAD = {@code monitor_login:{id}:{欄位}}，見 {@link MonitorLogin} 的 AAD 陷阱。 */
    private static String aad(MonitorLogin login, String field) {
        return AAD_PREFIX + login.getId() + ":" + field;
    }

    /** 後台建立／更新密碼時共用的加密進入點。呼叫前 {@code login} 必須已經有 id。 */
    public void encryptPasswordInto(MonitorLogin login, String plaintext) {
        Optional.ofNullable(login.getId()).orElseThrow(() -> new IllegalStateException(
                "必須先 save() 取得 id 才能加密密碼——AAD 用的就是那個 id"));
        EncryptedSecret encrypted = secretCipher.encrypt(plaintext, aad(login, "password"));
        login.applyPassword(encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), clock.instant());
    }
}
