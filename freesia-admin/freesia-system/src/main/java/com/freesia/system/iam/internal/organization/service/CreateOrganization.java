package com.freesia.system.iam.internal.organization.service;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.organization.dto.OrganizationInput;
import com.freesia.system.iam.internal.organization.entity.OrganizationRow;
import com.freesia.system.iam.internal.organization.mapper.OrganizationWrites;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CreateOrganization {
    private final JdbcClient jdbc;
    private final OrganizationWrites writes;
    private final ManagementActions actions;
    private final AuthorizationQueries authorization;

    public CreateOrganization(JdbcClient jdbc, OrganizationWrites writes, ManagementActions actions,
                              AuthorizationQueries authorization) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.actions = actions;
        this.authorization = authorization;
    }

    public UUID create(UUID actor, OrganizationInput input, String trace) {
        return actions.run(actor, "system:organization:create", "organization", null, trace, () -> {
            if (input.parentId() == null || !jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_organization WHERE id=:id)")
                    .param("id", input.parentId()).query(Boolean.class).single()) {
                throw new ApiException(CommonErrorCode.BAD_REQUEST);
            }
            authorization.organization(actor, "system:organization:create", input.parentId());
            var row = new OrganizationRow();
            row.setParentId(input.parentId());
            row.setCode(input.code().trim());
            row.setName(input.name().trim());
            row.setRemark(input.remark());
            row.setSort(input.sort());
            writes.insertSelective(row);
            return row.getId();
        });
    }
}
