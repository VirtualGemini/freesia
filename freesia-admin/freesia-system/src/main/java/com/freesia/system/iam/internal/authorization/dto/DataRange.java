package com.freesia.system.iam.internal.authorization.dto;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A union, not an ordering of the six scope labels. Self is always the acting account. */
public record DataRange(boolean all, boolean self, Set<UUID> organizationIds) {
    public DataRange { organizationIds = Set.copyOf(organizationIds); }
    public static DataRange none() { return new DataRange(false, false, Set.of()); }
    public DataRange union(DataRange other) {
        var ids = new HashSet<>(organizationIds); ids.addAll(other.organizationIds);
        return new DataRange(all || other.all, self || other.self, ids);
    }
    public boolean allows(UUID actor, UUID account, UUID organization) {
        return all || (self && actor.equals(account)) || organization != null && organizationIds.contains(organization);
    }
    public boolean contains(DataRange other, UUID actorOrganization) {
        return all || !other.all && organizationIds.containsAll(other.organizationIds)
                && (!other.self || self || actorOrganization != null && organizationIds.contains(actorOrganization));
    }
}
