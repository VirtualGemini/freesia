package com.freesia.system.iam.internal.role.query;

import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.authorization.dto.EffectiveRights;
import com.freesia.system.iam.internal.role.dto.RoleDetail;
import com.freesia.system.iam.internal.role.dto.PermissionOption;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RoleManagementQueries {
    private final JdbcClient jdbc;
    private final AuthorizationQueries authorization;
    public RoleManagementQueries(JdbcClient jdbc, AuthorizationQueries authorization) { this.jdbc = jdbc; this.authorization = authorization; }

    public RoleDetail raw(UUID id) {
        return jdbc.sql("SELECT * FROM freesia.sys_role WHERE id=:id AND NOT deleted").param("id", id)
                .query((rs, row) -> new RoleDetail(id, rs.getString("role_code"), rs.getString("role_name"),
                        rs.getString("description"), rs.getBoolean("enabled"), rs.getBoolean("built_in") ? 0 : 1,
                        rs.getString("data_scope"), authorization.custom(id), uses(id), grants(id),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .optional().orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
    }
    public List<UUID> uses(UUID id) {
        return jdbc.sql("SELECT menu_id FROM freesia.sys_role_menu_permission WHERE role_id=:id ORDER BY menu_id")
                .param("id", id).query(UUID.class).list();
    }
    public List<UUID> grants(UUID id) {
        return jdbc.sql("SELECT menu_id FROM freesia.sys_role_grant_permission WHERE role_id=:id ORDER BY menu_id")
                .param("id", id).query(UUID.class).list();
    }
    public boolean operable(EffectiveRights actor, RoleDetail role) {
        return role.type() != 0 && grantAllowed(actor,role.menuIds(),role.grantMenuIds(),role.dataScope(),
                role.customOrganizationIds(),null,false);
    }
    public boolean grantAllowed(EffectiveRights actor,List<UUID> menus,List<UUID> grants,String scope,
                                List<UUID> organizations,UUID recipientOrganization,boolean assigning) {
        var required=new HashSet<>(menus);required.addAll(grants);
        if(!actor.grantMenuIds().containsAll(required))return false;
        // A template has no holder yet. Actual relative range is checked on assignment.
        if(!assigning&&Set.of("ORG","SUB_ORG","ORG_AND_SUB").contains(scope))return true;
        var desired=authorization.scope(scope,organizations,assigning?recipientOrganization:actor.organizationId());
        return required.stream().allMatch(menu->actor.grantRanges().get(menu).contains(desired,
                assigning?recipientOrganization:actor.organizationId()));
    }
    public boolean canAssign(EffectiveRights actor,RoleDetail role,UUID recipient,UUID organization) {
        if(!grantAllowed(actor,role.menuIds(),role.grantMenuIds(),role.dataScope(),
                role.customOrganizationIds(),organization,true))return false;
        if(!"SELF".equals(role.dataScope())||actor.accountId().equals(recipient))return true;
        var required=new HashSet<>(role.menuIds());required.addAll(role.grantMenuIds());
        return required.stream().allMatch(menu->{
            var allowed=actor.grantRanges().get(menu);
            return allowed.all()||organization!=null&&allowed.organizationIds().contains(organization);
        });
    }
    public RoleDetail detail(UUID actor, UUID id) {
        RoleDetail role = raw(id);
        if (!visible(authorization.rights(actor), role)) throw new ApiException(CommonErrorCode.FORBIDDEN);
        return role;
    }
    private boolean visible(EffectiveRights actor, RoleDetail role) {
        return actor.owner() && role.type() == 0 || operable(actor, role);
    }
    public RoleDetail forWrite(UUID actor,UUID id,String permission) {
        RoleDetail role=raw(id);var rights=authorization.rights(actor);
        boolean relative=Set.of("ORG","SUB_ORG","ORG_AND_SUB").contains(role.dataScope());
        if(!operable(rights,role)||!relative&&!rights.range(permission).contains(
                authorization.scope(role.dataScope(),role.customOrganizationIds(),rights.organizationId()),rights.organizationId())) {
            throw new ApiException(CommonErrorCode.FORBIDDEN);
        }
        return role;
    }
    public List<RoleDetail> list(UUID actor, String name, String code) {
        var rights = authorization.rights(actor);
        return jdbc.sql("""
                SELECT id FROM freesia.sys_role WHERE NOT deleted
                AND (:name='' OR role_name ILIKE :namePattern) AND (:code='' OR role_code ILIKE :codePattern) ORDER BY created_at DESC,id DESC
                """).param("name", name).param("namePattern", "%"+name+"%").param("code", code).param("codePattern", "%"+code+"%")
                .query(UUID.class).list().stream().map(this::raw).filter(role -> visible(rights, role)).toList();
    }
    public List<RoleDetail> assignable(UUID actor) {
        var rights = authorization.rights(actor);
        return list(actor,"","").stream().filter(RoleDetail::enabled).filter(role -> operable(rights, role)).toList();
    }
    public List<PermissionOption> options(UUID actor) {
        var allowed = authorization.rights(actor).grantMenuIds();
        return jdbc.sql("""
                SELECT action.id,coalesce(action.parent_id,(
                    SELECT menu.id FROM freesia.sys_menu menu
                    WHERE action.menu_type='button' AND menu.menu_type='menu'
                    AND lower(menu.name)=split_part(action.auth_mark,':',2)
                    AND menu.enabled AND NOT menu.deleted ORDER BY menu.sort,menu.id LIMIT 1
                )) parent_id,action.name,action.title,action.auth_mark
                FROM freesia.sys_menu action WHERE action.enabled AND NOT action.deleted ORDER BY action.sort,action.id
                """)
                .query((rs,row)->new PermissionOption(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5)))
                .list().stream().filter(node->allowed.contains(node.id())).toList();
    }
}
