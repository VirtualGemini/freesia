package com.freesia.system.iam.internal.authorization.dto;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record EffectiveRights(UUID accountId, UUID organizationId, boolean owner,
                              Set<UUID> menuIds, Set<UUID> grantMenuIds,
                              Map<String, DataRange> use, Map<String, DataRange> grant,
                              Map<UUID,DataRange> grantRanges) {
    public DataRange range(String permission) { return use.getOrDefault(permission, DataRange.none()); }
}
