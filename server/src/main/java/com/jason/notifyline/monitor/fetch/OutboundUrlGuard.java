package com.jason.notifyline.monitor.fetch;

import com.jason.notifyline.monitor.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 出站請求的目標網址檢查。見 {@code Docs/plan/11-API監控輪詢設計.md} §5。
 *
 * <p><strong>威脅模型</strong>：這個功能會把抓回來的內容渲染進 LINE 訊息、寫進後台
 * 執行紀錄。它<strong>不是盲 SSRF</strong>，是<strong>附帶完整回傳通道的任意網址讀取
 * 器</strong>。「網址是管理員自己在後台設的」描述的是今天的狀態，不是控制措施 ——
 * 這裡的每一條規則都要在<strong>後台被攻破之後</strong>仍然成立，才算數。
 *
 * <h2>四道強制檢查（無開關）</h2>
 *
 * <ol>
 *   <li>只允許 {@code https}。其他 scheme（含 {@code http}）一律拒絕 —— 明文請求
 *       在網路路徑上可被竄改，而且很多內網服務只在 {@code http} 上監聽。</li>
 *   <li>不跟隨 redirect（{@code ApiFetcher} 把 {@code HttpClient} 設成
 *       {@code Redirect.NEVER}，3xx 一律視為失敗）。這裡不重複做 —— 但理由要說清楚：
 *       這個類別驗證的是「使用者填的網址」，不是「實際連線最終落腳的網址」。如果
 *       {@code ApiFetcher} 跟了 redirect，這裡做的所有檢查都會被一跳繞過，等於沒做。</li>
 *   <li>解析 DNS，檢查<strong>每一個</strong>回傳的位址。任何一個落在封鎖範圍就整個
 *       拒絕 —— round-robin DNS 可以只讓其中一筆指向內網，只看第一筆會被繞過。</li>
 *   <li>IPv4-mapped（{@code ::ffff:0:0/96}）與 NAT64（{@code 64:ff9b::/96}）位址
 *       要先還原成 IPv4 再檢查一次 —— {@code ::ffff:10.0.0.1} 就是內網位址
 *       {@code 10.0.0.1}，直接拿 IPv6 的封鎖清單比對會漏放它過去。</li>
 * </ol>
 *
 * <p><strong>不可只依賴</strong> {@link InetAddress#isSiteLocalAddress()}、
 * {@link InetAddress#isLoopbackAddress()}、{@link InetAddress#isLinkLocalAddress()}
 * 這些內建方法：它們不涵蓋 CGNAT {@code 100.64.0.0/10}、{@code 192.0.0.0/24}，也不會
 * 做 IPv4-mapped 還原。這裡改用顯式的 CIDR 範圍表逐一比對。
 *
 * <p>{@code 169.254.0.0/16} 是<strong>最重要的一條</strong> —— 雲端 metadata endpoint
 * （{@code 169.254.169.254}）會吐出 instance 的 IAM 憑證，是這整套檢查存在的
 * 首要理由。
 *
 * <h2>選配：host 白名單</h2>
 *
 * <p>{@code app.monitor.allowed-hosts} 空白代表<strong>允許任何公開網域</strong>
 * （仍受上面四道 IP 層檢查全面約束）—— 這是刻意與
 * {@link com.jason.notifyline.notification.dispatch.UriHostValidator} 相反的預設
 * 值語意，因為兩者防的是不同的威脅：{@code UriHostValidator} 擋的是「外洩金鑰夾帶
 * 釣魚連結」，空白名單=全擋是安全的預設；這裡擋的是「打到內網」，IP 層檢查已經是
 * 那條防線，host 白名單只是給單人 / 小團隊情境的選配收斂，空白名單=全擋會讓這個
 * 功能對多數人來說開箱即用不了。
 *
 * <p>比對邏輯沿用
 * {@link com.jason.notifyline.notification.dispatch.UriHostValidator#isAllowed}
 * （見該類別第 151 行）的精確語意：完全相等或帶點號邊界的子網域，<strong>絕不用
 * {@code endsWith}</strong>（那會讓 {@code evil-example.com} 誤配到白名單裡的
 * {@code example.com}），並在比對前用 {@link IDN#toASCII} 正規化、以
 * {@link Locale#ROOT} 轉小寫，避免 Unicode 同形字或土耳其語系 locale 造成繞過或
 * 誤判。因為那個方法是 private，這裡照同樣規則重寫一份，不是引用。
 *
 * <h2>殘留風險：DNS rebinding（刻意不處理）</h2>
 *
 * <p>這裡檢查通過之後、{@code ApiFetcher} 實際建立連線之前，DNS 紀錄理論上可能被
 * 改指向內網（TOCTOU）。完整解法需要把已驗證過的 IP 直接 pin 進連線用的
 * socket factory，v1 不做 —— 這四道檢查已經擋掉絕大多數實際可利用的路徑，而自訂
 * socket factory 會大幅增加複雜度，換來的邊際安全提升在這個功能的風險等級下不值得。
 * <strong>這是刻意的取捨，不是遺漏。</strong>
 */
@Component
public class OutboundUrlGuard {

    private static final Logger log = LoggerFactory.getLogger(OutboundUrlGuard.class);

    /** 封鎖範圍。見 §5.1 的表格，逐條對應。 */
    private static final List<BlockedRange> BLOCKED_IPV4 = List.of(
            range("0.0.0.0/8"),
            range("10.0.0.0/8"),
            range("100.64.0.0/10"),      // CGNAT —— InetAddress 內建方法不涵蓋這條
            range("127.0.0.0/8"),
            range("169.254.0.0/16"),     // 雲端 metadata endpoint，最重要的一條
            range("172.16.0.0/12"),
            range("192.0.0.0/24"),       // InetAddress 內建方法也不涵蓋這條
            range("192.0.2.0/24"),       // TEST-NET-1
            range("192.168.0.0/16"),
            range("198.18.0.0/15"),      // benchmark 測試網段
            range("198.51.100.0/24"),    // TEST-NET-2
            range("203.0.113.0/24"),     // TEST-NET-3
            range("224.0.0.0/4"),        // multicast
            range("240.0.0.0/4"),        // reserved
            range("255.255.255.255/32"));

    private static final List<BlockedRange> BLOCKED_IPV6 = List.of(
            range("::/128"),             // unspecified
            range("::1/128"),            // loopback
            range("fc00::/7"),           // ULA
            range("fe80::/10"),          // link-local
            range("ff00::/8"),           // multicast
            range("2001:db8::/32"));     // documentation

    /**
     * IPv4-mapped 前綴：{@code ::ffff:0:0/96}。命中後把後 4 byte 當 IPv4 重新檢查。
     *
     * <p>刻意手刻位元組，不透過 {@link #range} 用 {@code InetAddress.getByName}
     * 剖析 —— JDK 對 {@code "::ffff:0:0"} 這個文字表示法會直接解析成 4 byte 的
     * {@link java.net.Inet4Address}（{@code 0.0.0.0}），根本拿不到我們要比對的
     * 16 byte /96 前綴，{@code inRange} 拿去跟一個真正 16 byte 的位址比對時會
     * 陣列越界。這個行為也代表：JDK 在文字剖析與 {@code InetAddress.getByAddress}
     * 兩條路徑上，其實都會把 IPv4-mapped 位址直接還原成
     * {@link java.net.Inet4Address} —— 也就是說下面 Inet6Address 分支對
     * IPv4-mapped 的處理，在正常的 JDK 行為下大概率走不到，是留給非標準
     * {@code InetAddress} 實作或手動建構情境的防禦性程式碼。
     */
    private static final byte[] IPV4_MAPPED_PREFIX = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0
    };

    /** NAT64 前綴：{@code 64:ff9b::/96}。命中後把後 4 byte 當 IPv4 重新檢查。 */
    private static final byte[] NAT64_PREFIX = {
            0x00, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private final MonitorProperties properties;
    private final DnsResolver dnsResolver;

    public OutboundUrlGuard(MonitorProperties properties, DnsResolver dnsResolver) {
        this.properties = properties;
        this.dnsResolver = dnsResolver;
    }

    /**
     * 檢查這個網址是否可以打。全部通過才會正常返回；任何一項不符合就丟
     * {@link BlockedException}。
     *
     * @throws BlockedException 訊息刻意寫得籠統 —— 呼叫端可以把它原封不動地存進
     *                           執行紀錄或回給前端，<strong>不會洩漏</strong>解析到
     *                           的內部位址。實際命中的範圍與位址只寫進伺服器端的
     *                           log（{@code WARN}），那才是排查問題該去看的地方。
     */
    public void check(URI uri) {
        if (uri == null) {
            throw blocked("URL is missing.", "null URI");
        }
        requireHttps(uri);
        String host = requireHost(uri);
        String normalizedHost = normalizeHost(host);
        checkAllowList(normalizedHost, host);

        InetAddress[] addresses = resolve(normalizedHost, host);
        for (InetAddress address : addresses) {
            String hitRange = blockedRangeLabel(address);
            if (hitRange != null) {
                // 細節（host、實際解析到的位址、命中的範圍）只留在伺服器端 log。
                // 回給呼叫端「resolved to 10.0.0.5, blocked」等於幫忙確認內網位址存在，
                // 這正是這個檢查要防的資訊外洩。
                log.warn("OutboundUrlGuard 擋下請求：host={} address={} range={}",
                        host, address.getHostAddress(), hitRange);
                throw blocked("Target host resolves to a disallowed network address.",
                        "host=" + host + " address=" + address.getHostAddress() + " range=" + hitRange);
            }
        }
    }

    private void requireHttps(URI uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            throw blocked("Only https URLs are permitted.", "scheme=" + scheme);
        }
    }

    private String requireHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // 例如底線造成 URI#getHost() 回 null：拒絕而不是略過，
            // 我們解析不出來不代表它解析不出實際的連線目標。
            throw blocked("URL host could not be determined.", "uri=" + uri);
        }
        return host;
    }

    /**
     * 選配白名單。空清單 = 允許任何公開網域（仍受 IP 層檢查全面約束）。
     */
    private void checkAllowList(String normalizedHost, String rawHost) {
        List<String> allowed = properties.allowedHostList();
        if (allowed.isEmpty()) {
            return;
        }
        for (String entry : allowed) {
            String candidate = normalizeHost(entry);
            // 完全相等，或帶點號邊界的子網域。不可只用 endsWith ——
            // 那會讓 evil-example.com 誤配到白名單裡的 example.com。
            if (normalizedHost.equals(candidate) || normalizedHost.endsWith("." + candidate)) {
                return;
            }
        }
        log.warn("OutboundUrlGuard 擋下請求：host={} 不在白名單內", rawHost);
        throw blocked("Target host is not in the allow-list.", "host=" + rawHost);
    }

    private InetAddress[] resolve(String host, String rawHost) {
        try {
            InetAddress[] addresses = dnsResolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw blocked("Could not resolve target host.", "host=" + rawHost + " (empty result)");
            }
            return addresses;
        } catch (UnknownHostException e) {
            throw blocked("Could not resolve target host.", "host=" + rawHost + " " + e);
        }
    }

    /**
     * @return 命中的封鎖範圍標籤（給 log 用），沒命中回傳 {@code null}
     */
    private static String blockedRangeLabel(InetAddress address) {
        if (address instanceof Inet4Address v4) {
            return matchLabel(v4.getAddress(), BLOCKED_IPV4);
        }
        if (address instanceof Inet6Address v6) {
            byte[] raw = v6.getAddress();
            byte[] embeddedIpv4 = unwrapEmbeddedIpv4(raw);
            if (embeddedIpv4 != null) {
                String label = matchLabel(embeddedIpv4, BLOCKED_IPV4);
                return label == null ? null : "IPv4-mapped/NAT64 -> " + label;
            }
            return matchLabel(raw, BLOCKED_IPV6);
        }
        // 目前 JDK 只有 Inet4Address / Inet6Address 兩種子類別。萬一將來多了新的
        // 位址家族，未知類型一律當作封鎖 —— 這種安全檢查該 fail closed，不是 fail open。
        return "unknown address family: " + address.getClass().getName();
    }

    /**
     * 還原 IPv4-mapped（{@code ::ffff:0:0/96}）或 NAT64（{@code 64:ff9b::/96}）
     * 位址內嵌的 IPv4。兩者都不命中則回傳 {@code null}（表示這是一個「正常」的 IPv6
     * 位址，不需要走 IPv4 還原路徑）。
     */
    private static byte[] unwrapEmbeddedIpv4(byte[] v6) {
        if (inRange(v6, IPV4_MAPPED_PREFIX, 96) || inRange(v6, NAT64_PREFIX, 96)) {
            return Arrays.copyOfRange(v6, 12, 16);
        }
        return null;
    }

    private static String matchLabel(byte[] address, List<BlockedRange> ranges) {
        for (BlockedRange r : ranges) {
            if (inRange(address, r.base(), r.prefixBits())) {
                return r.cidr();
            }
        }
        return null;
    }

    /**
     * 通用的 CIDR 比對，位元組陣列長度 4（IPv4）或 16（IPv6）都適用 —— 前面整數個
     * byte 逐一比對，最後不足一個 byte 的部分用位元遮罩比對。
     */
    private static boolean inRange(byte[] addr, byte[] base, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addr[i] != base[i]) {
                return false;
            }
        }
        if (remainingBits > 0) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            if ((addr[fullBytes] & mask) != (base[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }

    /**
     * IDN 正規化 + 小寫，語意抄
     * {@link com.jason.notifyline.notification.dispatch.UriHostValidator}
     * 的 {@code normalise}（該類別第 166 行）。{@link Locale#ROOT} 是刻意的 ——
     * 土耳其語系的 JVM 會把 {@code I} 轉成 {@code ı}，讓合法網域比對失敗。
     */
    private static String normalizeHost(String host) {
        String h = host.trim();
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        try {
            // 國際化網域名稱轉 ASCII，避免用 Unicode 同形字繞過白名單比對
            h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            log.debug("IDN 轉換失敗，改用原字串比對：{}", h);
        }
        return h.toLowerCase(Locale.ROOT);
    }

    private static BlockedException blocked(String publicMessage, String logDetail) {
        log.debug("OutboundUrlGuard 詳情（僅伺服器端）：{}", logDetail);
        return new BlockedException(publicMessage);
    }

    /** 一個字面量 CIDR range，例如 {@code "10.0.0.0/8"}。 */
    private static BlockedRange range(String cidr) {
        int slash = cidr.indexOf('/');
        String addressPart = cidr.substring(0, slash);
        int prefixBits = Integer.parseInt(cidr.substring(slash + 1));
        try {
            // addressPart 永遠是數字字面量（IPv4 點分十進位或 IPv6 文字表示法），
            // InetAddress#getByName 對字面量不會觸發真正的 DNS 查詢。
            InetAddress address = InetAddress.getByName(addressPart);
            return new BlockedRange(cidr, address.getAddress(), prefixBits);
        } catch (UnknownHostException e) {
            // 只會在上面硬編碼的字面量寫錯時發生 —— 等同編譯期錯誤，讓它直接炸掉
            // class 初始化，而不是在執行期才發現某條封鎖範圍悄悄失效。
            throw new ExceptionInInitializerError("invalid literal CIDR: " + cidr);
        }
    }

    private record BlockedRange(String cidr, byte[] base, int prefixBits) {
    }

    /**
     * 目標網址被擋下。訊息刻意籠統，見 {@link #check}。
     */
    public static final class BlockedException extends RuntimeException {

        public BlockedException(String message) {
            super(message);
        }
    }
}
