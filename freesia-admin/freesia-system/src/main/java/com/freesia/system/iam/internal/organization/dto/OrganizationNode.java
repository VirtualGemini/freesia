package com.freesia.system.iam.internal.organization.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrganizationNode(UUID id, UUID parentId, String code, String name, String remark, int sort,
                               boolean root, Instant createdAt, Instant updatedAt, List<OrganizationNode> children) {}
