package com.freesia.common.audit;

import java.time.Instant;
import java.util.UUID;

/** Successful writes join the caller transaction; failures are published only after its rollback. */
public record OperationAudit(UUID actorId, String action, String targetType, String targetId,
                             boolean success, String outcomeCode, String traceId, Instant occurredAt) {}
