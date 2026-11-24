package com.freesia.system.iam.internal.account.dto;

import java.util.List;
import java.util.UUID;

public record RoleAssignmentInput(List<UUID> addRoleIds, List<UUID> removeRoleIds) {}
