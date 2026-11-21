package com.freesia.framework.web.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.List;

/** One literal-only trust rule for auditing and rate limits; never resolves DNS. */
public class ClientIpResolver {
    @ConfigurationProperties("freesia.web")
    public record Properties(List<String> trustedProxies) {
        public Properties { trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies); }
    }
    private record Network(byte[] address, int prefix) {}
    private final List<Network> trusted;
    public ClientIpResolver(Properties properties) {
        trusted = properties.trustedProxies().stream().map(value -> {
            String[] parts = value.split("/", -1);
            InetAddress ip = InetAddress.ofLiteral(parts[0]);
            int bits = parts.length == 1 ? ip.getAddress().length * 8 : Integer.parseInt(parts[1]);
            if (parts.length > 2 || bits < 0 || bits > ip.getAddress().length * 8) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR");
            }
            return new Network(ip.getAddress(), bits);
        }).toList();
    }
    public String resolve(HttpServletRequest request) {
        InetAddress peer = InetAddress.ofLiteral(request.getRemoteAddr());
        if (!trusted(peer)) return peer.getHostAddress();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 2048) return peer.getHostAddress();
        String[] chain = forwarded.split(",", -1);
        if (chain.length > 32) return peer.getHostAddress();
        try {
            InetAddress current = peer;
            for (int i=chain.length-1; i>=0 && trusted(current); i--) current = InetAddress.ofLiteral(chain[i].strip());
            return current.getHostAddress();
        } catch (IllegalArgumentException malformed) { return peer.getHostAddress(); }
    }
    private boolean trusted(InetAddress ip) {
        byte[] address = ip.getAddress();
        for (Network network : trusted) {
            if (address.length != network.address.length) continue;
            boolean same = true;
            for (int bit=0;bit<network.prefix;bit++) {
                if ((address[bit/8] & (1 << (7-bit%8))) != (network.address[bit/8] & (1 << (7-bit%8)))) {
                    same = false; break;
                }
            }
            if (same) return true;
        }
        return false;
    }
}
