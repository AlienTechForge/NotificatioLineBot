package com.jason.notifyline.monitor.fetch;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** {@link DnsResolver} 的真實實作，直接委派給 JDK 的名稱服務。 */
@Component
public class SystemDnsResolver implements DnsResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
