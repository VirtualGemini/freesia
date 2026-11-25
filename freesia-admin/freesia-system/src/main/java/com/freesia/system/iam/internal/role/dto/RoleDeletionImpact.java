package com.freesia.system.iam.internal.role.dto;

import com.freesia.system.iam.internal.account.dto.VisibleRoleMembers.Member;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Counts and identities are query-visible only; false completeness never reveals hidden membership. */
public record RoleDeletionImpact(String confirmationToken, Instant expiresAt, List<RoleImpact> roles,
                                 List<Member> records, long visibleCount, boolean accountsComplete) {
    public record RoleImpact(UUID roleId, String roleCode, String roleName, List<Member> records, long visibleCount) {}
}
