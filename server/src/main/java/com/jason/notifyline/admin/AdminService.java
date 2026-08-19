package com.jason.notifyline.admin;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.ClientRepository;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUser;
import com.jason.notifyline.lineuser.LineUserRepository;
import com.jason.notifyline.lineuser.LineUserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;

/**
 * 管理操作。目前只有「設定 client 的預設通知對象」與兩個唯讀查詢。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final ClientRepository clients;
    private final LineUserRepository lineUsers;
    private final Clock clock;

    public AdminService(ClientRepository clients, LineUserRepository lineUsers, Clock clock) {
        this.clients = clients;
        this.lineUsers = lineUsers;
        this.clock = clock;
    }

    /** 依建立時間排序，讓清單順序穩定 —— 每次重整都跳動的列表沒辦法用。 */
    @Transactional(readOnly = true)
    public List<AdminDto.ClientSummary> listClients() {
        return clients.findAll().stream()
                .sorted(Comparator.comparing(Client::getCreatedAt))
                .map(AdminDto.ClientSummary::from)
                .toList();
    }

    /** 只列 ACTIVE 的 —— 已封鎖的人選了也送不到，讓他出現在選單只會製造誤會。 */
    @Transactional(readOnly = true)
    public List<AdminDto.LineUserSummary> listActiveLineUsers() {
        return lineUsers.findByStatus(LineUserStatus.ACTIVE).stream()
                // owner 排前面，其餘依顯示名稱。顯示名稱可能還沒同步到，用 id 墊底
                .sorted(Comparator.comparing(LineUser::isOwner).reversed()
                        .thenComparing(u -> u.getDisplayName() == null
                                ? u.getLineUserId() : u.getDisplayName()))
                .map(AdminDto.LineUserSummary::from)
                .toList();
    }

    /**
     * 設定或清除某個 client 的預設通知對象。
     *
     * @throws ApiException client 不存在（404），或型別與名單不一致（400）
     */
    @Transactional
    public AdminDto.ClientSummary setDefaultTarget(String clientId,
                                                   TargetType type,
                                                   List<String> userIds) {
        Client client = clients.findByClientId(clientId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Client not found."));

        List<String> normalised = type == TargetType.USER
                ? validateRecipients(userIds)
                : List.of();

        try {
            client.setDefaultTarget(type, normalised, clock.instant());
        } catch (IllegalArgumentException e) {
            // entity 的不變式檢查。訊息是我們自己寫的，可以直接回給呼叫端。
            throw new ApiException(ErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        log.info("設定預設通知對象：client={} type={} recipients={}",
                clientId, type, normalised.size());
        return AdminDto.ClientSummary.from(client);
    }

    /**
     * 收件人必須是<strong>目前存在且 ACTIVE</strong> 的使用者。
     *
     * <p>不驗證的話，一個打錯的 user id 會安靜地存進去，直到某次真的要發通知時
     * 才變成 {@code NO_RECIPIENT} —— 而那時已經沒有人記得是誰在什麼時候設錯的。
     * 設定的當下就擋掉，錯誤訊息還指得出是哪一個。
     */
    private List<String> validateRecipients(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "userIds is required when type is USER.");
        }
        List<String> unique = requested.stream().distinct().toList();
        List<String> active = lineUsers
                .findByLineUserIdInAndStatus(unique, LineUserStatus.ACTIVE).stream()
                .map(LineUser::getLineUserId)
                .toList();

        List<String> missing = unique.stream().filter(id -> !active.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "These LINE users are unknown or not active: " + String.join(", ", missing));
        }
        return unique;
    }
}
