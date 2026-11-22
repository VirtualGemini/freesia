package com.freesia.system.iam.internal.auth;

import java.time.Instant;

/** Refresh is issued now; rotation is implemented by the separate refresh slice. */
public record SessionCredentials(String token, String refreshToken, String sessionId,
                                 Instant accessExpiresAt, Instant sessionExpiresAt) {
    @Override public String toString() { return "SessionCredentials[redacted]"; }
}
