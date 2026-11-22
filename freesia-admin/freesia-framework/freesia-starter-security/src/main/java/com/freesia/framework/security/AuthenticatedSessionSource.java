package com.freesia.framework.security;

import java.util.UUID;

/** The account-owning module may enforce its credential lifecycle before any handler executes. */
public interface AuthenticatedSessionSource {
    UUID authenticate(String accessToken, boolean allowExpiredAccess);
}
