package com.jason.notifyline.monitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 單次輪詢的執行紀錄。schema 見 {@code V4__api_monitor.sql} §3.2。
 *
 * <p>寫入後不再修改 —— 一次執行的結果就是它最終的樣子，跟 {@code audit_log}
 * 一樣是純粹的歷史紀錄，所以沒有狀態轉換方法，只有建構子 + getters。
 */
@Entity
@Table(name = "api_monitor_run")
public class ApiMonitorRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private RunOutcome outcome;

    @Column(name = "http_status")
    private Integer httpStatus;

    /** 只能是分類與長度，不得含目標 API 回應內容全文。見 migration 註解。 */
    @Column(name = "error_message")
    private String errorMessage;

    /** 僅 {@code outcome = CHANGED} 且通過防洗版時有值。 */
    @Column(name = "notification_id")
    private UUID notificationId;

    protected ApiMonitorRun() {
        // JPA
    }

    public ApiMonitorRun(Long monitorId,
                         Instant startedAt,
                         Integer durationMs,
                         RunOutcome outcome,
                         Integer httpStatus,
                         String errorMessage,
                         UUID notificationId) {
        this.monitorId = monitorId;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.errorMessage = errorMessage;
        this.notificationId = notificationId;
    }

    // ---------------------------------------------------------------- getters

    public Long getId() {
        return id;
    }

    public Long getMonitorId() {
        return monitorId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public RunOutcome getOutcome() {
        return outcome;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    @Override
    public String toString() {
        return "ApiMonitorRun[monitor=" + monitorId + " outcome=" + outcome
                + " httpStatus=" + httpStatus + "]";
    }
}
