package com.freesia.system.iam.internal.role.dto;

import com.freesia.system.iam.internal.account.dto.VisibleRoleMembers.Member;
import com.freesia.system.iam.internal.role.dto.RoleDeletionImpact.RoleImpact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Server-only confirmation state; complete bindings must never become an HTTP response. */
public record RoleDeletionSnapshot(List<RoleDetail> definitions, List<Binding> bindings, List<RoleImpact> impacts,
                                   List<Member> records, boolean accountsComplete) {
    public record Binding(UUID roleId, UUID accountId, String username, UUID organizationId,
                          boolean enabled, boolean deleted, Instant updatedAt) {}
}
