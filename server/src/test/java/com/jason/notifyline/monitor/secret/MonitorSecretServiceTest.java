package com.jason.notifyline.monitor.secret;

import com.jason.notifyline.auth.SecretCipher;
import com.jason.notifyline.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MonitorSecretService} 的單元測試——真的 {@link SecretCipher}（純記憶體運算，
 * 不需要 Spring），{@link MonitorSecretRepository} 用 Mockito mock。見
 * {@code Docs/plan/13-監控計算欄位設計.md} §2.1、§7、§8。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MonitorSecretService")
class MonitorSecretServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final byte[] KEY = new byte[32]; // 全零測試金鑰，僅供測試用
    private static final Long MONITOR_ID = 42L;

    @Mock
    private MonitorSecretRepository repository;

    private MonitorSecretService service;
    private SecretCipher secretCipher;

    @BeforeEach
    void setUp() {
        secretCipher = new SecretCipher(Map.of(1, KEY), 1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new MonitorSecretService(repository, secretCipher, clock);
    }

    private MonitorSecret secretFor(Long monitorId, String name, String plaintext) {
        var encrypted = secretCipher.encrypt(plaintext, "monitor_secret:" + monitorId + ":" + name);
        return new MonitorSecret(monitorId, name, encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(), NOW);
    }

    // ------------------------------------------------------------ decryptAll

    @Test
    @DisplayName("decryptAll：正確用 AAD = monitor_secret:{monitorId}:{name} 解出明文")
    void decryptAll_decryptsWithCorrectAad() {
        when(repository.findByMonitorId(MONITOR_ID)).thenReturn(List.of(
                secretFor(MONITOR_ID, "appsecret", "YWHZ@&mxZge1A@"),
                secretFor(MONITOR_ID, "deviceid", "2b34aabc-6d14-490e-b76a-254097095055")));

        Map<String, String> values = service.decryptAll(MONITOR_ID);

        assertThat(values).containsEntry("appsecret", "YWHZ@&mxZge1A@");
        assertThat(values).containsEntry("deviceid", "2b34aabc-6d14-490e-b76a-254097095055");
    }

    @Test
    @DisplayName("decryptAll：沒有任何 secret 時回傳空 map")
    void decryptAll_empty() {
        when(repository.findByMonitorId(MONITOR_ID)).thenReturn(List.of());

        assertThat(service.decryptAll(MONITOR_ID)).isEmpty();
    }

    @Test
    @DisplayName("★ AAD 綁 monitorId：密文若跟這一列自己的 monitorId 對不上，解密會失敗（不會回傳垃圾資料或竄改後的明文）")
    void decryptAll_wrongMonitorIdAad_fails() {
        // 密文是用 AAD "monitor_secret:1:appsecret" 加密的，但把它包進一筆 monitorId=2 的
        // MonitorSecret（模擬密文被搬到別的 monitor 名下）——decrypt() 一定是拿「這一列自己
        // 宣稱的」monitorId 組 AAD，兩者不符，GCM 的 authentication tag 驗證就會失敗。
        var encryptedForMonitor1 = secretCipher.encrypt("top-secret", "monitor_secret:1:appsecret");
        MonitorSecret mismatched = new MonitorSecret(2L, "appsecret",
                encryptedForMonitor1.ciphertext(), encryptedForMonitor1.iv(), encryptedForMonitor1.keyVersion(), NOW);
        when(repository.findByMonitorId(2L)).thenReturn(List.of(mismatched));

        assertThatThrownBy(() -> service.decryptAll(2L)).isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------ listNames

    @Test
    @DisplayName("listNames：只回傳名稱，依名稱排序，不含值")
    void listNames_sortedNamesOnly() {
        when(repository.findByMonitorId(MONITOR_ID)).thenReturn(List.of(
                secretFor(MONITOR_ID, "zeta", "z-value"),
                secretFor(MONITOR_ID, "alpha", "a-value")));

        assertThat(service.listNames(MONITOR_ID)).containsExactly("alpha", "zeta");
    }

    // ------------------------------------------------------------ upsert

    @Test
    @DisplayName("upsert：新名稱 → save 一筆新的 MonitorSecret")
    void upsert_newName_savesNewRow() {
        when(repository.findById(new MonitorSecretId(MONITOR_ID, "appsecret"))).thenReturn(Optional.empty());

        service.upsert(MONITOR_ID, Map.of("appsecret", "plain-value"));

        ArgumentCaptor<MonitorSecret> captor = ArgumentCaptor.forClass(MonitorSecret.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMonitorId()).isEqualTo(MONITOR_ID);
        assertThat(captor.getValue().getName()).isEqualTo("appsecret");
        // 解回來確認密文真的是這個明文，且用對了 AAD。
        String decrypted = secretCipher.decrypt(captor.getValue().getCiphertext(), captor.getValue().getIv(),
                captor.getValue().getKeyVersion(), "monitor_secret:" + MONITOR_ID + ":appsecret");
        assertThat(decrypted).isEqualTo("plain-value");
    }

    @Test
    @DisplayName("upsert：既有名稱 → 覆寫既有那一筆（applyValue），不新建")
    void upsert_existingName_overwritesRow() {
        MonitorSecret existing = secretFor(MONITOR_ID, "appsecret", "old-value");
        when(repository.findById(new MonitorSecretId(MONITOR_ID, "appsecret"))).thenReturn(Optional.of(existing));

        service.upsert(MONITOR_ID, Map.of("appsecret", "new-value"));

        verify(repository).save(existing);
        String decrypted = secretCipher.decrypt(existing.getCiphertext(), existing.getIv(),
                existing.getKeyVersion(), "monitor_secret:" + MONITOR_ID + ":appsecret");
        assertThat(decrypted).isEqualTo("new-value");
    }

    @Test
    @DisplayName("upsert：null／空 map 不做任何事")
    void upsert_nullOrEmpty_noOp() {
        service.upsert(MONITOR_ID, null);
        service.upsert(MONITOR_ID, Map.of());

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("upsert：一次可以新增／覆寫多筆")
    void upsert_multipleEntries() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        service.upsert(MONITOR_ID, Map.of("appsecret", "a", "deviceid", "d"));

        verify(repository, times(2)).save(any());
    }

    // ------------------------------------------------------------ delete

    @Test
    @DisplayName("delete：存在時刪除")
    void delete_existing_deletes() {
        MonitorSecretId id = new MonitorSecretId(MONITOR_ID, "appsecret");
        when(repository.existsById(id)).thenReturn(true);

        service.delete(MONITOR_ID, "appsecret");

        verify(repository).deleteById(id);
    }

    @Test
    @DisplayName("delete：不存在 → 404 ApiException，不呼叫 deleteById")
    void delete_missing_throws() {
        MonitorSecretId id = new MonitorSecretId(MONITOR_ID, "nope");
        when(repository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(MONITOR_ID, "nope")).isInstanceOf(ApiException.class);
        verify(repository, never()).deleteById(eq(id));
    }
}
