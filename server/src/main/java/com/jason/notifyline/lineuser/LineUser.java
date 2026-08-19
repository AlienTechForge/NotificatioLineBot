package com.jason.notifyline.lineuser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Bot 好友。所有發送的最終依據都是這張表的 {@code line_user_id}。
 *
 * <p>schema 見 {@code Docs/plan/04-資料模型.md} §2。
 *
 * <p>用 LINE 給的 id 當主鍵而非代理鍵 —— 它本來就是穩定且唯一的外部識別碼，
 * 多一層代理鍵只會讓每次發送都要多做一次 join。
 */
@Entity
@Table(name = "line_user")
public class LineUser {

    /** {@code U[0-9a-f]{32}}。 */
    @Id
    @Column(name = "line_user_id", length = 64)
    private String lineUserId;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "picture_url")
    private String pictureUrl;

    @Column(name = "status_message")
    private String statusMessage;

    @Column(name = "language", length = 16)
    private String language;

    @Column(name = "is_owner", nullable = false)
    private boolean owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LineUserStatus status;

    @Column(name = "followed_at")
    private Instant followedAt;

    @Column(name = "unfollowed_at")
    private Instant unfollowedAt;

    @Column(name = "profile_synced_at")
    private Instant profileSyncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LineUser() {
        // JPA
    }

    public LineUser(String lineUserId, Instant now) {
        this.lineUserId = lineUserId;
        this.status = LineUserStatus.ACTIVE;
        this.followedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------- 狀態轉換

    /** 加好友或解除封鎖。 */
    public void follow(Instant now) {
        this.status = LineUserStatus.ACTIVE;
        this.followedAt = now;
        this.unfollowedAt = null;
        this.updatedAt = now;
    }

    /** 封鎖官方帳號，或 Profile API 回 404（帳號已刪除）。 */
    public void block(Instant now) {
        this.status = LineUserStatus.BLOCKED;
        this.unfollowedAt = now;
        this.updatedAt = now;
    }

    /** Profile API 同步結果。任一欄位可能為 null，LINE 不保證都有值。 */
    public void applyProfile(String displayName, String pictureUrl, String statusMessage,
                             String language, Instant now) {
        this.displayName = displayName;
        this.pictureUrl = pictureUrl;
        this.statusMessage = statusMessage;
        this.language = language;
        this.profileSyncedAt = now;
        this.updatedAt = now;
    }

    public void setOwner(boolean owner, Instant now) {
        this.owner = owner;
        this.updatedAt = now;
    }

    // ------------------------------------------------------------------ 查詢

    public boolean isActive() {
        return status == LineUserStatus.ACTIVE;
    }

    // ---------------------------------------------------------------- getters

    public String getLineUserId() {
        return lineUserId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPictureUrl() {
        return pictureUrl;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isOwner() {
        return owner;
    }

    public LineUserStatus getStatus() {
        return status;
    }

    public Instant getFollowedAt() {
        return followedAt;
    }

    public Instant getUnfollowedAt() {
        return unfollowedAt;
    }

    public Instant getProfileSyncedAt() {
        return profileSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "LineUser[" + lineUserId + " status=" + status + " owner=" + owner + "]";
    }
}
