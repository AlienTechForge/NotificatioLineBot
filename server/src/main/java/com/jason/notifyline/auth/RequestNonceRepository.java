package com.jason.notifyline.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RequestNonceRepository extends JpaRepository<RequestNonce, String> {

    /**
     * 原子地「若不存在才插入」。
     *
     * <p><strong>不能用 {@code save()}</strong>：{@link RequestNonce} 的主鍵是外部指定的
     * String，Spring Data 判定它「不是新實體」而走 {@code merge()}。merge 對已存在的列
     * 是 UPDATE，<strong>不會違反主鍵約束</strong> —— 於是重放的 nonce 被靜默覆寫，
     * 重放防護完全失效，而且不會有任何錯誤訊息。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 讓「檢查 + 插入」在資料庫內是一個原子操作，
     * 併發下不會有兩個請求都判定為首次。
     *
     * @return 1 = 首次出現；0 = 已用過（重放）
     */
    @Modifying
    @Query(value = """
            INSERT INTO request_nonce (nonce, client_id, seen_at)
            VALUES (:nonce, :clientId, :seenAt)
            ON CONFLICT (nonce) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("nonce") String nonce,
                       @Param("clientId") Long clientId,
                       @Param("seenAt") Instant seenAt);

    @Modifying
    @Query("delete from RequestNonce n where n.seenAt < :threshold")
    int deleteBySeenAtBefore(@Param("threshold") Instant threshold);
}
