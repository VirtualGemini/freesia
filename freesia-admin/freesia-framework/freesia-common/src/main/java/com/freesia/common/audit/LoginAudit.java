package com.freesia.common.audit;

import java.time.Instant;
import java.util.UUID;

/** Synchronously persisted before successful credentials are exposed. Never carries secrets. */
public record LoginAudit(UUID accountId, String username, String sessionId, String eventType,
                         boolean success, String outcomeCode, ClientOrigin origin, Instant occurredAt) {}
