package com.jason.notifyline.monitor.secret;

import com.jason.notifyline.auth.EncryptedSecret;
import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.common.ApiException;
import com.jason.notifyline.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 監控專屬 secret 的增／改／刪／查。見 {@code Docs/plan/13-監控計算欄位設計.md} §2.1、§7。
 *
 * <h2>AAD 陷阱——呼叫端必須先讓監控 id 存在</h2>
 *
 * <p>AAD 是 {@code "monitor_secret:" + monitorId + ":" + name}。跟
 * {@code AdminService.createMonitor} 加密 header 時一樣：{@code monitorId} 是
 * {@code BIGSERIAL}，新監控要等第一次 {@code save()} 之後才有值。<strong>呼叫端必須
 * 先把監控存好拿到 id，才能呼叫這個類別的任何寫入方法</strong>——這裡不會、也不能替
 * 呼叫端補救用錯誤 id（例如 {@code null}）加密出來的密文，那樣的密文永遠解不開。
 *
 * <h2>值只活在方法呼叫的堆疊上</h2>
 *
 * <p>{@link #decryptAll} 回傳的明文只給 {@code ApiMonitorStore}（排程輪詢時解密給
 * {@code ComputedFieldEvaluator} 用）與 {@code AdminService}（試算時解密既有值、跟使用者
 * 這次輸入的覆寫值合併）使用，<strong>絕不可再往外傳</strong>——不進任何 DTO、log、
 * {@code api_monitor_run}，也不會被這個類別自己記錄。
 */
@Service
public class MonitorSecretService {

    private static final Logger log = LoggerFactory.getLogger(MonitorSecretService.class);
    private static final String AAD_PREFIX = "monitor_secret:";

    private final MonitorSecretRepository repository;
    private final SecretCipher secretCipher;
    private final Clock clock;

    public MonitorSecretService(MonitorSecretRepository repository, SecretCipher secretCipher, Clock clock) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.clock = clock;
    }

    /** 這個監控目前設定的全部 secret 名稱，依名稱排序供後台顯示。絕不含值。 */
    @Transactional(readOnly = true)
    public List<String> listNames(Long monitorId) {
        return repository.findByMonitorId(monitorId).stream()
                .map(MonitorSecret::getName)
                .sorted()
                .toList();
    }

    /**
     * 解密這個監控的全部 secret，{@code name -> 明文}。給
     * {@code ApiMonitorStore.claim}（排程輪詢）與 {@code AdminService}（試算，讀既有值
     * 供未覆寫的欄位使用）使用，回傳值絕不可再往外傳，見類別註解。
     */
    @Transactional(readOnly = true)
    public Map<String, String> decryptAll(Long monitorId) {
        List<MonitorSecret> rows = repository.findByMonitorId(monitorId);
        Map<String, String> values = new LinkedHashMap<>();
        for (MonitorSecret row : rows) {
            values.put(row.getName(), decrypt(row));
        }
        return values;
    }

    /**
     * 新增或覆寫一批 secret。{@code values} 裡的每一筆都會真的被寫入——
     * 「留空 = 不變更」的判斷（同 header 的既有慣例）是呼叫端
     * （{@code AdminService}）的責任：把空白值先過濾掉，只把真的要新增／覆寫的
     * 非空項目傳進來。
     *
     * @param monitorId 呼叫前必須已經存在，見類別註解「AAD 陷阱」
     */
    @Transactional
    public void upsert(Long monitorId, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String plaintext = entry.getValue();
            EncryptedSecret encrypted = secretCipher.encrypt(plaintext, aad(monitorId, name));
            MonitorSecret existing = repository.findById(new MonitorSecretId(monitorId, name)).orElse(null);
            if (existing == null) {
                repository.save(new MonitorSecret(
                        monitorId, name, encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), now));
            } else {
                existing.applyValue(encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), now);
                repository.save(existing);
            }
        }
        log.info("監控 secret 已新增／覆寫：monitorId={} names={}", monitorId, values.keySet());
    }

    /**
     * 刪除一筆 secret（不可回復）。
     *
     * @throws ApiException 該監控沒有這個名稱的 secret（404）
     */
    @Transactional
    public void delete(Long monitorId, String name) {
        MonitorSecretId id = new MonitorSecretId(monitorId, name);
        if (!repository.existsById(id)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No secret named \"" + name + "\" for this monitor.");
        }
        repository.deleteById(id);
        log.info("監控 secret 已刪除：monitorId={} name={}", monitorId, name);
    }

    private String decrypt(MonitorSecret row) {
        return secretCipher.decrypt(row.getCiphertext(), row.getIv(), row.getKeyVersion(),
                aad(row.getMonitorId(), row.getName()));
    }

    private static String aad(Long monitorId, String name) {
        return AAD_PREFIX + monitorId + ":" + name;
    }
}
