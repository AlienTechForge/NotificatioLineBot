package com.jason.notifyline.monitor.login;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 取得可用的 token 並組出要注入的 header。見 {@code Docs/plan/15-監控站台登入設計.md} §5。
 *
 * <h2>流程</h2>
 *
 * <pre>
 * 1. 取得這筆登入的行程內鎖（序列化，見下）
 * 2. store.loadForUse()      短交易：讀出並解密快取的 token / refresh token
 * 3. 快取仍新鮮 → 直接用，完全不碰網路
 * 4. 有 refresh token → REFRESH_TOKEN_AUTH        ┐
 * 5. 沒有，或 refresh 失效 → 完整 SRP 登入         ┴ 無交易
 * 6. store.storeTokens()     短交易：寫回
 * </pre>
 *
 * <h2>這個類別沒有 {@code @Transactional} —— 那是刻意的</h2>
 *
 * <p>網路呼叫絕不可以在交易裡，理由見 {@link MonitorLoginStore} 的類別註解
 * （連線池、以及第一版真的踩到的兩個線上錯誤）。所有資料庫存取都經過那個類別的
 * 短交易方法。
 *
 * <h2>序列化：行程內鎖，不是資料庫的 FOR UPDATE</h2>
 *
 * <p>多個監控可能同時到期。沒有序列化就是 N 個執行緒同時對同一組帳號發起登入 ——
 * 浪費之外，更實際的風險是對方把這看成暴力嘗試。
 *
 * <p>第一版用 {@code SELECT ... FOR UPDATE} 達成，但那需要交易橫跨整個網路呼叫，
 * 正是上面說不能做的事。改用每筆登入一把 {@link ReentrantLock}：
 *
 * <ul>
 *   <li><strong>前提</strong>：單一應用程式實例（現在的 docker compose 就是一個
 *       {@code app} 容器）。要橫向擴充成多實例時，這裡必須換成資料庫或 Redis 的
 *       分散式鎖 —— 這是這個設計唯一的假設，寫在這裡以免日後擴充時被靜默地破壞。</li>
 *   <li>鎖的數量以登入設定的筆數為上限（個位數），不會無限增長。</li>
 * </ul>
 */
@Service
public class SiteLoginService {

    private static final Logger log = LoggerFactory.getLogger(SiteLoginService.class);

    /**
     * token 只剩這麼多秒時就當作已過期。
     *
     * <p>沒有這個邊際，會出現「檢查時還有 1 秒、送到對方手上已過期」的間歇性 401 ——
     * 那種失敗長得像對方不穩定，其實是我們自己算得太剛好。
     */
    private static final int EXPIRY_SKEW_SECONDS = 120;

    private final MonitorLoginStore store;
    private final CognitoClient cognitoClient;
    private final SecureRandom secureRandom;
    private final Clock clock;

    /** 每筆登入一把鎖，見類別註解。 */
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SiteLoginService(MonitorLoginStore store, CognitoClient cognitoClient, Clock clock) {
        this.store = store;
        this.cognitoClient = cognitoClient;
        this.secureRandom = new SecureRandom();
        this.clock = clock;
    }

    /**
     * 取得這組登入現在該注入的 header。
     *
     * @throws CognitoAuthException 登入失敗；{@link CognitoAuthException.Reason#permanent()}
     *                              為 true 時這組登入已經被停用（呼叫端負責通知使用者）
     */
    public ResolvedLoginHeader resolve(Long loginId) {
        ReentrantLock lock = lockFor(loginId);
        lock.lock();
        try {
            LoginSnapshot snapshot = store.loadForUse(loginId);
            requireEnabled(snapshot);

            if (snapshot.hasFreshToken(clock.instant(), EXPIRY_SKEW_SECONDS)) {
                return header(snapshot, snapshot.idToken());
            }
            return header(snapshot, obtainToken(snapshot));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 這組登入現在能不能用（後台「測試登入」）。
     *
     * <p><strong>強制走一次真的登入</strong>，不吃快取 —— 否則按了等於什麼都沒驗證。
     *
     * @return token 的到期時間
     */
    public Instant verify(Long loginId) {
        ReentrantLock lock = lockFor(loginId);
        lock.lock();
        try {
            LoginSnapshot snapshot = store.loadForUse(loginId);
            // 這裡刻意不檢查 enabled：使用者修好密碼後就是要靠這顆按鈕確認能不能用，
            // 而那時它多半正處於「因為密碼錯而被自動停用」的狀態。
            return fullLogin(snapshot).expiresAt();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ 內部

    private ReentrantLock lockFor(Long loginId) {
        return locks.computeIfAbsent(loginId, id -> new ReentrantLock());
    }

    private static void requireEnabled(LoginSnapshot snapshot) {
        if (!snapshot.enabled()) {
            throw new CognitoAuthException(CognitoAuthException.Reason.CONFIGURATION,
                    "登入設定已停用: " + snapshot.name());
        }
    }

    /** refresh 優先，失敗才走完整登入。回傳明文 idToken。 */
    private String obtainToken(LoginSnapshot snapshot) {
        if (snapshot.hasRefreshToken()) {
            try {
                AuthTokens tokens = cognitoClient.refresh(snapshot.endpoint(), snapshot.refreshToken());
                store.storeTokens(snapshot.id(), tokens);
                return tokens.idToken();
            } catch (CognitoAuthException e) {
                // refresh token 過期時 Cognito 回的也是 NotAuthorizedException，與密碼錯
                // 同一個型別。但這裡的上下文明確：這是 refresh 失敗，不是帳密錯，
                // 退回完整登入才是對的，停用會是嚴重誤判。
                if (e.reason() != CognitoAuthException.Reason.INVALID_CREDENTIALS) {
                    store.recordFailure(snapshot.id(), e);
                    throw e;
                }
                log.info("login={} refresh token 已失效，改走完整登入", snapshot.name());
            }
        }
        return fullLogin(snapshot).idToken();
    }

    /** 一次完整登入的結果。 */
    private record LoginResult(String idToken, Instant expiresAt) {
    }

    /**
     * 完整 SRP 登入。失敗一律先記錄（獨立交易，見 {@link MonitorLoginStore#recordFailure}）
     * 再往外拋。
     *
     * <h2>salt 編碼的一次重試</h2>
     *
     * <p>Cognito 送來的 salt hex 以 {@code 00} 開頭、且下一個位元組最高位元為 0 時，
     * 「保留前導零」與「BigInteger 最小表示」兩種解讀會算出不同的 x，因而不同的簽章
     * —— 只有一種是對的，而我們無法事先知道是哪一種（見 {@link CognitoSrp} 類別註解）。
     *
     * <p>與其擲骰子，不如在<strong>確實具歧義</strong>時換另一種再試一次：兩次嘗試遠低於
     * 任何鎖定門檻，卻把「這個帳號永遠登不進去」變成「自動走通」。不具歧義時完全不會有
     * 第二次嘗試 —— 那只會白白多送一次錯誤密碼。
     */
    private LoginResult fullLogin(LoginSnapshot snapshot) {
        String password = store.loadPassword(snapshot.id());
        try {
            return attemptLogin(snapshot, password, CognitoSrp.SaltEncoding.PRESERVE);
        } catch (CognitoAuthException first) {
            if (first.reason() != CognitoAuthException.Reason.INVALID_CREDENTIALS) {
                store.recordFailure(snapshot.id(), first);
                throw first;
            }
            try {
                return attemptLogin(snapshot, password, CognitoSrp.SaltEncoding.MINIMAL);
            } catch (SaltUnambiguous ignored) {
                // 這個 salt 本來就沒有歧義，第一次的失敗就是最終結論。
                // 往外拋<strong>原本那個</strong>例外，不是自己合成的訊息 ——
                // Cognito 的原文（"Incorrect username or password"、"Password attempts
                // exceeded" 等）才是使用者排查時真正需要的東西。
                store.recordFailure(snapshot.id(), first);
                throw first;
            } catch (CognitoAuthException second) {
                store.recordFailure(snapshot.id(), second);
                throw second;
            }
        }
    }

    /**
     * 內部訊號：這個 salt 兩種編碼算出來一樣，第二次嘗試沒有意義。
     *
     * <p>不繼承 {@link CognitoAuthException}，這樣它永遠不會被誤當成「登入失敗的原因」
     * 送到使用者眼前。不帶堆疊（建構子最後兩個 {@code false}）——它是控制流訊號，
     * 不是錯誤，收集堆疊只是浪費。
     */
    private static final class SaltUnambiguous extends RuntimeException {
        SaltUnambiguous() {
            super(null, null, false, false);
        }
    }

    private LoginResult attemptLogin(LoginSnapshot snapshot,
                                     String password,
                                     CognitoSrp.SaltEncoding saltEncoding) {
        CognitoEndpoint endpoint = snapshot.endpoint();
        CognitoSrp.KeyPair keyPair = CognitoSrp.generateKeyPair(secureRandom);
        SrpChallenge challenge = cognitoClient.initiateSrp(endpoint, snapshot.username(), keyPair.bigAHex());

        if (saltEncoding == CognitoSrp.SaltEncoding.MINIMAL && !saltIsAmbiguous(challenge.saltHex())) {
            // 這個 salt 沒有歧義 → 換編碼會算出一模一樣的簽章，重試沒有意義，
            // 而且會白白多送一次錯誤密碼、多累加一次帳號的失敗計數。
            throw new SaltUnambiguous();
        }

        byte[] derivedKey = CognitoSrp.passwordAuthenticationKey(
                endpoint.poolName(), challenge.userIdForSrp(), password,
                keyPair, new BigInteger(challenge.srpBHex(), 16), challenge.saltHex(), saltEncoding);

        String timestamp = CognitoSrp.timestamp(clock.instant());
        String signature = CognitoSrp.passwordClaimSignature(
                derivedKey, endpoint.poolName(), challenge.userIdForSrp(),
                Base64.getDecoder().decode(challenge.secretBlock()), timestamp);

        AuthTokens tokens = cognitoClient.respondToPasswordVerifier(
                endpoint, challenge.userIdForSrp(), challenge.secretBlock(), signature, timestamp);

        Instant expiresAt = store.storeTokens(snapshot.id(), tokens);
        return new LoginResult(tokens.idToken(), expiresAt);
    }

    /** 兩種 salt 編碼會不會算出不同結果。見 {@link #fullLogin} 的說明。 */
    private static boolean saltIsAmbiguous(String saltHex) {
        return !CognitoSrp.padHex(saltHex).equals(CognitoSrp.padHex(new BigInteger(saltHex, 16)));
    }

    private static ResolvedLoginHeader header(LoginSnapshot snapshot, String idToken) {
        return new ResolvedLoginHeader(snapshot.headerName(),
                snapshot.headerValueTemplate().replace("{token}", idToken));
    }
}
