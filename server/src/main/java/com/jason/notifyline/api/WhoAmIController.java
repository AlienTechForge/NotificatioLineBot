package com.jason.notifyline.api;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.ApiResponse;
import com.jason.notifyline.common.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 呼叫端自我診斷。
 *
 * <p>回答「我的憑證有效嗎、我有哪些權限、我綁到哪個使用者」——
 * 接入時最常見的問題就是這三個，有這支端點就不必靠猜或看伺服器日誌。
 *
 * <p>刻意不回傳任何其他 client 或使用者的資訊。
 */
@RestController
public class WhoAmIController {

    @GetMapping("/api/v1/whoami")
    public ApiResponse<WhoAmI> whoAmI(HttpServletRequest request) {
        ClientPrincipal principal =
                (ClientPrincipal) request.getAttribute(RequestContext.PRINCIPAL_ATTRIBUTE);

        return ApiResponse.ok(new WhoAmI(
                principal.clientId(),
                principal.boundLineUserId(),
                principal.scopes().stream().map(Scope::value).sorted().toList(),
                principal.rateLimitPerMin(),
                principal.dailyMessageQuota()));
    }

    /**
     * @param boundLineUserId    null 代表 SERVICE client
     * @param rateLimitPerMin    null 代表使用系統預設
     * @param dailyMessageQuota  null 代表不限
     */
    public record WhoAmI(
            String clientId,
            String boundLineUserId,
            List<String> scopes,
            Integer rateLimitPerMin,
            Integer dailyMessageQuota) {
    }
}
