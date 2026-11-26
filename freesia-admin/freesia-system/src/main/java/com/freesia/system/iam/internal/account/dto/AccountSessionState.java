package com.freesia.system.iam.internal.account.dto;

/** Locked authentication state; never returned over HTTP or included in logs. */
public record AccountSessionState(boolean enabled, boolean deleted, long version, String passwordHash) {
    @Override public String toString() { return "AccountSessionState[redacted]"; }
}
