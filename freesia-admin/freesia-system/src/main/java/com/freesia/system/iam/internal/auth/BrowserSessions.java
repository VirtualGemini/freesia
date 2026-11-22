package com.freesia.system.iam.internal.auth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Access never enters a browser cookie; Refresh never enters response JSON. */
@Component
@EnableConfigurationProperties(BrowserSessionProperties.class)
class BrowserSessions {
    static final String COOKIE = "freesia_refresh";
    private final BrowserSessionProperties properties;
    BrowserSessions(BrowserSessionProperties properties) { this.properties = properties; }

    SessionCredentials.Access install(SessionCredentials credentials, HttpServletResponse response) {
        long remaining = Math.max(0, Duration.between(Instant.now(), credentials.sessionExpiresAt()).getSeconds());
        cookie(response, credentials.refreshToken(), remaining);
        return credentials.access();
    }

    void clear(HttpServletResponse response) { cookie(response, "", 0); }

    String refresh(HttpServletRequest request) {
        String value = null;
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (!COOKIE.equals(cookie.getName())) continue;
                if (value != null) throw new ApiException(IamErrorCode.SESSION_INVALID);
                value = cookie.getValue();
            }
        }
        return value;
    }

    String expectedSession(HttpServletRequest request) {
        var values = java.util.Collections.list(request.getHeaders("X-Freesia-Session"));
        if (values.isEmpty()) return null;
        if (values.size() != 1 || !values.getFirst().matches("[A-Za-z0-9_-]{43}")) {
            throw new ApiException(IamErrorCode.SESSION_INVALID);
        }
        return values.getFirst();
    }

    private void cookie(HttpServletResponse response, String value, long maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Strict")
                .path("/api/auth").maxAge(maxAge).build().toString());
    }
}
