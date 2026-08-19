package com.jason.notifyline.lineuser;

import com.jason.notifyline.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.regex.Pattern;

/**
 * 啟動時把設定中的 LINE user 標記為 owner。
 *
 * <p><strong>為什麼需要</strong>：沒有任何 owner 的話 {@code target: OWNER} 會解析出
 * 空收件人，而那是最常見的用途（後端服務回報給管理者）。
 *
 * <p><strong>為什麼放在應用啟動而不是 Flyway migration</strong>：migration 應該與環境
 * 無關，而 {@code APP_OWNER_LINE_USER_ID} 是環境設定。寫進 migration 會讓它只在第一次
 * 建置時生效，日後改環境變數不會反映，而且同一份 migration 在不同環境會產生不同結果。
 *
 * <p>每次啟動都會執行，且是冪等的。
 */
@Component
public class OwnerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    /** LINE user id 的格式。擋掉設定檔裡的手誤，避免建出永遠收不到訊息的假使用者。 */
    private static final Pattern LINE_USER_ID = Pattern.compile("^U[0-9a-f]{32}$");

    private final AppProperties appProperties;
    private final LineUserRepository repository;
    private final Clock clock;

    public OwnerBootstrap(AppProperties appProperties, LineUserRepository repository, Clock clock) {
        this.appProperties = appProperties;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        // bootstrap CLI 是一次性維運指令，不該順便改 owner 標記
        if (args.containsOption("create-client")) {
            return;
        }
        applyAll(appProperties.ownerLineUserId());
    }

    /**
     * @param csv 逗號分隔的 LINE user id，可為 null 或空
     */
    public void applyAll(String csv) {
        if (csv == null || csv.isBlank()) {
            log.debug("未設定 app.owner-line-user-id，略過 owner 標記");
            return;
        }
        for (String raw : csv.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!LINE_USER_ID.matcher(id).matches()) {
                // 不中斷 —— 一個手誤不該讓其他有效的 owner 也沒被標記
                log.warn("app.owner-line-user-id 中有格式不合法的值，已略過：{}", id);
                continue;
            }
            apply(id);
        }
    }

    /**
     * 標記單一使用者為 owner。使用者不存在時建立。
     *
     * <p><strong>不會</strong>把已封鎖的使用者強制改回 ACTIVE —— 封鎖是使用者的意願，
     * 設定檔不該覆寫它。對已封鎖者發送會被 LINE 靜默丟棄（見 06 §1.3），
     * 強行改回 ACTIVE 只會讓名單說謊。
     */
    @Transactional
    public void apply(String lineUserId) {
        LineUser user = repository.findById(lineUserId).orElse(null);

        if (user == null) {
            // owner 通常在還沒加好友時就要先設定好，否則服務啟動後第一則
            // 回報通知就沒有收件人。這裡先建起來，之後 follow 事件會補齊 profile。
            user = new LineUser(lineUserId, clock.instant());
            user.setOwner(true, clock.instant());
            repository.save(user);
            log.info("已建立並標記 owner：lineUserId={}", lineUserId);
            return;
        }

        if (user.isOwner()) {
            log.debug("已經是 owner，無需變更：lineUserId={}", lineUserId);
            return;
        }

        user.setOwner(true, clock.instant());
        log.info("已標記為 owner：lineUserId={}", lineUserId);
    }
}
