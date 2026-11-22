package com.freesia.system.iam.internal.origin.service;

import com.freesia.common.audit.ClientOrigin;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import com.freesia.framework.web.servlet.ClientIpResolver;

@Component
public class ClientOrigins {
    private final ClientIpResolver clients;
    private final OfflineLocations locations;
    public ClientOrigins(ClientIpResolver clients, OfflineLocations locations) {
        this.clients = clients; this.locations = locations;
    }
    public ClientOrigin from(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        String ip = clients.resolve(request);
        return new ClientOrigin(ip, MDC.get("traceId"),
                agent == null ? null : agent.substring(0, Math.min(512, agent.length())), locations.resolve(ip));
    }
}
