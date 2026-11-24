package com.freesia.system.iam.internal.authorization.query;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.authorization.dto.DataRange;
import com.freesia.system.iam.internal.authorization.dto.PermissionProfile;
import com.freesia.system.iam.internal.authorization.dto.EffectiveRights;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AuthorizationQueries {
    private final JdbcClient jdbc;
    public AuthorizationQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public EffectiveRights rights(UUID actor) {
        UUID organization = jdbc.sql("SELECT organization_id FROM freesia.sys_account WHERE id=:id AND enabled AND NOT deleted")
                .param("id", actor).query((rs, row) -> rs.getObject(1, UUID.class)).optional().orElse(null);
        boolean owner = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM freesia.sys_bootstrap b JOIN freesia.sys_account a ON a.id=b.account_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND b.completed_at IS NOT NULL)
                """).param("id", actor).query(Boolean.class).single();
        var menuIds = new HashSet<UUID>(); var grants = new HashSet<UUID>();
        var use = new LinkedHashMap<String, DataRange>(); var grant = new LinkedHashMap<String, DataRange>();
        var rows = jdbc.sql("""
                SELECT r.id,r.data_scope,p.menu_id,m.auth_mark,p.grantable FROM freesia.sys_account a
                JOIN freesia.sys_account_role ar ON ar.account_id=a.id JOIN freesia.sys_role r ON r.id=ar.role_id
                JOIN (SELECT role_id,menu_id,false grantable FROM freesia.sys_role_menu_permission
                      UNION ALL SELECT role_id,menu_id,true FROM freesia.sys_role_grant_permission) p ON p.role_id=r.id
                JOIN freesia.sys_menu m ON m.id=p.menu_id
                WHERE a.id=:id AND a.enabled AND NOT a.deleted AND r.enabled AND NOT r.deleted AND m.enabled AND NOT m.deleted
                """).param("id", actor).query((rs, row) -> new Grant(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getBoolean(5))).list();
        Map<UUID, DataRange> ranges = new HashMap<>();
        Map<UUID,DataRange> grantRanges=new HashMap<>();
        for (var row : rows) {
            (row.grantable ? grants : menuIds).add(row.menu);
            DataRange range = ranges.computeIfAbsent(row.role, id -> scope(row.scope, custom(id), organization));
            if(row.grantable)grantRanges.merge(row.menu,range,DataRange::union);
            if (row.permission != null) {
                (row.grantable ? grant : use).merge(row.permission, range, DataRange::union);
            }
        }
        return new EffectiveRights(actor, organization, owner, Set.copyOf(menuIds), Set.copyOf(grants), Map.copyOf(use), Map.copyOf(grant),
                Map.copyOf(grantRanges));
    }

    public DataRange scope(String scope, Collection<UUID> custom, UUID organization) {
        if(scope==null||custom==null)throw new ApiException(CommonErrorCode.BAD_REQUEST);
        return switch (scope) {
            case "ALL" -> new DataRange(true, false, Set.of());
            case "SELF" -> new DataRange(false, true, Set.of());
            case "CUSTOM_ORGS" -> new DataRange(false, false, Set.copyOf(custom));
            case "ORG" -> new DataRange(false, false, organization == null ? Set.of() : Set.of(organization));
            case "SUB_ORG", "ORG_AND_SUB" -> {
                Set<UUID> ids = new HashSet<>();
                if (organization != null) {
                    ids.addAll(jdbc.sql("""
                            WITH RECURSIVE tree AS (
                                SELECT id FROM freesia.sys_organization WHERE id=:id
                                UNION ALL SELECT o.id FROM freesia.sys_organization o JOIN tree t ON o.parent_id=t.id)
                            SELECT id FROM tree
                            """).param("id", organization).query(UUID.class).list());
                    if (scope.equals("SUB_ORG")) ids.remove(organization);
                }
                yield new DataRange(false, false, ids);
            }
            default -> throw new ApiException(CommonErrorCode.BAD_REQUEST);
        };
    }
    public List<UUID> custom(UUID role) {
        return jdbc.sql("SELECT organization_id FROM freesia.sys_role_scope_organization WHERE role_id=:id")
                .param("id", role).query(UUID.class).list();
    }
    public void organization(UUID actor, String permission, UUID target) {
        DataRange range = rights(actor).range(permission);
        if (!range.all() && !range.organizationIds().contains(target)) throw new ApiException(CommonErrorCode.FORBIDDEN);
    }
    public void account(UUID actor, String permission, UUID target) {
        var rights = rights(actor);
        var row = jdbc.sql("""
                SELECT organization_id, EXISTS(SELECT 1 FROM freesia.sys_bootstrap WHERE account_id=a.id) owner
                FROM freesia.sys_account a WHERE id=:id AND NOT deleted
                """).param("id", target).query((rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getBoolean(2)))
                .optional().orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
        if (row.owner && !rights.owner() && !permission.equals("system:account:query")
                || !rights.range(permission).allows(actor, target, row.organization)) {
            throw new ApiException(CommonErrorCode.FORBIDDEN);
        }
    }
    public PermissionProfile profile(UUID actor) {
        var rights=rights(actor);
        return new PermissionProfile(rights.use(),rights.grant(),rights.organizationId());
    }
    private record Grant(UUID role, String scope, UUID menu, String permission, boolean grantable) {}
    private record Target(UUID organization, boolean owner) {}
}
