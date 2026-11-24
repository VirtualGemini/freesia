package com.freesia.system.iam.internal.role.dto;

import java.util.UUID;

public record PermissionOption(UUID id, UUID parentId, String name, String title, String authMark) {}
