package com.freesia.system.iam.internal.role.query;

import com.freesia.system.iam.internal.role.dto.RoleOption;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;

@Component
public class RoleQueries {
    private final JdbcClient jdbc;
    private final RoleManagementQueries management;
    private final AuthorizationQueries authorization;

    public RoleQueries(JdbcClient jdbc, RoleManagementQueries management,AuthorizationQueries authorization) {
        this.jdbc = jdbc; this.management = management;this.authorization=authorization;
    }

    public List<String> roles(UUID id) {
        return jdbc.sql("""
                SELECT r.role_code FROM freesia.sys_role r JOIN freesia.sys_account_role ar ON ar.role_id=r.id
                JOIN freesia.sys_account a ON a.id=ar.account_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted ORDER BY r.role_code
                """).param("id", id).query(String.class).list();
    }

    public List<RoleOption> assignableRoles(UUID accountId) {
        return management.assignable(accountId).stream()
                .map(role -> new RoleOption(role.roleId(), role.roleCode(), role.roleName())).toList();
    }
    public List<RoleOption> assignableRoles(UUID actor,UUID target,UUID organization) {
        if(target!=null) {
            authorization.account(actor,"system:account:assign-role",target);
            organization=jdbc.sql("SELECT organization_id FROM freesia.sys_account WHERE id=:id").param("id",target)
                    .query((rs,n)->rs.getObject(1,UUID.class)).optional().orElse(null);
        }
        final UUID recipient=organization;var rights=authorization.rights(actor);
        var range=rights.range("system:account:assign-role");
        if(target==null&&recipient!=null&&!range.all()&&!range.organizationIds().contains(recipient))return List.of();
        return management.assignable(actor).stream().filter(role->recipient!=null
                        ||!java.util.Set.of("ORG","SUB_ORG","ORG_AND_SUB").contains(role.dataScope()))
                .filter(role->management.canAssign(rights,role,target,recipient))
                .map(role->new RoleOption(role.roleId(),role.roleCode(),role.roleName())).toList();
    }
}
