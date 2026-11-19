package com.freesia.framework.security;

import java.util.Set;

/**
 * Supplies the permission identifiers an account currently holds. Implemented by the module that
 * owns accounts and roles. Without an implementation every {@link RequirePermission} check is denied.
 *
 * <p>The result must reflect disabled accounts and disabled or deleted roles: an account that may no
 * longer act holds no permissions.
 */
public interface AuthorizationSource {
    Set<String> permissionsOf(long accountId);
}
