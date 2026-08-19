package com.jason.notifyline.lineuser;

import com.jason.notifyline.client.ClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * LINE 好友名單維護。
 *
 * <p>因為 LINE 對已封鎖使用者的 multicast <strong>仍然回 200</strong>
 * （見 {@code Docs/plan/06-LINE整合設計.md} §1.3），發送回應完全不能用來判斷
 * 名單有效性。名單只能靠 webhook 事件與 Profile API 主動維護。
 */
@Service
public class LineUserService {

    private static final Logger log = LoggerFactory.getLogger(LineUserService.class);

    private final LineUserRepository repository;
    private final ClientService clientService;
    private final Clock clock;

    public LineUserService(LineUserRepository repository, ClientService clientService, Clock clock) {
        this.repository = repository;
        this.clientService = clientService;
        this.clock = clock;
    }

    /** 加好友或解除封鎖。天然冪等 —— 重複呼叫結果相同。 */
    @Transactional
    public LineUser follow(String lineUserId) {
        Instant now = clock.instant();
        LineUser user = repository.findById(lineUserId)
                .map(existing -> {
                    existing.follow(now);
                    return existing;
                })
                .orElseGet(() -> new LineUser(lineUserId, now));

        LineUser saved = repository.save(user);
        log.info("使用者加入好友：lineUserId={}", lineUserId);
        return saved;
    }

    /**
     * 封鎖官方帳號。
     *
     * <p>連帶停用該使用者的金鑰 —— 他不想再收到通知了，但金鑰仍有效還能繼續打 API
     * （只是訊息送不到）。停用讓狀態一致，也避免無效呼叫消耗配額。
     *
     * <p>重新加好友時<strong>不會自動恢復</strong>金鑰，需重新申請。這是刻意的：
     * 封鎖期間金鑰可能已外流。
     */
    @Transactional
    public void unfollow(String lineUserId) {
        repository.findById(lineUserId).ifPresent(user -> {
            user.block(clock.instant());
            log.info("使用者封鎖官方帳號：lineUserId={}", lineUserId);
        });
        clientService.disableAllForLineUser(lineUserId, "使用者封鎖官方帳號");
    }

    /** Profile API 回 404 代表帳號已刪除或已封鎖，是可靠的「這人不在了」訊號。 */
    @Transactional
    public void markGone(String lineUserId) {
        repository.findById(lineUserId).ifPresent(user -> {
            if (user.isActive()) {
                user.block(clock.instant());
                log.info("Profile 校正發現使用者已不存在：lineUserId={}", lineUserId);
            }
        });
        clientService.disableAllForLineUser(lineUserId, "Profile API 回報使用者不存在");
    }

    @Transactional
    public void applyProfile(String lineUserId, String displayName, String pictureUrl,
                             String statusMessage, String language) {
        repository.findById(lineUserId).ifPresent(user ->
                user.applyProfile(displayName, pictureUrl, statusMessage, language, clock.instant()));
    }

    @Transactional
    public void setOwner(String lineUserId, boolean owner) {
        LineUser user = repository.findById(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("LINE user not found: " + lineUserId));
        user.setOwner(owner, clock.instant());
        log.info("使用者 owner 標記變更：lineUserId={} owner={}", lineUserId, owner);
    }

    @Transactional(readOnly = true)
    public Optional<LineUser> find(String lineUserId) {
        return repository.findById(lineUserId);
    }

    @Transactional(readOnly = true)
    public boolean isActive(String lineUserId) {
        return repository.findById(lineUserId).map(LineUser::isActive).orElse(false);
    }

    /** target=ALL 的收件人。 */
    @Transactional(readOnly = true)
    public List<String> activeUserIds() {
        return repository.findByStatus(LineUserStatus.ACTIVE).stream()
                .map(LineUser::getLineUserId)
                .toList();
    }

    /** target=OWNER 的收件人。 */
    @Transactional(readOnly = true)
    public List<String> activeOwnerIds() {
        return repository.findByOwnerTrueAndStatus(LineUserStatus.ACTIVE).stream()
                .map(LineUser::getLineUserId)
                .toList();
    }

    /** target=USER 的收件人：只保留仍然 ACTIVE 的。 */
    @Transactional(readOnly = true)
    public List<String> activeAmong(List<String> lineUserIds) {
        if (lineUserIds.isEmpty()) {
            return List.of();
        }
        return repository.findByLineUserIdInAndStatus(lineUserIds, LineUserStatus.ACTIVE).stream()
                .map(LineUser::getLineUserId)
                .toList();
    }
}
