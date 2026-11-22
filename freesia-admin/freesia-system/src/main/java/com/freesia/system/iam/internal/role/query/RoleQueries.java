package com.freesia.system.iam.internal.role.query;

import com.freesia.system.iam.internal.role.dto.RoleOption;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class RoleQueries {
    private final JdbcClient jdbc;

    public RoleQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<String> roles(UUID id) {
        return jdbc.sql("""
                SELECT r.role_code FROM freesia.sys_role r JOIN freesia.sys_account_role ar ON ar.role_id=r.id
                JOIN freesia.sys_account a ON a.id=ar.account_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted ORDER BY r.level DESC
                """).param("id", id).query(String.class).list();
    }

    public List<RoleOption> assignableRoles(UUID accountId) {
        return jdbc.sql("""
                SELECT r.id, r.role_code, r.role_name, r.level FROM freesia.sys_role r
                WHERE r.built_in AND r.enabled AND NOT r.deleted AND r.level <= (
                    SELECT coalesce(max(own.level),0) FROM freesia.sys_role own
                    JOIN freesia.sys_account_role ar ON ar.role_id=own.id
                    JOIN freesia.sys_account a ON a.id=ar.account_id
                    WHERE a.id=:id AND a.enabled AND NOT a.deleted AND own.enabled AND NOT own.deleted)
                ORDER BY r.level DESC
                """).param("id", accountId).query((rs, row) -> new RoleOption(rs.getObject(1, UUID.class),
                        rs.getString(2), rs.getString(3), rs.getInt(4))).list();
    }
}
