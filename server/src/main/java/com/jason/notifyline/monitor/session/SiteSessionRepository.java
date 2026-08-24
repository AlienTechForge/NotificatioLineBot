package com.jason.notifyline.monitor.session;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code host}（已正規化）就是主鍵，見 {@link SiteSession} 類別註解。
 *
 * <p>沒有衍生查詢方法——{@link SiteSessionService} 找「涵蓋某個請求 host 的 jar」時
 * 需要比對子網域，不是單純的主鍵相等，得用 {@code findAll()} 掃描後過濾。這張表的
 * 規模是「使用者貼過幾個站的登入狀態」，全表掃描對單人／小團隊系統而言不成問題，
 * 抄的是 {@code ApiMonitorStore.loadSeenKeys} 的同一個理由。
 */
public interface SiteSessionRepository extends JpaRepository<SiteSession, String> {
}
