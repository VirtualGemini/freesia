package com.freesia.system.iam.internal.authorization.query;

import com.freesia.framework.security.AuthorizationSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class PermissionQueries implements AuthorizationSource {
    private final JdbcClient jdbc;

    public PermissionQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Set<String> permissionsOf(UUID id) {
        return new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT m.auth_mark FROM freesia.sys_account a
                JOIN freesia.sys_account_role ar ON ar.account_id=a.id
                JOIN freesia.sys_role r ON r.id=ar.role_id
                JOIN freesia.sys_role_menu_permission rm ON rm.role_id=r.id
                JOIN freesia.sys_menu m ON m.id=rm.menu_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted
                AND m.enabled AND NOT m.deleted AND m.auth_mark IS NOT NULL ORDER BY m.auth_mark
                """).param("id", id).query(String.class).list());
    }
}
