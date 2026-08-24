package com.jason.notifyline.admin;

import com.jason.notifyline.common.ApiResponse;
import com.jason.notifyline.monitor.importer.ImportedRequest;
import com.jason.notifyline.notification.api.NotificationAccepted;
import com.jason.notifyline.notification.api.NotificationDetail;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 管理 API，全部掛在 {@code /admin/api/**}。
 *
 * <p>認證由 {@link AdminSecurityConfig} 的 session filter chain 統一擋掉 ——
 * 能走到這個類別就代表已經是登入的管理者，所以這裡沒有任何 scope 檢查。
 *
 * <p>路徑前綴與認證方式一一對應：{@code /api/v1/**} 一律 HMAC，
 * {@code /admin/**} 一律 session。同一個能力不開放兩條認證路徑。
 */
@RestController
@RequestMapping("/admin/api")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // ------------------------------------------------------------ 儀表板

    @GetMapping("/stats")
    public ApiResponse<AdminDto.Stats> stats() {
        return ApiResponse.ok(adminService.stats());
    }

    /** LINE 官方帳號的月配額用量。獨立於 {@code /stats}，逾時失敗不影響其他卡片。 */
    @GetMapping("/line-quota")
    public ApiResponse<AdminDto.LineQuota> lineQuota() {
        return ApiResponse.ok(adminService.lineQuota());
    }

    // -------------------------------------------------------------- 憑證

    @GetMapping("/clients")
    public ApiResponse<List<AdminDto.ClientSummary>> listClients() {
        return ApiResponse.ok(adminService.listClients());
    }

    /**
     * 建立憑證。回 {@code 201} 且 body 含<strong>明文 secret（只此一次）</strong>。
     */
    @PostMapping("/clients")
    public ResponseEntity<ApiResponse<AdminDto.CreatedClient>> createClient(
            @Valid @RequestBody AdminDto.CreateClientRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(adminService.createClient(body)));
    }

    /** 作廢憑證（不可回復）。 */
    @DeleteMapping("/clients/{clientId}")
    public ApiResponse<Void> revokeClient(@PathVariable String clientId) {
        adminService.revokeClient(clientId);
        return ApiResponse.ok(null);
    }

    /**
     * 設定 client 的預設通知對象。PUT = 整份取代，{@code type = null} 清除。
     */
    @PutMapping("/clients/{clientId}/default-target")
    public ApiResponse<AdminDto.ClientSummary> setDefaultTarget(
            @PathVariable String clientId,
            @Valid @RequestBody AdminDto.SetDefaultTargetRequest body) {
        return ApiResponse.ok(
                adminService.setDefaultTarget(clientId, body.type(), body.userIds()));
    }

    // ------------------------------------------------------------ 使用者

    @GetMapping("/line-users")
    public ApiResponse<List<AdminDto.LineUserSummary>> listLineUsers() {
        return ApiResponse.ok(adminService.listActiveLineUsers());
    }

    /** 切換使用者的 owner 標記。 */
    @PutMapping("/line-users/{lineUserId}/owner")
    public ApiResponse<AdminDto.LineUserSummary> setOwner(
            @PathVariable String lineUserId,
            @RequestBody AdminDto.SetOwnerRequest body) {
        return ApiResponse.ok(adminService.setOwner(lineUserId, body.owner()));
    }

    // -------------------------------------------------------------- 發送

    /** 從後台直接發一則通知（測試用）。回 {@code 202}，與正式 API 一致。 */
    @PostMapping("/notifications/test")
    public ResponseEntity<ApiResponse<NotificationAccepted>> sendTest(
            @Valid @RequestBody AdminDto.SendTestRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(adminService.sendTest(body)));
    }

    // ------------------------------------------------------------ 發送紀錄

    @GetMapping("/notifications")
    public ApiResponse<List<AdminDto.NotificationSummary>> recentNotifications() {
        return ApiResponse.ok(adminService.recentNotifications());
    }

    @GetMapping("/notifications/{id}")
    public ApiResponse<NotificationDetail> notificationDetail(@PathVariable UUID id) {
        return ApiResponse.ok(adminService.notificationDetail(id));
    }

    // -------------------------------------------------------------- 排程

    /** 還沒到派送時間的排程通知，最快到期的排前面。 */
    @GetMapping("/notifications/scheduled")
    public ApiResponse<List<AdminDto.ScheduledNotification>> scheduledNotifications() {
        return ApiResponse.ok(adminService.listScheduled());
    }

    /** 取消一則排程。已經開始送（或已結束）的取消不到，回 400。 */
    @DeleteMapping("/notifications/{id}/schedule")
    public ApiResponse<Void> cancelScheduled(@PathVariable UUID id) {
        adminService.cancelScheduled(id);
        return ApiResponse.ok(null);
    }

    // -------------------------------------------------------------- 監控

    /** 列表，含執行狀態摘要（啟用狀態、連續失敗次數、最後/下次執行時間）。 */
    @GetMapping("/monitors")
    public ApiResponse<List<AdminDto.MonitorSummary>> listMonitors() {
        return ApiResponse.ok(adminService.listMonitors());
    }

    @PostMapping("/monitors")
    public ResponseEntity<ApiResponse<AdminDto.MonitorSummary>> createMonitor(
            @Valid @RequestBody AdminDto.CreateMonitorRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(adminService.createMonitor(body)));
    }

    @PutMapping("/monitors/{id}")
    public ApiResponse<AdminDto.MonitorSummary> updateMonitor(
            @PathVariable Long id, @Valid @RequestBody AdminDto.UpdateMonitorRequest body) {
        return ApiResponse.ok(adminService.updateMonitor(id, body));
    }

    @DeleteMapping("/monitors/{id}")
    public ApiResponse<Void> deleteMonitor(@PathVariable Long id) {
        adminService.deleteMonitor(id);
        return ApiResponse.ok(null);
    }

    /** 啟用／停用。 */
    @PostMapping("/monitors/{id}/enabled")
    public ApiResponse<AdminDto.MonitorSummary> setMonitorEnabled(
            @PathVariable Long id, @RequestBody AdminDto.SetEnabledRequest body) {
        return ApiResponse.ok(adminService.setMonitorEnabled(id, body.enabled()));
    }

    /**
     * 試跑：body 帶完整設定（未存檔也可），抓一次、回傳抽出的值與渲染後的訊息。
     * 不發送、不寫入任何狀態。走跟排程完全相同的 {@code OutboundUrlGuard} 檢查。
     *
     * <p>成功時回應帶著目標 API 的原始回應內容（見 {@link AdminDto.MonitorTestResult}
     * 類別註解），供後台畫成可展開的欄位選取樹——回應必須帶 {@code Cache-Control: no-store}，
     * 理由跟 {@link #importMonitor} 一樣：內容機敏，不可被瀏覽器或中介的快取留存。
     */
    @PostMapping("/monitors/test")
    public ResponseEntity<ApiResponse<AdminDto.MonitorTestResult>> testMonitor(
            @Valid @RequestBody AdminDto.MonitorTestRequest body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(adminService.testMonitor(body)));
    }

    /** 最近 50 筆執行紀錄。 */
    @GetMapping("/monitors/{id}/runs")
    public ApiResponse<List<AdminDto.MonitorRunSummary>> monitorRuns(@PathVariable Long id) {
        return ApiResponse.ok(adminService.monitorRuns(id));
    }

    /**
     * 匯入解析：把貼上的 cURL / {@code fetch(...)} / 自訂 JSON 解析成
     * {@link ImportedRequest}，<strong>不存檔</strong>。見
     * {@code Docs/plan/12-API監控易用性升級.md} §2.6。
     *
     * <p>回應帶 {@code Cache-Control: no-store}——貼上的內容含 cookie 與 API token，
     * 解析出來的結果同樣機敏，不可被瀏覽器或中介的快取留存。
     */
    @PostMapping("/monitors/import")
    public ResponseEntity<ApiResponse<ImportedRequest>> importMonitor(
            @Valid @RequestBody AdminDto.ImportMonitorRequest body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(adminService.importMonitorRequest(body.raw())));
    }

    // -------------------------------------------------------------- 站台登入狀態

    /** 列出各 host 的登入狀態：cookie 名稱、數量、時間戳。絕不回傳值。 */
    @GetMapping("/sessions")
    public ApiResponse<List<AdminDto.SiteSessionSummary>> listSessions() {
        return ApiResponse.ok(adminService.listSessions());
    }

    /** 清除某個 host 的登入狀態（不可回復）。 */
    @DeleteMapping("/sessions/{host}")
    public ApiResponse<Void> deleteSession(@PathVariable String host) {
        adminService.deleteSession(host);
        return ApiResponse.ok(null);
    }
}
