package com.jason.notifyline.admin;

import com.jason.notifyline.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理 API。
 *
 * <p>掛在 {@code /admin/api/**} 而不是 {@code /api/v1/admin/**}，是為了讓
 * <strong>認證方式與路徑一致</strong>：{@code /api/v1/**} 一律是 HMAC，
 * {@code /admin/**} 一律是 session。同一個能力若同時開放兩條認證路徑，
 * 就有兩份可能寫錯的驗證邏輯，而其中一份一定比較少被測到。
 *
 * <p>權限由 {@link AdminSecurityConfig} 的 filter chain 統一擋掉，所以這裡
 * 沒有任何 scope 檢查 —— 能走到這個類別就代表已經是登入的管理者。
 */
@RestController
@RequestMapping("/admin/api")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/clients")
    public ApiResponse<List<AdminDto.ClientSummary>> listClients() {
        return ApiResponse.ok(adminService.listClients());
    }

    @GetMapping("/line-users")
    public ApiResponse<List<AdminDto.LineUserSummary>> listLineUsers() {
        return ApiResponse.ok(adminService.listActiveLineUsers());
    }

    /**
     * 設定 client 的預設通知對象。
     *
     * <p>用 PUT 而非 PATCH：這是整份設定的取代，重複送出同一個請求結果相同。
     * {@code type = null} 代表清除設定。
     */
    @PutMapping("/clients/{clientId}/default-target")
    public ApiResponse<AdminDto.ClientSummary> setDefaultTarget(
            @PathVariable String clientId,
            @Valid @RequestBody AdminDto.SetDefaultTargetRequest body) {

        return ApiResponse.ok(
                adminService.setDefaultTarget(clientId, body.type(), body.userIds()));
    }
}
