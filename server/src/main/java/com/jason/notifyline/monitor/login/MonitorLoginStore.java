package com.jason.notifyline.monitor.login;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * {@link MonitorLogin} 的交易邊界。見 {@code Docs/plan/15-監控站台登入設計.md} §5。
 *
 * <h2>為什麼要有這個類別</h2>
 *
 * <p>它存在的唯一理由是：<strong>交易絕不可以撐過對 Cognito 的 HTTP 呼叫</strong>。
 * 這是 {@code ApiMonitorStore} / {@code DeliveryStore} 已經示範過的三段式，
 * 也是 ADR-0007 的教訓 —— Hikari 只有 10 條連線，把連線握在手上等一個外部服務，
 * 結果是連線池被佔滿、連健康檢查都回不了。
 *
 * <p><strong>第一版沒有這個類別，付出了代價。</strong>當時 {@code SiteLoginService.resolve()}
 * 整段掛 {@code @Transactional}，網路呼叫在交易裡。這除了上面那個風險，還直接造成兩個
 * 線上錯誤：
 *
 * <ol>
 *   <li>登入失敗時 {@code recordFailure()} 寫入的「停用」<strong>被回滾</strong> ——
 *       因為同一個方法接著把例外往外拋，Spring 把交易標成 rollback-only。
 *       「密碼錯就停用」這條保護等於不存在，而它正是用來避免把使用者帳號鎖死的。</li>
 *   <li>後台「測試登入」回 500 而不是失敗原因 —— 外層 {@code @Transactional} 的
 *       {@code test()} 攔下例外正常回傳，提交時炸
 *       {@code UnexpectedRollbackException}，把真正的錯誤蓋掉。</li>
 * </ol>
 *
 * <p>拆成三個短交易之後，{@link #recordFailure} 在自己的交易裡提交，不受呼叫端後續
 * 是否拋例外影響 —— 這正是它必須是獨立方法（而不是 service 裡的一段程式）的原因。
 *
 * <h2>機密的流向</h2>
 *
 * <p>密碼、refresh token、idToken 的明文只在這個類別與 {@link SiteLoginService} 的
 * 方法堆疊內存在。不記錄、不放進任何 DTO。
 */
@Service
public class MonitorLoginStore {

    private static final Logger log = LoggerFactory.getLogger(MonitorLoginStore.class);

    private static final String AAD_PREFIX = "monitor_login:";

    private final MonitorLoginRepository repository;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MonitorLoginStore(MonitorLoginRepository repository,
                             SecretCipher secretCipher,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 第一段：把這次登入需要的東西一次讀出來、解密好。
     *
     * <p><strong>刻意不含密碼</strong>：多數呼叫只是要確認快取的 token 還新不新鮮，
     * 根本用不到密碼。等真的要跑完整 SRP 時再呼叫 {@link #loadPassword} ——
     * 明文密碼存在於記憶體的時間愈短愈好。
     */
    @Transactional(readOnly = true)
    public LoginSnapshot loadForUse(Long loginId) {
        MonitorLogin login = require(loginId);
        return new LoginSnapshot(
                login.getId(),
                login.getName(),
                login.isEnabled(),
                endpointOf(login),
                login.getUsername(),
                decryptOrNull(login.getTokenCiphertext(), login.getTokenIv(),
                        login.getTokenKeyVersion(), aad(loginId, "token")),
                login.getTokenExpiresAt(),
                decryptOrNull(login.getRefreshCiphertext(), login.getRefreshIv(),
                        login.getRefreshKeyVersion(), aad(loginId, "refresh")),
                login.getHeaderName(),
                login.getHeaderValueTemplate());
    }

    /** 只在真的要跑完整 SRP 時才呼叫，理由見 {@link #loadForUse}。 */
    @Transactional(readOnly = true)
    public String loadPassword(Long loginId) {
        MonitorLogin login = require(loginId);
        String password = decryptOrNull(login.getPasswordCiphertext(), login.getPasswordIv(),
                login.getPasswordKeyVersion(), aad(loginId, "password"));
        if (password == null) {
            throw new CognitoAuthException(CognitoAuthException.Reason.CONFIGURATION,
                    "這組登入還沒有設定密碼");
        }
        return password;
    }

    /**
     * 第三段（成功）：寫回 token 與到期時間。
     *
     * @return token 的到期時間，供後台顯示
     */
    @Transactional
    public Instant storeTokens(Long loginId, AuthTokens tokens) {
        MonitorLogin login = require(loginId);
        Instant now = clock.instant();
        Instant expiresAt = JwtExpiry.parse(tokens.idToken())
                .orElseGet(() -> MonitorLogin.fallbackExpiry(now, tokens.expiresInSeconds()));

        EncryptedSecret token = secretCipher.encrypt(tokens.idToken(), aad(loginId, "token"));
        EncryptedSecret refresh = tokens.refreshToken() == null
                ? null
                : secretCipher.encrypt(tokens.refreshToken(), aad(loginId, "refresh"));

        login.applyTokens(
                token.ciphertext(), token.iv(), token.keyVersion(), expiresAt,
                refresh == null ? null : refresh.ciphertext(),
                refresh == null ? null : refresh.iv(),
                refresh == null ? null : refresh.keyVersion(),
                now);
        repository.save(login);
        return expiresAt;
    }

    /**
     * 第三段（失敗）：永久性失敗停用、暫時性失敗只計數。
     *
     * <p><strong>這個方法必須在自己的交易裡提交。</strong>呼叫端在它回來之後就會把
     * 例外往外拋；如果兩者共用一個交易，這裡寫的東西會被那個拋出動作連帶回滾 ——
     * 見類別註解。
     *
     * <p>寫入失敗不往外拋：呼叫端手上有更重要的東西要報告（真正的登入失敗原因），
     * 讓一個記錄動作蓋掉它只會讓人更難查。
     */
    @Transactional
    public void recordFailure(Long loginId, CognitoAuthException e) {
        try {
            MonitorLogin login = require(loginId);
            Instant now = clock.instant();
            String detail = e.reason() + ": " + e.getMessage();
            if (e.reason().permanent()) {
                log.warn("login={} 永久性登入失敗（{}），已停用", login.getName(), e.reason());
                login.disableAfterPermanentFailure(detail, now);
            } else {
                log.warn("login={} 暫時性登入失敗（{}）", login.getName(), e.reason());
                login.recordTransientFailure(detail, now);
            }
            repository.save(login);
        } catch (RuntimeException writeFailure) {
            log.error("記錄登入失敗時本身也失敗了：loginId={}", loginId, writeFailure);
        }
    }

    /**
     * 後台建立／更新密碼時的加密進入點。
     *
     * <p><strong>AAD 陷阱</strong>：AAD 含 {@code id}，而 {@code id} 是 {@code BIGSERIAL}。
     * 呼叫前必須已經 {@code save()} 過一次拿到 id，否則會產生永遠解不開的密文。
     */
    public void encryptPasswordInto(MonitorLogin login, String plaintext) {
        Optional.ofNullable(login.getId()).orElseThrow(() -> new IllegalStateException(
                "必須先 save() 取得 id 才能加密密碼——AAD 用的就是那個 id"));
        EncryptedSecret encrypted = secretCipher.encrypt(plaintext, aad(login.getId(), "password"));
        login.applyPassword(encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), clock.instant());
    }

    // ------------------------------------------------------------------ 內部

    private MonitorLogin require(Long loginId) {
        return repository.findById(loginId)
                .orElseThrow(() -> new CognitoAuthException(
                        CognitoAuthException.Reason.CONFIGURATION, "登入設定不存在: " + loginId));
    }

    private String decryptOrNull(byte[] ciphertext, byte[] iv, Integer keyVersion, String aad) {
        if (ciphertext == null || iv == null || keyVersion == null) {
            return null;
        }
        return secretCipher.decrypt(ciphertext, iv, keyVersion, aad);
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

    /** AAD = {@code monitor_login:{id}:{欄位}}。 */
    private static String aad(Long loginId, String field) {
        return AAD_PREFIX + loginId + ":" + field;
    }
}
