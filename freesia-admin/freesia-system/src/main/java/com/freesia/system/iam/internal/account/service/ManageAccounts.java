package com.freesia.system.iam.internal.account.service;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.account.dto.*;
import com.freesia.system.iam.internal.account.entity.AccountRow;
import com.freesia.system.iam.internal.account.mapper.AccountWrites;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.password.service.Passwords;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ManageAccounts {
    private final JdbcClient jdbc;
    private final AccountWrites writes;
    private final Passwords passwords;
    private final AuthorizationQueries authorization;
    private final ManagementActions actions;
    private final AssignAccountRoles assignments;
    public ManageAccounts(JdbcClient jdbc,AccountWrites writes,Passwords passwords,AuthorizationQueries authorization,ManagementActions actions,
                          AssignAccountRoles assignments) {
        this.jdbc=jdbc;this.writes=writes;this.passwords=passwords;this.authorization=authorization;this.actions=actions;this.assignments=assignments;
    }
    public UUID create(UUID actor,CreateAccountInput input,String trace) {
        return actions.run(actor,"system:account:create","account",null,trace,()->{
            if(input.username()==null||!input.username().matches("[\\p{L}\\p{N}_.@-]{3,50}")
                    || input.password()==null||input.password().isBlank()||input.remark()!=null&&input.remark().length()>500) {
                throw new ApiException(CommonErrorCode.BAD_REQUEST);
            }
            if(input.roleIds()!=null&&input.roleIds().contains(null)||input.roleCodes()!=null&&input.roleCodes().contains(null)) {
                throw new ApiException(CommonErrorCode.BAD_REQUEST);
            }
            var rights=authorization.rights(actor);var range=rights.range("system:account:create");
            if(input.organizationId()!=null) {
                boolean root=jdbc.sql("SELECT is_root FROM freesia.sys_organization WHERE id=:id").param("id",input.organizationId())
                        .query(Boolean.class).optional().orElseThrow(()->new ApiException(CommonErrorCode.BAD_REQUEST));
                if(root)throw new ApiException(CommonErrorCode.FORBIDDEN);
            }
            if(!range.all()&&(input.organizationId()==null||!range.organizationIds().contains(input.organizationId()))) {
                throw new ApiException(CommonErrorCode.FORBIDDEN);
            }
            var row=new AccountRow();row.setUsername(input.username());row.setRemark(input.remark());row.setOrganizationId(input.organizationId());
            row.setPasswordHash(passwords.hash(input.password()));writes.insertSelective(row);writes.createProfile(row.getId());
            var roleIds=new HashSet<UUID>(input.roleIds()==null?List.of():input.roleIds());
            if(input.roleCodes()!=null) for(String code:input.roleCodes()) roleIds.add(jdbc.sql("""
                    SELECT id FROM freesia.sys_role WHERE role_code=:code AND NOT deleted
                    """).param("code",code).query(UUID.class).optional().orElseThrow(()->new ApiException(CommonErrorCode.BAD_REQUEST)));
            if(!roleIds.isEmpty()) {
                if(!rights.use().containsKey("system:account:assign-role"))throw new ApiException(CommonErrorCode.FORBIDDEN);
                authorization.account(actor,"system:account:assign-role",row.getId());
                assignments.apply(actor,row.getId(),new RoleAssignmentInput(List.copyOf(roleIds),List.of()));
            }
            return row.getId();
        });
    }
    public boolean profile(UUID actor,UUID target,AccountProfileInput input,String trace) {
        return actions.run(actor,"system:account:update","account",target.toString(),trace,()->{
            authorization.account(actor,"system:account:update",target);
            if(input.username()==null||!input.username().matches("[\\p{L}\\p{N}_.@-]{3,50}")
                    || input.remark()!=null&&input.remark().length()>500 || input.nickname()!=null&&input.nickname().length()>100
                    || input.email()!=null&&input.email().length()>254 || input.phone()!=null&&input.phone().length()>32) {
                throw new ApiException(CommonErrorCode.BAD_REQUEST);
            }
            writes.changeBasic(target,input);writes.changeProfile(target,input);
            return true;
        });
    }
}
