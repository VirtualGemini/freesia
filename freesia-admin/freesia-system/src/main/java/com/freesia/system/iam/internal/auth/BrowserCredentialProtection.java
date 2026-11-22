package com.freesia.system.iam.internal.auth;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/** Custom header + no CORS + Strict cookie; do not enable credentialed cross-origin access here. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class BrowserCredentialProtection extends OncePerRequestFilter {
    private static final Set<String> ENDPOINTS = Set.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout");
    private final ObjectProvider<HandlerExceptionResolver> errors;
    private final ObjectProvider<LocaleResolver> locales;

    BrowserCredentialProtection(@Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> errors,
                                @Qualifier("localeResolver") ObjectProvider<LocaleResolver> locales) {
        this.errors = errors;
        this.locales = locales;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Use MVC's decoded segments without matrix parameters, not the raw request URI.
        String path = org.springframework.web.util.ServletRequestPathUtils.parse(request).pathWithinApplication()
                .elements().stream().map(element -> element instanceof org.springframework.http.server.PathContainer.PathSegment segment
                        ? segment.valueToMatch() : element.value()).collect(java.util.stream.Collectors.joining());
        if (ENDPOINTS.contains(path)) {
            response.setHeader("Cache-Control", "no-store");
            var csrf = Collections.list(request.getHeaders("X-Freesia-CSRF"));
            var site = Collections.list(request.getHeaders("Sec-Fetch-Site"));
            var origin = Collections.list(request.getHeaders("Origin"));
            boolean hostile = site.size() > 1 || site.stream().anyMatch(value -> !Set.of("same-origin", "none").contains(value))
                    || origin.size() > 1 || origin.stream().anyMatch(value -> !validOrigin(value));
            if ("POST".equals(request.getMethod()) && (csrf.size() != 1 || !"1".equals(csrf.getFirst()) || hostile)) {
                var previous = LocaleContextHolder.getLocaleContext();
                try {
                    // DispatcherServlet has not established the negotiated request locale yet.
                    LocaleContextHolder.setLocale(locales.getObject().resolveLocale(request));
                    errors.getObject().resolveException(request, response, null, new ApiException(CommonErrorCode.FORBIDDEN));
                } finally {
                    LocaleContextHolder.setLocaleContext(previous);
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean validOrigin(String value) {
        try {
            var uri = java.net.URI.create(value);
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getPort() <= 65535;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return false;
        }
    }
}
