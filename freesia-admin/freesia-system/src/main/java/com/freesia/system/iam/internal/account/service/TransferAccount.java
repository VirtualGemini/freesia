package com.freesia.system.iam.internal.account.service;

import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.account.mapper.AccountWrites;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class TransferAccount {
    private final JdbcClient jdbc;private final AuthorizationQueries authorization;private final ManagementActions actions;
    private final AccountWrites writes;
    public TransferAccount(JdbcClient jdbc,AuthorizationQueries authorization,ManagementActions actions,AccountWrites writes) {
        this.jdbc=jdbc;this.authorization=authorization;this.actions=actions;this.writes=writes;
    }
    public boolean transfer(UUID actor,UUID target,UUID destination,String trace) {
        return actions.run(actor,"system:account:transfer","account",target.toString(),trace,()->{
            var before=authorization.rights(actor);
            authorization.account(actor,"system:account:transfer",target);
            if(jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_bootstrap WHERE account_id=:id)").param("id",target)
                    .query(Boolean.class).single())throw new ApiException(CommonErrorCode.FORBIDDEN);
            if(destination!=null) {
                boolean root=jdbc.sql("SELECT is_root FROM freesia.sys_organization WHERE id=:id").param("id",destination)
                        .query(Boolean.class).optional().orElseThrow(()->new ApiException(CommonErrorCode.BAD_REQUEST));
                if(root)throw new ApiException(CommonErrorCode.FORBIDDEN);
                if(!before.range("system:account:transfer").all()
                        &&!before.range("system:account:transfer").organizationIds().contains(destination)) {
                    throw new ApiException(CommonErrorCode.FORBIDDEN);
                }
            } else if(!before.range("system:account:transfer").all())throw new ApiException(CommonErrorCode.FORBIDDEN);
            var relative=jdbc.sql("""
                    SELECT DISTINCT r.data_scope FROM freesia.sys_account_role ar JOIN freesia.sys_role r ON r.id=ar.role_id
                    WHERE ar.account_id=:id AND NOT r.deleted AND r.data_scope IN ('ORG','SUB_ORG','ORG_AND_SUB')
                    """).param("id",target).query(String.class).list();
            if(destination==null&&!relative.isEmpty())throw new ApiException(IamErrorCode.ORGANIZATION_REQUIRED);
            for(String scope:relative)if(!before.range("system:account:transfer")
                    .contains(authorization.scope(scope,java.util.List.of(),destination),before.organizationId())) {
                throw new ApiException(CommonErrorCode.FORBIDDEN);
            }
            return writes.moveOrganization(target,destination)==1;
        });
    }
}
