package com.freesia.system.iam.internal.authorization.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.authorization.query.PermissionQueries;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
class PermissionController {
    private final PermissionQueries permissions;
    private final Sessions sessions;

    PermissionController(PermissionQueries permissions, Sessions sessions) {
        this.permissions = permissions;
        this.sessions = sessions;
    }

    @RequireAuthenticated
    @GetMapping("/api/iam/permissions")
    R<Set<String>> permissions(@RequestHeader("Authorization") String token) {
        return R.ok(permissions.permissionsOf(sessions.authenticate(token)));
    }
}
