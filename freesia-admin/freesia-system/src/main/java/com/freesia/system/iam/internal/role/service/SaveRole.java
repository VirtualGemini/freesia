package com.freesia.system.iam.internal.role.service;

import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.role.dto.RoleInput;
import com.freesia.system.iam.internal.role.entity.RoleRow;
import com.freesia.system.iam.internal.role.mapper.RoleWrites;
import com.freesia.system.iam.internal.role.query.RoleManagementQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SaveRole {
    private final JdbcClient jdbc;
    private final RoleWrites writes;
    private final RoleManagementQueries roles;
    private final AuthorizationQueries authorization;
    private final ManagementActions actions;
    public SaveRole(JdbcClient jdbc, RoleWrites writes, RoleManagementQueries roles, AuthorizationQueries authorization, ManagementActions actions) {
        this.jdbc = jdbc; this.writes = writes; this.roles = roles; this.authorization = authorization; this.actions = actions;
    }
    public UUID save(UUID actor, UUID id, RoleInput input, String trace) {
        return save(actor,id,input,trace,id==null?"system:role:create":"system:role:update");
    }
    public UUID permissions(UUID actor,UUID id,RoleInput input,String trace) {
        return save(actor,id,input,trace,"system:role:permission");
    }
    private UUID save(UUID actor,UUID id,RoleInput input,String trace,String permission) {
        return actions.run(actor, permission, "role",
                id == null ? null : id.toString(), trace, () -> {
            if (id != null) {
                var original = roles.forWrite(actor, id,permission);
                if(input.menuIds()==null||input.grantMenuIds()==null||input.customOrganizationIds()==null||input.dataScope()==null) {
                    throw new ApiException(CommonErrorCode.BAD_REQUEST);
                }
                boolean authorizationChanged=!new HashSet<>(original.menuIds()).equals(new HashSet<>(input.menuIds()))
                        ||!new HashSet<>(original.grantMenuIds()).equals(new HashSet<>(input.grantMenuIds()))
                        ||!original.dataScope().equals(input.dataScope())
                        ||!new HashSet<>(original.customOrganizationIds()).equals(new HashSet<>(input.customOrganizationIds()));
                if(permission.equals("system:role:update")&&authorizationChanged) {
                    if(!authorization.rights(actor).use().containsKey("system:role:permission")) {
                        throw new ApiException(CommonErrorCode.FORBIDDEN);
                    }
                    roles.forWrite(actor,id,"system:role:permission");
                }
                if(permission.equals("system:role:permission")&&(!original.roleCode().equals(input.roleCode())
                        ||!original.roleName().equals(input.roleName())||original.enabled()!=input.enabled()
                        ||!original.description().equals(input.description())))throw new ApiException(CommonErrorCode.BAD_REQUEST);
                if (original.enabled() != input.enabled() && !authorization.rights(actor).use().containsKey("system:role:status")) {
                    throw new ApiException(CommonErrorCode.FORBIDDEN);
                }
                if(original.enabled()!=input.enabled())roles.forWrite(actor,id,"system:role:status");
            }
            if (input.roleCode() == null || !input.roleCode().matches("[A-Za-z0-9_.:-]{1,80}")
                    || input.roleName() == null || input.roleName().isBlank() || input.roleName().length()>100
                    || input.roleCode().equals("R_SUPER") || input.description() != null && input.description().length()>500
                    || input.menuIds() == null || input.grantMenuIds() == null || input.customOrganizationIds() == null
                    || input.dataScope()==null || input.menuIds().contains(null) || input.grantMenuIds().contains(null)
                    || input.customOrganizationIds().contains(null)) {
                throw new ApiException(CommonErrorCode.BAD_REQUEST);
            }
            var rights = authorization.rights(actor);
            if (!roles.grantAllowed(rights,input.menuIds(),input.grantMenuIds(),input.dataScope(),
                    input.customOrganizationIds(),null,false)) {
                throw new ApiException(IamErrorCode.GRANT_EXCEEDED);
            }
            authorization.scope(input.dataScope(), input.customOrganizationIds(), rights.organizationId());
            if (Set.of("ORG","SUB_ORG","ORG_AND_SUB").contains(input.dataScope()) && id != null
                    && jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM freesia.sys_account_role ar JOIN freesia.sys_account a ON a.id=ar.account_id
                        WHERE ar.role_id=:id AND a.organization_id IS NULL AND NOT a.deleted)
                        """).param("id", id).query(Boolean.class).single()) {
                throw new ApiException(IamErrorCode.ORGANIZATION_REQUIRED);
            }
            UUID roleId = id;
            if (roleId == null) {
                var row = new RoleRow(); row.setRoleCode(input.roleCode()); row.setRoleName(input.roleName().trim());
                row.setDescription(input.description() == null ? "" : input.description()); row.setEnabled(input.enabled());
                row.setDataScope(input.dataScope()); writes.insertSelective(row); roleId = row.getId();
            } else {
                writes.changeDetails(roleId, input); writes.clearUse(roleId); writes.clearGrant(roleId); writes.clearOrganizations(roleId);
            }
            for (UUID menu : new HashSet<>(input.menuIds())) writes.addUse(roleId, menu);
            for (UUID menu : new HashSet<>(input.grantMenuIds())) writes.addGrant(roleId, menu);
            if ("CUSTOM_ORGS".equals(input.dataScope())) for (UUID org : new HashSet<>(input.customOrganizationIds())) writes.addOrganization(roleId, org);
            return roleId;
        });
    }
    public boolean status(UUID actor,UUID id,boolean enabled,String trace) {
        return actions.run(actor,"system:role:status","role",id.toString(),trace,()->{
            roles.forWrite(actor,id,"system:role:status");
            return writes.changeStatus(id,enabled)==1;
        });
    }
}
