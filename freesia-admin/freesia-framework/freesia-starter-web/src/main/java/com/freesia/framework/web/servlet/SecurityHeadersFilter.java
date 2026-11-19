package com.freesia.framework.web.servlet;

import com.freesia.framework.web.WebProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class SecurityHeadersFilter extends OncePerRequestFilter {
    private final WebProperties.SecurityHeaders headers;

    public SecurityHeadersFilter(WebProperties.SecurityHeaders headers) {
        this.headers = headers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        set(response, "X-Content-Type-Options", headers.contentTypeOptions());
        set(response, "X-Frame-Options", headers.frameOptions());
        set(response, "Referrer-Policy", headers.referrerPolicy());
        set(response, "Content-Security-Policy", headers.contentSecurityPolicy());
        if (request.isSecure()) {
            set(response, "Strict-Transport-Security", headers.strictTransportSecurity());
        }
        chain.doFilter(request, response);
    }

    private static void set(HttpServletResponse response, String name, String value) {
        if (StringUtils.hasText(value)) {
            response.setHeader(name, value);
        }
    }
}
