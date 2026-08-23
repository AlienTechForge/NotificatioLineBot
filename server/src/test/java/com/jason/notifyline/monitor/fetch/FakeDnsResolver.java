package com.jason.notifyline.monitor.fetch;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DnsResolver} 的測試替身。不打真的 DNS，回傳事先掛好的位址 ——
 * 讓 {@code OutboundUrlGuardTest} 的每個案例都是確定性的，不依賴外部網路
 * 或某個網域「這次」解析到哪個 IP。
 *
 * <p>{@link OutboundUrlGuard} 是拿<strong>正規化後</strong>（IDN 轉 ASCII、轉小寫）
 * 的 host 去查詢，不是原始輸入。測 IDN／大小寫正規化本身時，測試不該同時要求呼叫端
 * 精確猜中正規化後的字串長什麼樣子 —— 那樣測到的其實是「兩處常數字串是否手動對齊」，
 * 不是正規化邏輯本身。這種情境用 {@link #withAnyHost} 讓 DNS 解析永遠成功，
 * 才能單純聚焦在白名單比對那一段邏輯上。
 */
final class FakeDnsResolver implements DnsResolver {

    private final Map<String, List<InetAddress>> byHost = new HashMap<>();
    private List<InetAddress> anyHost;

    FakeDnsResolver with(String host, InetAddress... addresses) {
        byHost.put(host, List.of(addresses));
        return this;
    }

    FakeDnsResolver withAnyHost(InetAddress... addresses) {
        this.anyHost = List.of(addresses);
        return this;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        List<InetAddress> found = byHost.getOrDefault(host, anyHost);
        if (found == null) {
            throw new UnknownHostException(host);
        }
        return found.toArray(new InetAddress[0]);
    }
}
