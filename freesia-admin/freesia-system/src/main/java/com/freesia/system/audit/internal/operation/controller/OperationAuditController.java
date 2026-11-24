package com.freesia.system.audit.internal.operation.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.AuthenticatedSessionSource;
import com.freesia.framework.security.RequirePermission;
import com.freesia.system.audit.internal.operation.query.OperationAuditQueries;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
class OperationAuditController {
    private final OperationAuditQueries queries;
    private final AuthenticatedSessionSource sessions;
    OperationAuditController(OperationAuditQueries queries,AuthenticatedSessionSource sessions) {
        this.queries=queries;this.sessions=sessions;
    }
    @RequirePermission("system:log:operation:query")
    @GetMapping("/api/log/operation/observations")
    R<List<com.freesia.system.audit.internal.operation.dto.OperationObservation>> observations(@RequestHeader("Authorization")String token,@RequestParam UUID targetId) {
        return R.ok(queries.observations(sessions.authenticate(token,false),targetId.toString()));
    }
}
