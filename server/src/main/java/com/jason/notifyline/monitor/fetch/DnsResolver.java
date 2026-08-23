package com.jason.notifyline.monitor.fetch;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * {@link OutboundUrlGuard} 用的 DNS 解析抽象介面。
 *
 * <p>存在的唯一理由是讓 {@code OutboundUrlGuardTest} 可以掛假的解析結果 ——
 * 真打 DNS 會讓單元測試依賴外部網路（CI 環境通常沒有對外連線）、變慢，而且
 * 「某網域這次解析到哪個 IP」本來就不是穩定、可重現的測試前提。真正的網路
 * 呼叫只留給 {@link SystemDnsResolver} 這一個實作。
 */
public interface DnsResolver {

    /**
     * 解析主機名稱得到<strong>所有</strong>回傳的位址。
     *
     * <p>呼叫端（{@link OutboundUrlGuard}）必須檢查回傳陣列裡的每一個位址，
     * 不能只看第一個 —— round-robin DNS 可能只讓其中一筆指向內網。
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
