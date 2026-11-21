package com.freesia.admin.iam.internal.auth;

import com.freesia.admin.iam.api.IamErrorCode;
import com.freesia.common.error.ApiException;
import com.freesia.framework.security.AuthorizationSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class AccountQueries implements AuthorizationSource {
    private final JdbcClient jdbc;
    public AccountQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Credential(UUID id, String username, String passwordHash, boolean enabled, boolean deleted) {
        @Override public String toString() { return "Credential[redacted]"; }
    }
    public Optional<Credential> credential(String username) {
        return jdbc.sql("SELECT id, username, password_hash, enabled, deleted FROM freesia.sys_account WHERE username=:username AND NOT deleted")
                .param("username", username).query((rs, row) -> new Credential(rs.getObject("id", UUID.class),
                        rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getBoolean("deleted"))).optional();
    }

    public boolean active(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_account WHERE id=:id AND enabled AND NOT deleted)")
                .param("id", id).query(Boolean.class).single();
    }

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

    public List<String> roles(UUID id) {
        return jdbc.sql("""
                SELECT r.role_code FROM freesia.sys_role r JOIN freesia.sys_account_role ar ON ar.role_id=r.id
                JOIN freesia.sys_account a ON a.id=ar.account_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted ORDER BY r.level DESC
                """).param("id", id).query(String.class).list();
    }

    public record AccountInfo(UUID accountId, String userName, String email, String phone, String avatar,
                              String language, String timeZone, List<String> roles, Set<String> buttons) {}
    public AccountInfo current(UUID id) {
        return jdbc.sql("""
                SELECT a.id, a.username, p.email, p.phone, p.language, p.time_zone
                FROM freesia.sys_account a LEFT JOIN freesia.sys_profile p ON p.account_id=a.id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted
                """).param("id", id).query((rs, row) -> new AccountInfo(id, rs.getString("username"), rs.getString("email"),
                        rs.getString("phone"), null, rs.getString("language"), rs.getString("time_zone"), roles(id), permissionsOf(id)))
                .optional().orElseThrow(() -> new ApiException(IamErrorCode.SESSION_INVALID));
    }

    public record Policy(int accessSeconds, int refreshSeconds) {}
    public Policy policy() {
        return jdbc.sql("SELECT access_seconds, refresh_seconds FROM freesia.sys_access_control WHERE scope_key='global'")
                .query((rs, row) -> new Policy(rs.getInt(1), rs.getInt(2))).single();
    }

    public record RoleOption(UUID roleId, String roleCode, String roleName, int roleLevel) {}
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
