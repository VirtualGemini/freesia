package com.freesia.system.iam.internal.account.dto;

import java.util.List;
import java.util.UUID;

public record VisibleRoleMembers(List<Member> records, long visibleCount) {
    public record Member(UUID accountId, String username) {}
}
