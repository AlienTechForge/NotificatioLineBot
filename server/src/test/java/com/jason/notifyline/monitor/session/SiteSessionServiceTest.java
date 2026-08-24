package com.jason.notifyline.monitor.session;

import com.jason.notifyline.auth.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SiteSessionService} 的單元測試——真的 {@link SecretCipher}／
 * {@link ObjectMapper}（純記憶體運算，不需要 Spring），{@link SiteSessionRepository}
 * 用 Mockito mock。見 {@code Docs/plan/12-API監控易用性升級.md} §3、§5（W6 測試矩陣）。
 *
 * <p>host 比對本身（{@code evil-example.com} 那條關鍵案例）已經在
 * {@link HostMatcherTest} 窮舉覆蓋，這裡驗證的是「用到 host 比對之後」的行為：
 * 附加/合併/匯入的資料流是否正確、加密是否真的發生、Domain 檢查是否真的接了
 * {@link HostMatcher}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SiteSessionService")
class SiteSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final byte[] KEY = new byte[32]; // 全零測試金鑰，僅供測試用

    @Mock
    private SiteSessionRepository repository;

    private SiteSessionService service;
    private SecretCipher secretCipher;

    @BeforeEach
    void setUp() {
        secretCipher = new SecretCipher(Map.of(1, KEY), 1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new SiteSessionService(repository, secretCipher, new ObjectMapper(), clock);
    }

    private SiteSession jarFor(String host, Map<String, String> cookies, Instant now) {
        String plaintext = new ObjectMapper().writeValueAsString(cookies);
        var encrypted = secretCipher.encrypt(plaintext, "site_session:" + host);
        return new SiteSession(host, encrypted.ciphertext(), encrypted.iv(), encrypted.keyVersion(),
                String.join(",", cookies.keySet()), now);
    }

    // ------------------------------------------------------------ attachCookies：host 比對

    @Test
    @DisplayName("★ jar 是 example.com，請求 evil-example.com 不得附加任何 cookie")
    void attachCookies_evilPrefixHost_doesNotAttach() {
        SiteSession jar = jarFor("example.com", Map.of("session", "secret-token"), NOW);
        when(repository.findAll()).thenReturn(List.of(jar));

        Map<String, String> result = service.attachCookies("evil-example.com", Map.of());

        assertThat(result).doesNotContainKey("Cookie");
        assertThat(result).doesNotContainKey("cookie");
    }

    @Test
    @DisplayName("子網域 api.example.com 應附加 example.com 的 cookie")
    void attachCookies_subdomain_attaches() {
        SiteSession jar = jarFor("example.com", Map.of("session", "abc"), NOW);
        when(repository.findAll()).thenReturn(List.of(jar));

        Map<String, String> result = service.attachCookies("api.example.com", Map.of());

        assertThat(result.get("Cookie")).isEqualTo("session=abc");
    }

    @Test
    @DisplayName("完全相同的 host 應附加")
    void attachCookies_exactHost_attaches() {
        SiteSession jar = jarFor("example.com", Map.of("session", "abc"), NOW);
        when(repository.findAll()).thenReturn(List.of(jar));

        Map<String, String> result = service.attachCookies("example.com", Map.of());

        assertThat(result.get("Cookie")).isEqualTo("session=abc");
    }

    @Test
    @DisplayName("沒有符合的 jar：原樣回傳 header，不新增 Cookie key")
    void attachCookies_noMatchingJar_returnsHeadersUnchanged() {
        when(repository.findAll()).thenReturn(List.of());

        Map<String, String> result = service.attachCookies("example.com", Map.of("Accept", "application/json"));

        assertThat(result).isEqualTo(Map.of("Accept", "application/json"));
    }

    @Test
    @DisplayName("header 裡已經有 Cookie（大小寫不拘）：尊重既有值，不覆蓋，也不查 jar")
    void attachCookies_existingCookieHeader_isNotOverwritten() {
        Map<String, String> result = service.attachCookies("example.com", Map.of("cookie", "manual=1"));

        assertThat(result).isEqualTo(Map.of("cookie", "manual=1"));
        verify(repository, never()).findAll();
    }

    // ------------------------------------------------------------ mergeSetCookies

    @Test
    @DisplayName("Set-Cookie 的 Domain 指向別的網域：忽略該筆，jar 不更新")
    void mergeSetCookies_domainPointsElsewhere_isIgnored() {
        SiteSession jar = jarFor("example.com", Map.of("session", "old"), NOW);
        when(repository.findAll()).thenReturn(List.of(jar));

        service.mergeSetCookies("example.com", List.of("evil=1; Domain=evil.example"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Set-Cookie 刷新：更新 jar 內容與 last_refreshed_at")
    void mergeSetCookies_refreshesJarAndLastRefreshedAt() {
        Instant createdAt = NOW.minusSeconds(3600);
        SiteSession jar = jarFor("example.com", Map.of("session", "old"), createdAt);
        when(repository.findAll()).thenReturn(List.of(jar));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.mergeSetCookies("example.com", List.of("session=refreshed; Path=/"));

        ArgumentCaptor<SiteSession> captor = ArgumentCaptor.forClass(SiteSession.class);
        verify(repository).save(captor.capture());
        SiteSession saved = captor.getValue();
        assertThat(saved.getLastRefreshedAt()).isEqualTo(NOW);
        assertThat(saved.getCookieNames()).isEqualTo("session");

        Map<String, String> decrypted = decrypt(saved);
        assertThat(decrypted.get("session")).isEqualTo("refreshed");
    }

    @Test
    @DisplayName("Set-Cookie 沒有 Domain 屬性：視為 host-only cookie，正常合併")
    void mergeSetCookies_withoutDomainAttribute_merges() {
        SiteSession jar = jarFor("example.com", Map.of(), NOW.minusSeconds(10));
        when(repository.findAll()).thenReturn(List.of(jar));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.mergeSetCookies("example.com", List.of("csrf=token123"));

        ArgumentCaptor<SiteSession> captor = ArgumentCaptor.forClass(SiteSession.class);
        verify(repository).save(captor.capture());
        assertThat(decrypt(captor.getValue())).containsEntry("csrf", "token123");
    }

    @Test
    @DisplayName("找不到既有 jar：merge-back 整批放棄，不會憑空建立新的一筆")
    void mergeSetCookies_noExistingJar_doesNothing() {
        when(repository.findAll()).thenReturn(List.of());

        service.mergeSetCookies("never-imported.example", List.of("a=1"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("多筆 Set-Cookie 混合：Domain 合法的合併，Domain 不合法的個別忽略")
    void mergeSetCookies_mixedBatch_onlyValidOnesApplied() {
        SiteSession jar = jarFor("example.com", Map.of(), NOW.minusSeconds(10));
        when(repository.findAll()).thenReturn(List.of(jar));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.mergeSetCookies("example.com", List.of(
                "good=1; Domain=example.com",
                "bad=2; Domain=evil.example",
                "hostOnly=3"));

        ArgumentCaptor<SiteSession> captor = ArgumentCaptor.forClass(SiteSession.class);
        verify(repository).save(captor.capture());
        Map<String, String> decrypted = decrypt(captor.getValue());
        assertThat(decrypted).containsEntry("good", "1").containsEntry("hostOnly", "3");
        assertThat(decrypted).doesNotContainKey("bad");
    }

    // ------------------------------------------------------------ importCookies

    @Test
    @DisplayName("匯入：沒有既有 jar 時會建立新的一筆")
    void importCookies_createsNewJarWhenNoneExists() {
        when(repository.findById("example.com")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importCookies("example.com", "session=abc; csrf=def");

        ArgumentCaptor<SiteSession> captor = ArgumentCaptor.forClass(SiteSession.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getHost()).isEqualTo("example.com");
        assertThat(decrypt(captor.getValue())).containsEntry("session", "abc").containsEntry("csrf", "def");
    }

    @Test
    @DisplayName("匯入：既有 jar 時覆蓋同名 cookie，其餘保留")
    void importCookies_overwritesExistingCookiesByName() {
        SiteSession existing = jarFor("example.com", Map.of("session", "old", "other", "keep"), NOW.minusSeconds(10));
        when(repository.findById("example.com")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importCookies("example.com", "session=new");

        ArgumentCaptor<SiteSession> captor = ArgumentCaptor.forClass(SiteSession.class);
        verify(repository).save(captor.capture());
        Map<String, String> decrypted = decrypt(captor.getValue());
        assertThat(decrypted).containsEntry("session", "new").containsEntry("other", "keep");
    }

    @Test
    @DisplayName("匯入的 host 未正規化也會被正規化後當主鍵查詢")
    void importCookies_normalizesHostBeforeLookup() {
        when(repository.findById("example.com")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importCookies("EXAMPLE.COM.", "a=1");

        ArgumentCaptor<SiteSession> captor = ArgumentCaptor.forClass(SiteSession.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getHost()).isEqualTo("example.com");
    }

    // ------------------------------------------------------------ 後台管理

    @Test
    @DisplayName("list()：依 host 排序")
    void list_sortsByHost() {
        when(repository.findAll()).thenReturn(List.of(
                jarFor("zeta.example", Map.of(), NOW), jarFor("alpha.example", Map.of(), NOW)));

        List<SiteSession> result = service.list();

        assertThat(result).extracting(SiteSession::getHost).containsExactly("alpha.example", "zeta.example");
    }

    @Test
    @DisplayName("delete()：存在則刪除")
    void delete_existing_deletes() {
        when(repository.existsById("example.com")).thenReturn(true);

        service.delete("example.com");

        verify(repository).deleteById("example.com");
    }

    @Test
    @DisplayName("delete()：不存在則丟 404")
    void delete_missing_throwsNotFound() {
        when(repository.existsById("missing.example")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete("missing.example"))
                .isInstanceOf(com.jason.notifyline.common.ApiException.class);
        verify(repository, never()).deleteById(any());
    }

    // ------------------------------------------------------------ 加密

    @Test
    @DisplayName("cookie 值加密落地：密文裡看不到明文值")
    void persistedJar_ciphertextDoesNotContainPlaintextValue() {
        when(repository.findById("example.com")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.importCookies("example.com", "session=super-secret-value");

        ArgumentCaptor<SiteSession> captor = ArgumentCaptor.forClass(SiteSession.class);
        verify(repository).save(captor.capture());
        byte[] ciphertext = captor.getValue().getJarCiphertext();
        assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("super-secret-value");
    }

    private Map<String, String> decrypt(SiteSession jar) {
        String plaintext = secretCipher.decrypt(
                jar.getJarCiphertext(), jar.getJarIv(), jar.getJarKeyVersion(), "site_session:" + jar.getHost());
        return new ObjectMapper().readValue(plaintext, Map.class);
    }
}
