package com.jason.notifyline.monitor.session;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 站台登入狀態（cookie jar）。見 {@code Docs/plan/12-API監控易用性升級.md} §3。
 *
 * <h2>⚠️ host 比對是這個類別最危險的地方</h2>
 *
 * <p>附加 cookie 前一律經過 {@link HostMatcher}，不可自行用 {@code contains}／
 * {@code endsWith}／{@code startsWith} 比對——見該類別的類別註解。這裡刻意<strong>不</strong>
 * 把比對邏輯寫在這個類別裡，讓 {@link HostMatcher} 可以獨立測試那條「evil-example.com
 * 不可誤配 example.com」的關鍵案例，不必每次都繞經一個要接資料庫的 service。
 *
 * <h2>merge-back 只更新既有 jar，不會憑空建立新的</h2>
 *
 * <p>{@link #mergeSetCookies} 在 {@link #findJarForHost} 找不到既有 jar 時直接放棄——
 * jar 只透過 {@link #importCookies}（使用者主動貼上）誕生，不能被「剛好某個第三方
 * API 回應裡有 Set-Cookie」這種偶然事件動態生出來。這既避免雜訊 cookie 污染資料庫，
 * 也讓「這個站有沒有登入狀態」完全由使用者自己的動作決定，符合
 * {@code Docs/plan/12-API監控易用性升級.md} §3.5「不做自動登入」的邊界。
 *
 * <h2>Cookie 值永遠不落地明文</h2>
 *
 * <p>jar 內容用 {@link SecretCipher} 加密，AAD = {@code "site_session:" + host}——
 * {@code host} 是主鍵，加密前一定已經存在，沒有 {@code ApiMonitor} header 那種要先
 * insert 拿 id 的順序陷阱。{@code cookie_names} 只存名稱，供後台顯示「這個站存了哪些
 * cookie」用；解密後的值只活在方法呼叫的堆疊裡，不會被記錄、也不會被任何 DTO 回傳。
 */
@Service
public class SiteSessionService {

    private static final Logger log = LoggerFactory.getLogger(SiteSessionService.class);

    private static final String AAD_PREFIX = "site_session:";
    private static final String COOKIE_HEADER_NAME = "Cookie";

    private final SiteSessionRepository repository;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SiteSessionService(SiteSessionRepository repository,
                              SecretCipher secretCipher,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // -------------------------------------------------------------- 抓取時：附加

    /**
     * 依請求 host 找 jar，比對通過（{@link HostMatcher}）才附上 {@code Cookie} header。
     *
     * <p>{@code headers} 裡已經有（大小寫不拘）{@code Cookie} 這個 key 時<strong>不覆
     * 蓋</strong>——那是監控自己設定的值，可能跟這個 jar 無關，尊重呼叫端的明確設定，
     * 不會被這裡悄悄取代。
     *
     * @param requestHost 實際要打的請求 host（樣板替換、guard 檢查之後的值）
     * @param headers     樣板替換後的明文 header；{@code null} 視同空 map
     * @return 附加（或原樣）後的 header map；找不到符合的 jar，或 jar 是空的，原樣回傳
     */
    @Transactional(readOnly = true)
    public Map<String, String> attachCookies(String requestHost, Map<String, String> headers) {
        Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
        if (CookieCodec.findHeaderValueIgnoreCase(safeHeaders, COOKIE_HEADER_NAME).isPresent()) {
            return safeHeaders;
        }
        Optional<SiteSession> jar = findJarForHost(requestHost);
        if (jar.isEmpty()) {
            return safeHeaders;
        }
        Map<String, String> cookies = decrypt(jar.get());
        if (cookies.isEmpty()) {
            return safeHeaders;
        }
        Map<String, String> merged = new LinkedHashMap<>(safeHeaders);
        merged.put(COOKIE_HEADER_NAME, CookieCodec.serializeCookieHeader(cookies));
        return Map.copyOf(merged);
    }

    // -------------------------------------------------------------- 回應時：合併回寫

    /**
     * 解析 {@code Set-Cookie}，合併進既有 jar，更新 {@code last_refreshed_at}。
     *
     * <p><strong>安全關鍵</strong>：每一筆 {@code Set-Cookie} 各自檢查 {@code Domain=}
     * 屬性——若指向這次請求 host 比對不到的網域（{@link HostMatcher#matches}），忽略
     * <strong>那一筆</strong>（不是整批放棄，其餘沒有 {@code Domain} 或 {@code Domain}
     * 合法的 cookie 仍然合併）。不可讓回應自己擴張 jar 的適用範圍——這正是一個被攻破
     * 或惡意的目標端點，用 {@code Set-Cookie: x=y; Domain=other.example} 把 cookie
     * 種到別的 host 名下的攻擊路徑。
     *
     * <p>找不到既有 jar 時整批放棄，見類別註解「merge-back 只更新既有 jar」。
     */
    @Transactional
    public void mergeSetCookies(String requestHost, List<String> setCookieHeaderValues) {
        if (setCookieHeaderValues == null || setCookieHeaderValues.isEmpty()) {
            return;
        }
        Optional<SiteSession> jarOpt = findJarForHost(requestHost);
        if (jarOpt.isEmpty()) {
            return;
        }
        SiteSession jar = jarOpt.get();

        Map<String, String> cookies = new LinkedHashMap<>(decrypt(jar));
        boolean changed = false;
        for (String raw : setCookieHeaderValues) {
            CookieCodec.SetCookieAttributes parsed = CookieCodec.parseSetCookie(raw);
            if (parsed == null) {
                continue;
            }
            if (parsed.domain() != null && !HostMatcher.matches(requestHost, parsed.domain())) {
                continue; // Domain 指向不同網域，忽略這筆——見類別／方法註解
            }
            cookies.put(parsed.name(), parsed.value());
            changed = true;
        }
        if (!changed) {
            return;
        }

        persist(jar.getHost(), cookies, jar);
        log.info("Set-Cookie 已合併回 jar：host={} cookieCount={}", jar.getHost(), cookies.size());
    }

    // -------------------------------------------------------------- 匯入時：貼上

    /**
     * 從匯入解析出的 {@code cookie:} header 值建立或更新 jar。
     *
     * <p>與 {@link #mergeSetCookies} 不同：這裡<strong>會</strong>在沒有既有 jar 時建立
     * 新的一筆——jar 就是靠使用者主動貼上誕生的，這是唯一的建立入口。
     *
     * @param host              匯入的請求 URL 的 host（未正規化也可以，內部會正規化）
     * @param cookieHeaderValue {@code Cookie} header 的原始值，例如 {@code "a=1; b=2"}
     */
    @Transactional
    public void importCookies(String host, String cookieHeaderValue) {
        Map<String, String> parsed = CookieCodec.parseCookieHeader(cookieHeaderValue);
        if (parsed.isEmpty()) {
            return;
        }
        String normalizedHost = HostMatcher.normalize(host);
        SiteSession existing = repository.findById(normalizedHost).orElse(null);

        Map<String, String> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(decrypt(existing));
        }
        merged.putAll(parsed); // 重貼的值覆蓋舊值——使用者重貼就是要更新登入狀態

        persist(normalizedHost, merged, existing);
        log.info("站台登入狀態已{}：host={} cookieCount={}",
                existing == null ? "建立" : "更新", normalizedHost, merged.size());
    }

    // -------------------------------------------------------------- 後台管理

    /** 依 host 排序，理由同 {@code AdminService.listClients}：讓每次重整的列表順序穩定。 */
    @Transactional(readOnly = true)
    public List<SiteSession> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(SiteSession::getHost))
                .toList();
    }

    /**
     * 清除某個 host 的登入狀態（不可回復）。
     *
     * @throws ApiException 該 host 沒有登入狀態（404）
     */
    @Transactional
    public void delete(String host) {
        String normalized = HostMatcher.normalize(host);
        if (!repository.existsById(normalized)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No stored login state for host: " + host);
        }
        repository.deleteById(normalized);
        log.info("站台登入狀態已清除：host={}", normalized);
    }

    // -------------------------------------------------------------- 共用

    /**
     * 找出涵蓋這個請求 host 的 jar：完全相等，或請求 host 是某個已存 jar host 的子網域
     * （{@link HostMatcher#matches}）。可能有多筆祖先網域同時命中（例如同時存了
     * {@code example.com} 與 {@code api.example.com} 兩筆，請求打 {@code api.example.com}），
     * 取 host 字串最長的那筆——最貼近實際請求 host 的那一個，語意上是「最具體的
     * 那份登入狀態」。
     *
     * <p>全表掃描，理由同 {@code ApiMonitorStore.loadSeenKeys}：這張表的規模是「使用者
     * 貼過幾個站的登入狀態」，對單人／小團隊系統而言不會大到需要另外建索引查詢。
     */
    private Optional<SiteSession> findJarForHost(String requestHost) {
        if (requestHost == null || requestHost.isBlank()) {
            return Optional.empty();
        }
        return repository.findAll().stream()
                .filter(row -> HostMatcher.matches(requestHost, row.getHost()))
                .max(Comparator.comparingInt(row -> row.getHost().length()));
    }

    private void persist(String host, Map<String, String> cookies, SiteSession existing) {
        Instant now = clock.instant();
        String plaintext = objectMapper.writeValueAsString(cookies);
        EncryptedSecret encrypted = secretCipher.encrypt(plaintext, AAD_PREFIX + host);
        String cookieNames = String.join(",", cookies.keySet());

        if (existing == null) {
            repository.save(new SiteSession(
                    host, encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), cookieNames, now));
        } else {
            existing.applyJar(encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), cookieNames, now);
            repository.save(existing);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> decrypt(SiteSession jar) {
        String plaintext = secretCipher.decrypt(
                jar.getJarCiphertext(), jar.getJarIv(), jar.getJarKeyVersion(), AAD_PREFIX + jar.getHost());
        Map<String, Object> raw = objectMapper.readValue(plaintext, Map.class);
        Map<String, String> cookies = new LinkedHashMap<>();
        raw.forEach((key, value) -> cookies.put(key, value == null ? "" : String.valueOf(value)));
        return cookies;
    }
}
