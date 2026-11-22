package com.freesia.system.iam.internal.auth;

import java.time.Instant;

/** Internal credentials; only the access view may be serialized in an HTTP response. */
public record SessionCredentials(String token, String refreshToken, String sessionId,
                                 Instant accessExpiresAt, Instant sessionExpiresAt) {
    public Access access() { return new Access(token, sessionId, accessExpiresAt, sessionExpiresAt); }
    public record Access(String token, String sessionId, Instant accessExpiresAt, Instant sessionExpiresAt) {}
    @Override public String toString() { return "SessionCredentials[redacted]"; }
}
