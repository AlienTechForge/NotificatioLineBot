package com.jason.notifyline.client;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByClientId(String clientId);

    /**
     * 取得 client 並鎖住該列，把同一個 client 的併發請求排成序列。
     *
     * <p>配額檢查是典型的 TOCTOU：先 {@code SELECT sum(...)} 判斷還有額度、再
     * INSERT。兩個併發請求會讀到同一個「還有額度」的快照，於是一起通過 ——
     * 額度 1000 的 client 在同一瞬間打 50 個請求就能送出 50000 則。
     *
     * <p>鎖的是 client 那一列而不是 notification 表，因為配額的範圍就是 client。
     * 鎖的持有時間是「配額查詢 + 一次 INSERT」，不含任何 LINE API 呼叫。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Client c where c.id = :id")
    Optional<Client> lockById(@Param("id") Long id);

    Optional<Client> findByClientIdAndStatus(String clientId, ClientStatus status);

    /**
     * 一個 LINE user 同時只會有一組 ACTIVE client —— 由 partial unique index
     * {@code uq_client_bound_active} 保證，這裡回 Optional 而非 List。
     */
    Optional<Client> findByBoundLineUserIdAndStatus(String boundLineUserId, ClientStatus status);

    List<Client> findByBoundLineUserId(String boundLineUserId);
}
