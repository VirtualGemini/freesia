package com.freesia.system.audit.internal.operation.dto;

import java.time.Instant;

public record OperationObservation(String action, String targetType, String targetId, boolean success,
                                   String outcomeCode, String traceId, Instant occurredAt) {}
