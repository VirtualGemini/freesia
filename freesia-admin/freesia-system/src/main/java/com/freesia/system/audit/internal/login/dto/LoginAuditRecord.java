package com.freesia.system.audit.internal.login.dto;

import java.time.Instant;
import java.util.UUID;

public record LoginAuditRecord(UUID id, UUID accountId, String username, String sessionId, String eventType,
                              String loginMethod, int result, String outcomeCode, String clientIp, String traceId,
                              String countryName, String provinceName, String cityName, String isp, String countryCode,
                              Instant eventTime, Instant createTime) {}
