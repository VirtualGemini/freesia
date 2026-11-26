package com.freesia.system.iam.internal.account.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Server-only locked state used for fresh deletion confirmation. */
public record AccountLifecycleTarget(UUID accountId, String username, boolean enabled, boolean abnormal, long version,
                                     Instant updatedAt, List<UUID> roleIds) {}
