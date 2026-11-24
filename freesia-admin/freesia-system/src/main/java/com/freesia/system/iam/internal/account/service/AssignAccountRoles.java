package com.freesia.system.iam.internal.account.service;

import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.account.dto.RoleAssignmentInput;
import com.freesia.system.iam.internal.account.mapper.AccountRoleWrites;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.role.dto.RoleDetail;
import com.freesia.system.iam.internal.role.query.RoleManagementQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class AssignAccountRoles {
    private final JdbcClient jdbc;
    private final AccountRoleWrites writes;
    private final AuthorizationQueries authorization;
    private final RoleManagementQueries roles;
    private final ManagementActions actions;
    public AssignAccountRoles(JdbcClient jdbc, AccountRoleWrites writes, AuthorizationQueries authorization,
                              RoleManagementQueries roles, ManagementActions actions) {
        this.jdbc=jdbc;this.writes=writes;this.authorization=authorization;this.roles=roles;this.actions=actions;
    }
    public boolean assign(UUID actor,UUID target,RoleAssignmentInput input,String trace) {
        return actions.run(actor,"system:account:assign-role","account",target.toString(),trace,()->{
            authorization.account(actor,"system:account:assign-role",target);
            apply(actor,target,input);
            return true;
        });
    }
    /** Called only inside the enclosing management transaction, including initial account creation. */
    public void apply(UUID actor,UUID target,RoleAssignmentInput input) {
        if(input.addRoleIds()==null||input.removeRoleIds()==null
                ||input.addRoleIds().stream().anyMatch(Objects::isNull)||input.removeRoleIds().stream().anyMatch(Objects::isNull)) {
            throw new ApiException(CommonErrorCode.BAD_REQUEST);
        }
        Set<UUID> add=new HashSet<>(input.addRoleIds()),remove=new HashSet<>(input.removeRoleIds());
        if(add.stream().anyMatch(remove::contains))throw new ApiException(CommonErrorCode.BAD_REQUEST);
        UUID organization=jdbc.sql("SELECT organization_id FROM freesia.sys_account WHERE id=:id").param("id",target)
                .query((rs,n)->rs.getObject(1,UUID.class)).optional().orElse(null);
        for(UUID id:java.util.stream.Stream.concat(add.stream(),remove.stream()).toList()) {
            RoleDetail role=roles.detail(actor,id);
            if(role.type()==0)throw new ApiException(CommonErrorCode.FORBIDDEN);
            boolean relative=Set.of("ORG","SUB_ORG","ORG_AND_SUB").contains(role.dataScope());
            if(relative&&organization==null)throw new ApiException(IamErrorCode.ORGANIZATION_REQUIRED);
            if(!roles.canAssign(authorization.rights(actor),role,target,organization)) {
                throw new ApiException(IamErrorCode.GRANT_EXCEEDED);
            }
            if(add.contains(id)&&!role.enabled())throw new ApiException(CommonErrorCode.CONFLICT);
        }
        for(UUID role:remove)writes.unbind(target,role);
        for(UUID role:add)writes.bind(target,role);
    }
}
