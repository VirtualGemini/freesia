package com.freesia.system.iam.internal.role.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.role.dto.RoleDeletionImpact;
import com.freesia.system.iam.internal.role.service.DeleteRoles;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@RestController
class RoleDeletionController {
    private final Sessions sessions;
    private final DeleteRoles deletion;

    RoleDeletionController(Sessions sessions, DeleteRoles deletion) {
        this.sessions = sessions;
        this.deletion = deletion;
    }

    @RequireAuthenticated
    @GetMapping("/api/role/bound-accounts")
    R<RoleDeletionImpact> preview(@RequestHeader("Authorization") String token, @RequestParam List<UUID> ids) {
        return R.ok(deletion.preview(sessions.authenticate(token), ids));
    }

    record Confirmation(String confirmationToken) {}

    @RequireAuthenticated
    @DeleteMapping("/api/role/{id}")
    R<Boolean> delete(@RequestHeader("Authorization") String token, @PathVariable UUID id,
                      @RequestBody(required = false) Confirmation input) {
        return R.ok(deletion.delete(sessions.authenticate(token), List.of(id),
                input == null ? null : input.confirmationToken(), org.slf4j.MDC.get("traceId")));
    }

    @RequireAuthenticated
    @DeleteMapping("/api/role/delete-batch")
    R<Boolean> batch(@RequestHeader("Authorization") String token, @RequestParam List<UUID> ids,
                     @RequestBody(required = false) Confirmation input) {
        return R.ok(deletion.delete(sessions.authenticate(token), ids,
                input == null ? null : input.confirmationToken(), org.slf4j.MDC.get("traceId")));
    }
}
