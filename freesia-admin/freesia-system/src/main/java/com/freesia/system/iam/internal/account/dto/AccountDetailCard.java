package com.freesia.system.iam.internal.account.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccountDetailCard(Snapshot header, Profile profile, Snapshot account, Security security,
                                List<String> thirdPartyProviders) {
    public record AssignedRole(UUID roleId, String roleCode, String roleName) {}
    public record Snapshot(UUID accountId, String username, String nickname, String remark, UUID organizationId,
                           String organizationName, String avatar, String status, String activeStatus,
                           Instant createTime, Instant updateTime, List<AssignedRole> roles,
                           List<String> roleCodes, boolean pendingDeletion) {}
    public record Profile(String nickname, String displayEmail, String displayMobile, List<String> tags) {}
    public record Security(boolean emailMfaEnabled, boolean totpMfaEnabled, List<String> loginMethods,
                           List<String> disabledLoginMethods, List<String> allowedLoginMethods) {}
}
