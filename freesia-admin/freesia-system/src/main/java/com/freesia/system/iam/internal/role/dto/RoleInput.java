package com.freesia.system.iam.internal.role.dto;

import java.util.List;
import java.util.UUID;

public record RoleInput(String roleCode, String roleName, String description, boolean enabled,
                        String dataScope, List<UUID> customOrganizationIds, List<UUID> menuIds, List<UUID> grantMenuIds) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name,Object value) {
        throw new IllegalArgumentException("Unsupported role field");
    }
}
