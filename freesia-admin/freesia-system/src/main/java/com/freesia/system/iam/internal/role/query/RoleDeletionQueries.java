package com.freesia.system.iam.internal.role.query;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.account.dto.VisibleRoleMembers.Member;
import com.freesia.system.iam.internal.account.query.AccountManagementQueries;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.role.dto.RoleDeletionImpact.RoleImpact;
import com.freesia.system.iam.internal.role.dto.RoleDeletionSnapshot;
import com.freesia.system.iam.internal.role.dto.RoleDeletionSnapshot.Binding;
import com.freesia.system.iam.internal.role.dto.RoleDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Set;

@Component
public class RoleDeletionQueries {
    private final JdbcClient jdbc;
    private final RoleManagementQueries roles;
    private final AccountManagementQueries accounts;
    private final AuthorizationQueries authorization;

    public RoleDeletionQueries(JdbcClient jdbc, RoleManagementQueries roles, AccountManagementQueries accounts,
                               AuthorizationQueries authorization) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.accounts = accounts;
        this.authorization = authorization;
    }

    /** Call only while holding the IAM management lock, for both preview and execution. */
    public RoleDeletionSnapshot snapshot(UUID actor, List<UUID> ids) {
        var definitions = new ArrayList<RoleDetail>();
        var impacts = new ArrayList<RoleImpact>();
        var visible = new LinkedHashMap<UUID, Member>();
        var rights = authorization.rights(actor);
        for (UUID id : ids) {
            RoleDetail original = roles.raw(id);
            if (original.type() == 0) throw new ApiException(IamErrorCode.EXCLUSIVE_OWNER);
            if (!roles.operable(rights, original)) throw new ApiException(IamErrorCode.GRANT_EXCEEDED);
            RoleDetail role = original;
            // A role has no owning organization. Compare its template in the actor's context,
            // never every holder's context; shared holders remain outside the deletion guard.
            var desired = authorization.scope(role.dataScope(), role.customOrganizationIds(), rights.organizationId());
            boolean missingContext = rights.organizationId() == null
                    && Set.of("ORG", "SUB_ORG", "ORG_AND_SUB").contains(role.dataScope());
            var required = new HashSet<>(role.menuIds());
            required.addAll(role.grantMenuIds());
            if (required.stream().anyMatch(menu -> {
                var grant = rights.grantRanges().get(menu);
                return missingContext ? !grant.all() : !grant.contains(desired, rights.organizationId());
            })) throw new ApiException(IamErrorCode.GRANT_EXCEEDED);
            var deletion = rights.range("system:role:delete");
            if (missingContext ? !deletion.all() : !deletion.contains(desired, rights.organizationId())) {
                throw new ApiException(CommonErrorCode.FORBIDDEN);
            }
            definitions.add(new RoleDetail(role.roleId(), role.roleCode(), role.roleName(), role.description(), role.enabled(),
                    role.type(), role.dataScope(), role.customOrganizationIds().stream().sorted().toList(),
                    role.menuIds(), role.grantMenuIds(), role.createTime(), role.updateTime()));
            var members = accounts.members(actor, id);
            impacts.add(new RoleImpact(id, role.roleCode(), role.roleName(), members.records(), members.visibleCount()));
            members.records().forEach(member -> visible.put(member.accountId(), member));
        }
        var bindings = jdbc.sql("""
                SELECT ar.role_id,a.id,a.username,a.organization_id,a.enabled,a.deleted,a.updated_at
                FROM freesia.sys_account_role ar JOIN freesia.sys_account a ON a.id=ar.account_id
                WHERE ar.role_id IN (:ids) ORDER BY ar.role_id,a.id
                """).param("ids", ids).query((rs, row) -> new Binding(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant())).list();
        var records = visible.values().stream().sorted(Comparator.comparing(Member::username).thenComparing(Member::accountId)).toList();
        boolean complete = rights.range("system:account:query").all();
        return new RoleDeletionSnapshot(definitions, bindings, impacts, records, complete);
    }
}
