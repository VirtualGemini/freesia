package com.freesia.system.iam.internal.authorization.dto;

import java.util.Map;
import java.util.UUID;

public record PermissionProfile(Map<String,DataRange> use, Map<String,DataRange> grant, UUID organizationId) {}
