package com.freesia.system.iam.internal.account.dto;

import java.time.Instant;
import java.util.UUID;

public record ManagedAccount(UUID accountId, String username, String remark, UUID organizationId,
                             String organizationName, String email, String avatar, String status,
                             String activeStatus, Instant createTime, Instant updateTime) {}
