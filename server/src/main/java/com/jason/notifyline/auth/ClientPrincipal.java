package com.jason.notifyline.auth;

import com.jason.notifyline.client.Client;
import com.jason.notifyline.client.Scope;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 已驗證的呼叫端身分。
 *
 * <p>只帶下游需要的資訊，<strong>不帶 secret 或密文</strong> —— 驗簽完成後就沒有
 * 任何理由再持有金鑰材料。
 *
 * @param boundLineUserId null 代表 SERVICE client
 */
public record ClientPrincipal(
        Long id,
        String clientId,
        String boundLineUserId,
        Set<Scope> scopes,
        Integer rateLimitPerMin,
        Integer dailyMessageQuota) {

    public ClientPrincipal {
        scopes = scopes.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Scope.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(scopes));
    }

    public static ClientPrincipal from(Client client) {
        return new ClientPrincipal(
                client.getId(),
                client.getClientId(),
                client.getBoundLineUserId(),
                client.getScopes(),
                client.getRateLimitPerMin(),
                client.getDailyMessageQuota());
    }

    public boolean hasScope(Scope scope) {
        return scopes.contains(scope);
    }

    public boolean isBound() {
        return boundLineUserId != null;
    }
}
