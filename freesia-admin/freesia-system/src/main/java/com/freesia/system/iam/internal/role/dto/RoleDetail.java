package com.freesia.system.iam.internal.role.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoleDetail(UUID roleId, String roleCode, String roleName, String description, boolean enabled,
                         int type, String dataScope, List<UUID> customOrganizationIds,
                         List<UUID> menuIds, List<UUID> grantMenuIds, Instant createTime, Instant updateTime) {}
