package com.jason.notifyline.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * 重放防護。
 *
 * <p>保留期是時間窗的兩倍（±300 秒 → 保留 600 秒），確保時間窗內的重放
 * 一定會撞到既有的 nonce。超過時間窗的重放則早就被時間戳擋掉了。
 */
@Component
public class NonceStore {

    private static final Logger log = LoggerFactory.getLogger(NonceStore.class);

    /** 時間窗 300 秒的兩倍。 */
    public static final Duration RETENTION = Duration.ofSeconds(600);

    private final RequestNonceRepository repository;
    private final Clock clock;

    public NonceStore(RequestNonceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 記錄 nonce。
     *
     * <p>用 {@code REQUIRES_NEW}：這個寫入必須獨立提交，不能被外層可能的
     * rollback 一起帶走 —— 否則失敗的請求會「歸還」它用掉的 nonce。
     *
     * @return true 代表首次出現；false 代表重放
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryConsume(String nonce, Long clientId) {
        // 用 ON CONFLICT DO NOTHING 而不是 save()：見 RequestNonceRepository
        // 的說明 —— save() 對 assigned id 會走 merge，重放會被靜默覆寫。
        return repository.insertIfAbsent(nonce, clientId, clock.instant()) == 1;
    }

    /**
     * 清掉過期的 nonce。
     *
     * <p>不清會讓這張表無限增長，而它是每個 API 請求都要寫入的熱點表。
     *
     * <p>多實例注意：這個排程在多實例下會重複執行（缺口 G12）。重複刪除是冪等的，
     * 所以不會出錯，但會浪費資源。加第二個實例前需導入 ShedLock。
     */
    @Scheduled(fixedDelayString = "PT5M")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteBySeenAtBefore(clock.instant().minus(RETENTION));
        if (deleted > 0) {
            log.debug("清除過期 nonce：{} 筆", deleted);
        }
    }
}
