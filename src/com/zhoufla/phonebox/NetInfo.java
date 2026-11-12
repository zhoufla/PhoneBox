package com.zhoufla.phonebox;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 枚举本机可给面板使用的地址。IPv4 在前，IPv6 在后。 */
public final class NetInfo {

    public static final class Addr {
        public final String iface;
        public final String ip;
        public final String url;

        Addr(String iface, String ip, String url) {
            this.iface = iface;
            this.ip = ip;
            this.url = url;
        }
    }

    private NetInfo() {
    }

    public static List<Addr> addresses(int port) {
        Map<String, Addr> map = new LinkedHashMap<String, Addr>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return new ArrayList<Addr>();
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress address = ia.getAddress();
                    if (address == null || address.isAnyLocalAddress()
                            || address.isLoopbackAddress() || address.isMulticastAddress()) {
                        continue;
                    }
                    if (address instanceof Inet4Address) {
                        if (address.isLinkLocalAddress()) {
                            continue;
                        }
                        String ip = address.getHostAddress();
                        if (!map.containsKey(ip)) {
                            map.put(ip, new Addr(ni.getName(), ip,
                                    "http://" + ip + ":" + port));
                        }
                    } else if (address instanceof Inet6Address) {
                        if (address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                            continue;
                        }
                        String ip = address.getHostAddress();
                        int zone = ip.indexOf('%');
                        if (zone >= 0) {
                            ip = ip.substring(0, zone);
                        }
                        if (ip.isEmpty() || ip.equals("::")) {
                            continue;
                        }
                        // fc00::/7 是 ULA，不能当公网地址用
                        if (ip.startsWith("fc") || ip.startsWith("fd")) {
                            continue;
                        }
                        if (!map.containsKey(ip)) {
                            map.put(ip, new Addr(ni.getName(), ip,
                                    "http://[" + ip + "]:" + port));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logs.add("枚举网卡失败：" + e);
        }

        List<Addr> list = new ArrayList<Addr>(map.values());
        Collections.sort(list, new Comparator<Addr>() {
            @Override
            public int compare(Addr a, Addr b) {
                int av = a.ip.indexOf(':') >= 0 ? 1 : 0;
                int bv = b.ip.indexOf(':') >= 0 ? 1 : 0;
                if (av != bv) {
                    return av - bv;
                }
                return a.ip.compareTo(b.ip);
            }
        });
        return list;
    }
}
