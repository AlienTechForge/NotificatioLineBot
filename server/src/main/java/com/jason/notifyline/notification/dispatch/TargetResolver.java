package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.lineuser.LineUserService;
import com.jason.notifyline.notification.api.NotificationRequest;
import com.jason.notifyline.notification.domain.TargetType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 target 解析成實際收件人，並在<strong>這一處</strong>完成所有 scope 檢查。
 *
 * <p>規格見 {@code Docs/plan/03-權限與認證設計.md} §1.3。
 *
 * <p>權限檢查集中在一個地方而不是散落在 Controller —— 越是需要逐案判斷的權限規則
 * 越容易寫錯。這裡的每條規則都是無條件的。
 */
@Component
public class TargetResolver {

    private final LineUserService lineUserService;

    public TargetResolver(LineUserService lineUserService) {
        this.lineUserService = lineUserService;
    }

    /**
     * @return 實際的收件人清單，保證非空
     * @throws ApiException scope 不足（403）或無有效收件人（400）
     */
    public List<String> resolve(ClientPrincipal principal, NotificationRequest request) {
        TargetType type = request.target().type();

        requireScope(principal, type.requiredScope(),
                "This client is not permitted to send to target type " + type + ".");

        // 逃生門也是一種權限。原始 message object 可帶 uri action，
        // 一組外洩的金鑰就能發出掛著官方帳號名義的釣魚連結（缺口 G5）。
        if (request.usesRawMessages()) {
            requireScope(principal, Scope.NOTIFY_RAW,
                    "Sending raw LINE message objects requires the notify:raw scope.");
        }

        List<String> recipients = switch (type) {
            case SELF -> resolveSelf(principal);
            case OWNER -> lineUserService.activeOwnerIds();
            case USER -> resolveUsers(principal, request);
            case ALL -> lineUserService.activeUserIds();
        };

        if (recipients.isEmpty()) {
            throw new ApiException(ErrorCode.NO_RECIPIENT,
                    "No active recipient resolved for target type " + type + ".");
        }
        return recipients;
    }

    private List<String> resolveSelf(ClientPrincipal principal) {
        if (!principal.isBound()) {
            throw new ApiException(ErrorCode.CLIENT_NOT_BOUND,
                    "This credential is not bound to a LINE user. "
                            + "Service credentials should use target type OWNER.");
        }
        return lineUserService.isActive(principal.boundLineUserId())
                ? List.of(principal.boundLineUserId())
                : List.of();
    }

    /**
     * <strong>刻意的嚴格規則</strong>：沒有 {@code notify:user} 就一律 403，
     * 即使 userIds 只填自己。
     *
     * <p>開這個後門會讓規則從「沒有 notify:user 就不准出現 userIds」變成
     * 「沒有時 userIds 只能包含自己，且長度為 1，且要比對綁定的 id」——
     * 權限檢查的正確性與它的分支數成反比。要發給自己就用 SELF。
     */
    private List<String> resolveUsers(ClientPrincipal principal, NotificationRequest request) {
        List<String> requested = request.target().userIds();
        if (requested == null || requested.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "target.userIds is required when target.type is USER.");
        }
        // scope 已在上方檢查過；這裡只做收件人過濾
        return lineUserService.activeAmong(requested);
    }

    private static void requireScope(ClientPrincipal principal, Scope scope, String message) {
        if (!principal.hasScope(scope)) {
            throw new ApiException(ErrorCode.SCOPE_DENIED, message);
        }
    }
}
