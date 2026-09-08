package com.jason.notifyline.admin;

import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.monitor.domain.ApiMonitorRepository;
import com.jason.notifyline.monitor.login.CognitoAuthException;
import com.jason.notifyline.monitor.login.CognitoEndpoint;
import com.jason.notifyline.monitor.login.LoginType;
import com.jason.notifyline.monitor.login.MonitorLogin;
import com.jason.notifyline.monitor.login.MonitorLoginRepository;
import com.jason.notifyline.monitor.login.SiteLoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 後台的站台登入 CRUD。見 {@code Docs/plan/15-監控站台登入設計.md} §9。
 *
 * <p>獨立於 {@link AdminService}——那個類別已經接近千行，而登入設定跟通知、client、
 * 監控本身都沒有共用的狀態，硬塞進去只會讓一個本來就過大的類別更大。
 *
 * <h2>密碼只往一個方向流</h2>
 *
 * <p>明文密碼從 {@code CreateLoginRequest} 進來，加密後落地，<strong>沒有任何一條路
 * 能把它讀回去</strong>——{@link AdminDto.LoginSummary} 刻意沒有那個欄位。編輯時
 * 密碼留空代表「不變更」，這樣使用者改個 header 名稱不必重打一次密碼。
 */
@Service
public class MonitorLoginAdminService {

    private static final Logger log = LoggerFactory.getLogger(MonitorLoginAdminService.class);

    private final MonitorLoginRepository repository;
    private final ApiMonitorRepository monitorRepository;
    private final SiteLoginService siteLoginService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MonitorLoginAdminService(MonitorLoginRepository repository,
                                    ApiMonitorRepository monitorRepository,
                                    SiteLoginService siteLoginService,
                                    ObjectMapper objectMapper,
                                    Clock clock) {
        this.repository = repository;
        this.monitorRepository = monitorRepository;
        this.siteLoginService = siteLoginService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminDto.LoginSummary> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toSummary).toList();
    }

    /**
     * 建立。
     *
     * <h2>兩段式儲存不是多餘的</h2>
     *
     * <p>密碼密文的 AAD 是 {@code monitor_login:{id}:password}，而 {@code id} 是
     * {@code BIGSERIAL}——第一次 {@code save()} 之後才存在。所以必須先存一次拿到 id，
     * 再用那個 id 加密、第二次存。用還是 {@code null} 的 id 加密會產生永遠解不開的密文。
     * 見 {@link MonitorLogin} 的「AAD 陷阱」。
     */
    @Transactional
    public AdminDto.LoginSummary create(AdminDto.CreateLoginRequest request) {
        repository.findByName(request.name()).ifPresent(existing -> {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "登入名稱已存在: " + request.name());
        });

        String config = configJson(request.region(), request.userPoolId(), request.clientId());
        Instant now = clock.instant();

        MonitorLogin login;
        try {
            login = new MonitorLogin(request.name(),
                    request.type() == null ? LoginType.COGNITO_SRP : request.type(),
                    config, request.username(), request.headerName(), request.headerValueTemplate(), now);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        // 先存一次只為了拿 id —— 見方法註解
        MonitorLogin saved = repository.saveAndFlush(login);
        siteLoginService.encryptPasswordInto(saved, request.password());
        repository.save(saved);

        log.info("建立站台登入 id={} name={}", saved.getId(), saved.getName());
        return toSummary(saved);
    }

    @Transactional
    public AdminDto.LoginSummary update(Long id, AdminDto.UpdateLoginRequest request) {
        MonitorLogin login = find(id);
        repository.findByName(request.name())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "登入名稱已存在: " + request.name());
                });

        Instant now = clock.instant();
        try {
            login.applyUpdate(request.name(),
                    configJson(request.region(), request.userPoolId(), request.clientId()),
                    request.username(), request.headerName(), request.headerValueTemplate(),
                    request.enabled() == null || request.enabled(), now);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        // 留空 = 不變更。有值時連帶作廢既有 token：它們可能是舊密碼發出的。
        if (request.password() != null && !request.password().isBlank()) {
            login.clearTokens();
            siteLoginService.encryptPasswordInto(login, request.password());
        }

        return toSummary(repository.save(login));
    }

    /**
     * 刪除。引用它的監控會因為 {@code ON DELETE SET NULL} 變成「不需要登入」，
     * 那些監控下一輪就會因為沒有 token 而失敗——所以要先讓使用者知道影響幾筆，
     * 這由前端用 {@link AdminDto.LoginSummary#monitorCount()} 呈現。
     */
    @Transactional
    public void delete(Long id) {
        MonitorLogin login = find(id);
        repository.delete(login);
        log.info("刪除站台登入 id={} name={}", id, login.getName());
    }

    /**
     * 測試登入：強制走一次真的登入。
     *
     * <p>失敗不往外拋，而是包成 {@link AdminDto.LoginTestResult}——這是「測試」按鈕，
     * 使用者期待看到失敗原因，不是一個 500。密碼錯時 {@code SiteLoginService} 已經
     * 依 §5.1 把這組登入停用了，回應裡的 {@code error} 就是要告訴使用者這件事。
     */
    @Transactional
    public AdminDto.LoginTestResult test(Long id) {
        find(id);
        try {
            Instant expiresAt = siteLoginService.verify(id);
            return new AdminDto.LoginTestResult(true, expiresAt, null);
        } catch (CognitoAuthException e) {
            String suffix = e.reason().permanent() ? "（已自動停用，請修正後重新啟用）" : "";
            return new AdminDto.LoginTestResult(false, null, e.getMessage() + suffix);
        }
    }

    // ------------------------------------------------------------------ 內部

    private MonitorLogin find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "登入設定不存在: " + id));
    }

    /**
     * 組出 {@code config} 的 JSON。<strong>先用 {@link CognitoEndpoint} 驗一次</strong>——
     * 那個 record 的建構子帶著 region／pool／client 的格式規則，在這裡借用它，
     * 就不會有「後台存得進去、輪詢時才炸」的設定。
     */
    private String configJson(String region, String userPoolId, String clientId) {
        try {
            new CognitoEndpoint(region, userPoolId, clientId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        Map<String, String> config = new LinkedHashMap<>();
        config.put("region", region);
        config.put("userPoolId", userPoolId);
        config.put("clientId", clientId);
        return objectMapper.writeValueAsString(config);
    }

    private AdminDto.LoginSummary toSummary(MonitorLogin login) {
        JsonNode config = objectMapper.readTree(login.getConfig());
        return new AdminDto.LoginSummary(
                login.getId(),
                login.getName(),
                login.getType(),
                login.isEnabled(),
                textOf(config, "region"),
                textOf(config, "userPoolId"),
                textOf(config, "clientId"),
                login.getUsername(),
                login.getHeaderName(),
                login.getHeaderValueTemplate(),
                login.getPasswordCiphertext() != null,
                login.getTokenExpiresAt(),
                login.getLastLoginAt(),
                login.getLastError(),
                login.getConsecutiveFailures(),
                monitorRepository.countByLoginId(login.getId()),
                login.getCreatedAt());
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asString();
    }
}
