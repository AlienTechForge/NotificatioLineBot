package com.jason.notifyline.lineuser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LineUser} 的狀態轉換。
 *
 * <p>這張表是所有發送的最終依據 —— 狀態錯了就會對已封鎖的人一直送（浪費額度），
 * 或漏掉真正的好友。
 */
@DisplayName("LineUser")
class LineUserTest {

    private static final String USER_ID = "U0000000000000000000000000000000a";
    private static final Instant T0 = Instant.parse("2026-08-18T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-18T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-18T02:00:00Z");

    @Test
    @DisplayName("新建立的使用者是 ACTIVE，且記下 followed_at")
    void newUser_isActiveWithFollowedAt() {
        LineUser user = new LineUser(USER_ID, T0);

        assertThat(user.getLineUserId()).isEqualTo(USER_ID);
        assertThat(user.getStatus()).isEqualTo(LineUserStatus.ACTIVE);
        assertThat(user.isActive()).isTrue();
        assertThat(user.isOwner()).isFalse();
        assertThat(user.getFollowedAt()).isEqualTo(T0);
        assertThat(user.getCreatedAt()).isEqualTo(T0);
        assertThat(user.getUpdatedAt()).isEqualTo(T0);
        assertThat(user.getUnfollowedAt()).isNull();
        assertThat(user.getProfileSyncedAt()).isNull();
    }

    @Test
    @DisplayName("封鎖：狀態變 BLOCKED 並記下 unfollowed_at")
    void block_marksBlocked() {
        LineUser user = new LineUser(USER_ID, T0);

        user.block(T1);

        assertThat(user.getStatus()).isEqualTo(LineUserStatus.BLOCKED);
        assertThat(user.isActive()).isFalse();
        assertThat(user.getUnfollowedAt()).isEqualTo(T1);
        assertThat(user.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("解除封鎖後重新加好友：回到 ACTIVE 並清掉 unfollowed_at")
    void followAfterBlock_returnsToActiveAndClearsUnfollowedAt() {
        LineUser user = new LineUser(USER_ID, T0);
        user.block(T1);

        user.follow(T2);

        assertThat(user.getStatus()).isEqualTo(LineUserStatus.ACTIVE);
        assertThat(user.getFollowedAt()).isEqualTo(T2);
        // 殘留的 unfollowed_at 會讓「這人何時封鎖的」查詢說謊
        assertThat(user.getUnfollowedAt()).isNull();
    }

    @Test
    @DisplayName("套用 profile：欄位可為 null，LINE 不保證都有值")
    void applyProfile_acceptsNullFields() {
        LineUser user = new LineUser(USER_ID, T0);

        user.applyProfile("Jason", "https://example.com/p.jpg", null, "zh-TW", T1);

        assertThat(user.getDisplayName()).isEqualTo("Jason");
        assertThat(user.getPictureUrl()).isEqualTo("https://example.com/p.jpg");
        assertThat(user.getStatusMessage()).isNull();
        assertThat(user.getLanguage()).isEqualTo("zh-TW");
        assertThat(user.getProfileSyncedAt()).isEqualTo(T1);
        assertThat(user.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("標記 owner：target=OWNER 的收件人來源")
    void setOwner_togglesOwnerFlag() {
        LineUser user = new LineUser(USER_ID, T0);

        user.setOwner(true, T1);
        assertThat(user.isOwner()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(T1);

        user.setOwner(false, T2);
        assertThat(user.isOwner()).isFalse();
    }

    @Test
    @DisplayName("toString 不輸出 profile 內容（可能含個資）")
    void toString_isMinimal() {
        LineUser user = new LineUser(USER_ID, T0);
        user.applyProfile("某位使用者", "https://example.com/p.jpg", "個人狀態訊息", "zh-TW", T1);

        assertThat(user.toString())
                .contains(USER_ID)
                .doesNotContain("某位使用者")
                .doesNotContain("個人狀態訊息");
    }
}
