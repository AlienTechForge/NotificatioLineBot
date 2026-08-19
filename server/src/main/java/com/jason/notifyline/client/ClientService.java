package com.jason.notifyline.client;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.common.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Client 憑證的建立與生命週期。
 *
 * <p>設計見 {@code Docs/plan/03-權限與認證設計.md} §1。
 */
@Service
public class ClientService {

    private static final Logger log = LoggerFactory.getLogger(ClientService.class);

    private final ClientRepository clientRepository;
    private final SecretCipher secretCipher;
    private final Clock clock;

    public ClientService(ClientRepository clientRepository, SecretCipher secretCipher, Clock clock) {
        this.clientRepository = clientRepository;
        this.secretCipher = secretCipher;
        this.clock = clock;
    }

    /**
     * 建立 client。
     *
     * <p><strong>回傳值是唯一一次能拿到明文 secret 的機會</strong> ——
     * 之後任何人（包含 OWNER 與資料庫管理員）都無法讀回，只能重設。
     * 這是刻意的：可讀回會讓管理介面變成集中的金鑰外洩點。
     */
    @Transactional
    public IssuedClient create(CreateClientCommand command) {
        command.validate();

        Instant now = clock.instant();
        String clientId = Ids.newClientId();
        String secret = Ids.newClientSecret();

        // AAD 綁 client_id：防止把 A 的密文搬到 B 的記錄上
        EncryptedSecret encrypted = secretCipher.encrypt(secret, clientId);

        Client client = new Client(
                clientId,
                command.name(),
                encrypted.ciphertext(),
                encrypted.iv(),
                encrypted.keyVersion(),
                command.boundLineUserId(),
                command.scopes(),
                command.rateLimitPerMin(),
                command.dailyMessageQuota(),
                now);

        try {
            clientRepository.saveAndFlush(client);
        } catch (DataIntegrityViolationException e) {
            // 「一人一把有效金鑰」由 partial unique index uq_client_bound_active 保證，
            // 不靠應用程式先查再插（那會有競態）。
            throw new ClientAlreadyExistsException(command.boundLineUserId(), e);
        }

        log.info("Client 已建立：clientId={} name={} bound={} scopes={}",
                clientId, command.name(), command.boundLineUserId(), command.scopes());

        return new IssuedClient(clientId, secret, client.getScopes());
    }

    /** 可回復的暫停。 */
    @Transactional
    public void disable(String clientId, String reason) {
        Client client = requireClient(clientId);
        client.disable(clock.instant());
        log.info("Client 已停用：clientId={} reason={}", clientId, reason);
    }

    /** 不可回復的作廢。 */
    @Transactional
    public void revoke(String clientId, String reason) {
        Client client = requireClient(clientId);
        client.revoke(clock.instant());
        log.info("Client 已撤銷：clientId={} reason={}", clientId, reason);
    }

    /** 使用者封鎖 Bot 時連帶停用其金鑰，讓狀態一致並避免無效的 API 呼叫。 */
    @Transactional
    public void disableAllForLineUser(String lineUserId, String reason) {
        Instant now = clock.instant();
        clientRepository.findByBoundLineUserIdAndStatus(lineUserId, ClientStatus.ACTIVE)
                .ifPresent(client -> {
                    client.disable(now);
                    log.info("Client 因使用者封鎖而停用：clientId={} lineUserId={} reason={}",
                            client.getClientId(), lineUserId, reason);
                });
    }

    /** 撤銷該使用者現有的 ACTIVE 金鑰，供「重設金鑰」流程使用。 */
    @Transactional
    public void revokeActiveForLineUser(String lineUserId, String reason) {
        Instant now = clock.instant();
        clientRepository.findByBoundLineUserIdAndStatus(lineUserId, ClientStatus.ACTIVE)
                .ifPresent(client -> {
                    client.revoke(now);
                    log.info("Client 已撤銷：clientId={} lineUserId={} reason={}",
                            client.getClientId(), lineUserId, reason);
                });
    }

    @Transactional(readOnly = true)
    public Optional<Client> findActive(String clientId) {
        return clientRepository.findByClientIdAndStatus(clientId, ClientStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<Client> findActiveByLineUser(String lineUserId) {
        return clientRepository.findByBoundLineUserIdAndStatus(lineUserId, ClientStatus.ACTIVE);
    }

    /**
     * 解密出明文 secret 供驗簽使用。
     *
     * <p>只有 {@code HmacAuthFilter} 該呼叫這個方法。回傳值絕不可記錄到日誌。
     */
    public String decryptSecret(Client client) {
        return secretCipher.decrypt(
                client.getSecretCiphertext(),
                client.getSecretIv(),
                client.getSecretKeyVersion(),
                client.getClientId());
    }

    @Transactional
    public void markUsed(String clientId) {
        clientRepository.findByClientId(clientId)
                .ifPresent(client -> client.markUsed(clock.instant()));
    }

    private Client requireClient(String clientId) {
        return clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    /**
     * 建立 client 的參數。
     *
     * @param scopes OWNER-only 的 scope 需要 {@code ownerGranted = true} 才允許
     */
    public record CreateClientCommand(
            String name,
            String boundLineUserId,
            Set<Scope> scopes,
            Integer rateLimitPerMin,
            Integer dailyMessageQuota,
            boolean ownerGranted) {

        public void validate() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("client name must not be blank");
            }
            if (scopes == null || scopes.isEmpty()) {
                throw new IllegalArgumentException("at least one scope is required");
            }
            if (scopes.contains(Scope.NOTIFY_SELF) && boundLineUserId == null) {
                throw new IllegalArgumentException(
                        "notify:self requires a bound LINE user; SERVICE client 請改用 notify:owner");
            }
            if (!ownerGranted) {
                // 訊息用 API 形式（notify:all）而非 enum 名稱（NOTIFY_ALL），
                // 讀到這則錯誤的人看到的是他實際填的字串
                String denied = scopes.stream()
                        .filter(Scope::isOwnerOnly)
                        .map(Scope::value)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(", "));
                if (!denied.isEmpty()) {
                    throw new IllegalArgumentException("只有 OWNER 能授予這些 scope：" + denied);
                }
            }
        }

        /** 自助申請的一般使用者金鑰。 */
        public static CreateClientCommand forUser(String name, String lineUserId, Integer dailyQuota) {
            return new CreateClientCommand(
                    name, lineUserId, Set.of(Scope.NOTIFY_SELF), null, dailyQuota, false);
        }

        /** 後端服務金鑰，無綁定使用者，預設只能通知管理者。 */
        public static CreateClientCommand forService(String name, Integer dailyQuota) {
            return new CreateClientCommand(
                    name, null, Set.of(Scope.NOTIFY_OWNER), null, dailyQuota, false);
        }

        /** 管理者金鑰，擁有全部發送權限（不含 notify:raw，那要另外明確授予）。 */
        public static CreateClientCommand forOwner(String name, String lineUserId) {
            return new CreateClientCommand(
                    name,
                    lineUserId,
                    Set.of(Scope.NOTIFY_SELF, Scope.NOTIFY_OWNER, Scope.NOTIFY_USER, Scope.NOTIFY_ALL),
                    null,
                    null,
                    true);
        }
    }

    /**
     * 建立結果。{@code secret} 是明文，只在此刻存在。
     */
    public record IssuedClient(String clientId, String secret, Set<Scope> scopes) {

        /** 避免明文 secret 因為順手 log 整個物件而外洩。 */
        @Override
        public String toString() {
            return "IssuedClient[" + clientId + " scopes=" + scopes + " secret=***]";
        }
    }
}
