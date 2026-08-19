package com.jason.notifyline.client;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {

    Optional<Client> findByClientId(String clientId);

    Optional<Client> findByClientIdAndStatus(String clientId, ClientStatus status);

    /**
     * 一個 LINE user 同時只會有一組 ACTIVE client —— 由 partial unique index
     * {@code uq_client_bound_active} 保證，這裡回 Optional 而非 List。
     */
    Optional<Client> findByBoundLineUserIdAndStatus(String boundLineUserId, ClientStatus status);

    List<Client> findByBoundLineUserId(String boundLineUserId);
}
