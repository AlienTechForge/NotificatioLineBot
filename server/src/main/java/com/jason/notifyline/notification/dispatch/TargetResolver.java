package com.jason.notifyline.notification.dispatch;

import com.jason.notifyline.auth.ClientPrincipal;
import com.jason.notifyline.client.Scope;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import com.jason.notifyline.common.TargetType;
import com.jason.notifyline.lineuser.LineUserService;
import com.jason.notifyline.notification.api.NotificationRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 target 解析成實際收件人，並在<strong>這一處</strong>完成所有 scope 檢查。
 *
 * <p>規格見 {@code Docs/plan/03-權限與認證設計.md} §1.3。
 *
 * <h2>核心規則：誰指定收件人，決定要不要檢查 scope</h2>
 *
 * <table border="1">
 *   <caption>授權來源</caption>
 *   <tr><th>收件人來自</th><th>要檢查 scope 嗎</th></tr>
 *   <tr><td>管理者設定在 client 上的預設對象</td><td><strong>不用</strong></td></tr>
 *   <tr><td>呼叫端在請求裡自己指定</td><td><strong>要</strong></td></tr>
 * </table>
 *
 * <p>理由：預設對象是管理者填的，授權行為本身已經發生了。再去檢查呼叫端有沒有
 * 對應 scope，等於要求管理者「先授予發給任何人的權限，才能指定發給某三個人」——
 * 那會把最小權限原則整個倒過來。
 *
 * <p>實際效果：一個只被信任「發給預設對象」的 service，可以被管理者指向任意
 * 收件人組合，卻拿不到 {@code notify:user}（那等於能發給任何人）。
 *
 * <p>權限檢查集中在一個地方而不是散落在 Controller —— 越是需要逐案判斷的權限
 * 規則越容易寫錯。這裡的每條規則都是無條件的。
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
        // 逃生門也是一種權限，與 target 無關，所以先檢查。
        // 原始 message object 可帶 uri action，一組外洩的金鑰就能發出掛著官方帳號
        // 名義的釣魚連結（缺口 G5）。
        if (request.usesRawMessages()) {
            requireScope(principal, Scope.NOTIFY_RAW,
                    "Sending raw LINE message objects requires the notify:raw scope.");
        }

        Resolved resolved = request.hasTarget()
                ? fromRequest(principal, request)
                : fromClientDefault(principal);

        if (resolved.recipients().isEmpty()) {
            throw new ApiException(ErrorCode.NO_RECIPIENT,
                    "No active recipient resolved for target type " + resolved.type() + ".");
        }
        return resolved.recipients();
    }

    /** 決定這次發送記錄成哪一型。與 {@link #resolve} 的判斷必須一致。 */
    public TargetType effectiveType(ClientPrincipal principal, NotificationRequest request) {
        return request.hasTarget()
                ? request.target().type()
                : requireDefaultTarget(principal);
    }

    // ------------------------------------------------------ 呼叫端指定的 target

    private Resolved fromRequest(ClientPrincipal principal, NotificationRequest request) {
        TargetType type = request.target().type();

        requireScope(principal, requiredScope(type),
                "This client is not permitted to send to target type " + type + ".");

        List<String> recipients = switch (type) {
            case SELF -> resolveSelf(principal);
            case OWNER -> lineUserService.activeOwnerIds();
            case USER -> resolveRequestedUsers(request);
            case ALL -> lineUserService.activeUserIds();
        };
        return new Resolved(type, recipients);
    }

    /**
     * target × scope 允許矩陣。
     *
     * <p>這個對應關係放在 resolver 而不是 {@link TargetType} 上，是為了讓
     * {@code TargetType} 不必知道 {@code Scope} 的存在 —— 見該列舉的說明。
     */
    private static Scope requiredScope(TargetType type) {
        return switch (type) {
            case SELF -> Scope.NOTIFY_SELF;
            case OWNER -> Scope.NOTIFY_OWNER;
            case USER -> Scope.NOTIFY_USER;
            case ALL -> Scope.NOTIFY_ALL;
        };
    }

    /**
     * <strong>刻意的嚴格規則</strong>：沒有 {@code notify:user} 就一律 403，
     * 即使 userIds 只填自己。
     *
     * <p>開這個後門會讓規則從「沒有 notify:user 就不准出現 userIds」變成
     * 「沒有時 userIds 只能包含自己，且長度為 1，且要比對綁定的 id」——
     * 權限檢查的正確性與它的分支數成反比。要發給自己就用 SELF。
     */
    private List<String> resolveRequestedUsers(NotificationRequest request) {
        List<String> requested = request.target().userIds();
        if (requested == null || requested.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "target.userIds is required when target.type is USER.");
        }
        return lineUserService.activeAmong(requested);
    }

    // --------------------------------------------------- 管理者設定的預設對象

    /**
     * 套用預設對象。<strong>這裡沒有 scope 檢查，而且是刻意的</strong>，理由見類別說明。
     */
    private Resolved fromClientDefault(ClientPrincipal principal) {
        TargetType type = requireDefaultTarget(principal);

        List<String> recipients = switch (type) {
            case SELF -> resolveSelf(principal);
            case OWNER -> lineUserService.activeOwnerIds();
            // 名單由管理者指定，這裡只過濾掉已經不是好友的人
            case USER -> lineUserService.activeAmong(principal.defaultTargetUserIds());
            case ALL -> lineUserService.activeUserIds();
        };
        return new Resolved(type, recipients);
    }

    private static TargetType requireDefaultTarget(ClientPrincipal principal) {
        if (!principal.hasDefaultTarget()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "target is required: this client has no default notification target configured. "
                            + "Ask the administrator to set one, or specify target explicitly.");
        }
        return principal.defaultTargetType();
    }

    // ------------------------------------------------------------------ 共用

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

    private static void requireScope(ClientPrincipal principal, Scope scope, String message) {
        if (!principal.hasScope(scope)) {
            throw new ApiException(ErrorCode.SCOPE_DENIED, message);
        }
    }

    private record Resolved(TargetType type, List<String> recipients) {
    }
}
