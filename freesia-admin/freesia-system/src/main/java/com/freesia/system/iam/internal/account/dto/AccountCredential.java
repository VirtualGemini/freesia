package com.freesia.system.iam.internal.account.dto;

import java.util.UUID;

/** Internal password-verification input, never an HTTP response. */
public record AccountCredential(UUID id, String username, String passwordHash, boolean enabled, boolean deleted) {
    @Override public String toString() { return "Credential[redacted]"; }
}
