package com.freesia.system.iam.internal.role.dto;

import java.util.UUID;

public record RoleOption(UUID roleId, String roleCode, String roleName, int roleLevel) {}
