package com.jason.notifyline.client;

import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用指令列建立第一組 client。
 *
 * <p><strong>這是雞生蛋問題的解法</strong>：還沒有任何 client 就無法呼叫 API，
 * 所以需要一條不經過 API 的路徑產生第一組憑證。Phase 2 有 Admin UI 之後仍保留
 * 這支指令，作為「Admin 也進不去」時的緊急救援手段。
 *
 * <pre>
 * java -jar app.jar --create-client --name=owner --owner --line-user-id=U0123...
 * java -jar app.jar --create-client --name=backup-service --service
 * java -jar app.jar --create-client --name=jason --line-user-id=U0123... --scopes=notify:self
 * </pre>
 *
 * <p>執行完會印出 clientId 與 secret 後<strong>直接結束行程</strong>，不會啟動 web server。
 */
@Component
public class ClientBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClientBootstrapRunner.class);

    private static final String FLAG = "create-client";

    private final ClientService clientService;
    private final LineUserRepository lineUserRepository;
    private final Clock clock;

    public ClientBootstrapRunner(ClientService clientService,
                                 LineUserRepository lineUserRepository,
                                 Clock clock) {
        this.clientService = clientService;
        this.lineUserRepository = lineUserRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(FLAG)) {
            return;
        }

        ClientService.CreateClientCommand command = buildCommand(args);
        ensureBoundUserExists(command.boundLineUserId(), args.containsOption("owner"));

        ClientService.IssuedClient issued = clientService.create(command);
        printCredentials(issued, command);
    }

    private ClientService.CreateClientCommand buildCommand(ApplicationArguments args) {
        String name = requireOption(args, "name");
        String lineUserId = optional(args, "line-user-id");

        boolean owner = args.containsOption("owner");
        boolean service = args.containsOption("service");
        if (owner && service) {
            throw new IllegalArgumentException("--owner 與 --service 不可同時指定");
        }
        // --owner / --service 是預設好的 scope 組合，跟 --scopes 併用只會讓人
        // 以為自己指定的有生效。寧可明確拒絕，不要靜默忽略。
        if ((owner || service) && optional(args, "scopes") != null) {
            throw new IllegalArgumentException(
                    "--scopes 不可與 --owner / --service 併用（那兩個已預設好 scope 組合）");
        }

        if (owner) {
            if (lineUserId == null) {
                throw new IllegalArgumentException("--owner 需要 --line-user-id（加 Bot 好友後傳「我的ID」可取得）");
            }
            return ClientService.CreateClientCommand.forOwner(name, lineUserId);
        }
        if (service) {
            return ClientService.CreateClientCommand.forService(name, quota(args));
        }

        Set<Scope> scopes = parseScopes(optional(args, "scopes"));
        if (scopes.isEmpty()) {
            scopes = Set.of(Scope.NOTIFY_SELF);
        }
        return new ClientService.CreateClientCommand(
                name, lineUserId, scopes, null, quota(args), args.containsOption("grant-owner-scopes"));
    }

    /**
     * OWNER 的 bootstrap 通常發生在對方還沒加 Bot 好友之前，所以 line_user 可能不存在。
     * 這裡補上一筆並標記 is_owner，讓 {@code target=OWNER} 立刻可用。
     *
     * <p>不加 {@code @Transactional} —— 這是同類別內的自我呼叫，代理攔截不到，
     * 加了只會製造「以為有交易」的假象。實際交易邊界在 repository 的 save()。
     */
    private void ensureBoundUserExists(String lineUserId, boolean markAsOwner) {
        if (lineUserId == null) {
            return;
        }
        LineUser user = lineUserRepository.findById(lineUserId)
                .orElseGet(() -> new LineUser(lineUserId, clock.instant()));
        if (markAsOwner && !user.isOwner()) {
            user.setOwner(true, clock.instant());
        }
        lineUserRepository.save(user);
    }

    private void printCredentials(ClientService.IssuedClient issued,
                                  ClientService.CreateClientCommand command) {
        String banner = """

                ═══════════════════════════════════════════════════════════════
                  Client 已建立
                ═══════════════════════════════════════════════════════════════
                  名稱        : %s
                  Client ID   : %s
                  Client Secret: %s
                  綁定使用者   : %s
                  權限        : %s
                ═══════════════════════════════════════════════════════════════
                  Secret 只會顯示這一次，之後無法讀回，遺失只能重設。
                  請立刻存進密碼管理器或呼叫端的環境變數。
                ═══════════════════════════════════════════════════════════════
                """.formatted(
                command.name(),
                issued.clientId(),
                issued.secret(),
                command.boundLineUserId() == null ? "（無，SERVICE client）" : command.boundLineUserId(),
                issued.scopes().stream().map(Scope::value).collect(Collectors.joining(", ")));

        // 刻意用 System.out 而不是 logger —— secret 絕不可進日誌檔
        System.out.println(banner);
        log.info("Bootstrap 建立 client 完成：clientId={}", issued.clientId());
    }

    private static Set<Scope> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Scope::fromValue)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Integer quota(ApplicationArguments args) {
        String value = optional(args, "daily-quota");
        return value == null ? null : Integer.valueOf(value);
    }

    private static String requireOption(ApplicationArguments args, String name) {
        String value = optional(args, name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " 為必填");
        }
        return value;
    }

    private static String optional(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
