package com.freesia.system.iam.internal.account.query;

import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.ApiException;
import com.freesia.system.iam.internal.account.dto.AccountCredential;
import com.freesia.system.iam.internal.account.dto.AccountInfo;
import com.freesia.system.iam.internal.authorization.query.PermissionQueries;
import com.freesia.system.iam.internal.role.query.RoleQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class AccountQueries {
    private final JdbcClient jdbc;
    private final RoleQueries roles;
    private final PermissionQueries permissions;

    public AccountQueries(JdbcClient jdbc, RoleQueries roles, PermissionQueries permissions) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.permissions = permissions;
    }

    public Optional<AccountCredential> credential(String username) {
        return jdbc.sql("SELECT id, username, password_hash, enabled, deleted FROM freesia.sys_account WHERE username=:username AND NOT deleted")
                .param("username", username).query((rs, row) -> new AccountCredential(rs.getObject("id", UUID.class),
                        rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getBoolean("deleted"))).optional();
    }

    public boolean active(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_account WHERE id=:id AND enabled AND NOT deleted)")
                .param("id", id).query(Boolean.class).single();
    }

    public AccountInfo current(UUID id) {
        return jdbc.sql("""
                SELECT a.id, a.username, p.email, p.phone, p.language, p.time_zone
                FROM freesia.sys_account a LEFT JOIN freesia.sys_profile p ON p.account_id=a.id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted
                """).param("id", id).query((rs, row) -> new AccountInfo(id, rs.getString("username"), rs.getString("email"),
                        rs.getString("phone"), null, rs.getString("language"), rs.getString("time_zone"),
                        roles.roles(id), permissions.permissionsOf(id)))
                .optional().orElseThrow(() -> new ApiException(IamErrorCode.SESSION_INVALID));
    }
}
