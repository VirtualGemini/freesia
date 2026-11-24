package com.freesia.system.iam.internal.organization.service;

import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.organization.dto.OrganizationInput;
import com.freesia.system.iam.internal.organization.mapper.OrganizationWrites;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ChangeOrganization {
    private final JdbcClient jdbc;
    private final OrganizationWrites writes;
    private final ManagementActions actions;
    private final AuthorizationQueries authorization;

    public ChangeOrganization(JdbcClient jdbc, OrganizationWrites writes, ManagementActions actions,AuthorizationQueries authorization) {
        this.jdbc = jdbc; this.writes = writes; this.actions = actions;this.authorization=authorization;
    }

    public boolean update(UUID actor, UUID id, OrganizationInput input, String trace) {
        return actions.run(actor, "system:organization:update", "organization", id.toString(), trace, () -> {
            boolean root = root(id);
            authorization.organization(actor,"system:organization:update",id);
            if (root && (input.parentId() != null || !"ROOT".equals(input.code()) || !owner(actor))) {
                throw new ApiException(CommonErrorCode.FORBIDDEN);
            }
            if(root&&input.sort()!=jdbc.sql("SELECT sort FROM freesia.sys_organization WHERE id=:id").param("id",id)
                    .query(Integer.class).single())throw new ApiException(CommonErrorCode.FORBIDDEN);
            if (!root) {
                UUID previous=jdbc.sql("SELECT parent_id FROM freesia.sys_organization WHERE id=:id").param("id",id).query(UUID.class).single();
                UUID parent=input.parentId()==null?previous:input.parentId();
                boolean cycle = jdbc.sql("""
                        WITH RECURSIVE children AS (
                            SELECT id FROM freesia.sys_organization WHERE id=:id
                            UNION ALL SELECT o.id FROM freesia.sys_organization o JOIN children c ON o.parent_id=c.id)
                        SELECT EXISTS(SELECT 1 FROM children WHERE id=:parent)
                        """).param("id", id).param("parent", parent).query(Boolean.class).single();
                if (cycle) throw new ApiException(IamErrorCode.ORGANIZATION_CYCLE);
                if(!previous.equals(parent))authorization.organization(actor,"system:organization:update",parent);
                return writes.changeDetails(id,new OrganizationInput(parent,input.code(),input.name(),input.remark(),input.sort()))==1;
            }
            return writes.changeDetails(id, input) == 1;
        });
    }

    public boolean delete(UUID actor, UUID id, String trace) {
        return actions.run(actor, "system:organization:delete", "organization", id.toString(), trace, () -> {
            if (root(id)) throw new ApiException(IamErrorCode.EXCLUSIVE_OWNER);
            authorization.organization(actor,"system:organization:delete",id);
            if (jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM freesia.sys_organization WHERE parent_id=:id)
                        OR EXISTS(SELECT 1 FROM freesia.sys_account WHERE organization_id=:id AND NOT deleted)
                        OR EXISTS(SELECT 1 FROM freesia.sys_role_scope_organization s JOIN freesia.sys_role r ON r.id=s.role_id
                            WHERE s.organization_id=:id AND NOT r.deleted)
                    """).param("id", id).query(Boolean.class).single()) throw new ApiException(IamErrorCode.ORGANIZATION_REFERENCED);
            return writes.remove(id) == 1;
        });
    }

    private boolean root(UUID id) {
        return jdbc.sql("SELECT is_root FROM freesia.sys_organization WHERE id=:id").param("id", id)
                .query(Boolean.class).optional().orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
    }
    private boolean owner(UUID actor) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_bootstrap WHERE account_id=:id AND completed_at IS NOT NULL)")
                .param("id", actor).query(Boolean.class).single();
    }
}
