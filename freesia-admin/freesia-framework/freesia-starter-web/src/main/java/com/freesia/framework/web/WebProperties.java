package com.freesia.framework.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("freesia.web")
public record WebProperties(@DefaultValue SecurityHeaders securityHeaders) {
    /** An empty value omits that header. HSTS is only sent on secure requests. */
    public record SecurityHeaders(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("nosniff") String contentTypeOptions,
            @DefaultValue("DENY") String frameOptions,
            @DefaultValue("no-referrer") String referrerPolicy,
            @DefaultValue("") String contentSecurityPolicy,
            @DefaultValue("") String strictTransportSecurity) {}
}
