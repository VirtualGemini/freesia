package com.freesia.system.iam.internal.authorization.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.authorization.query.PermissionQueries;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
class PermissionController {
    private final PermissionQueries permissions;
    private final Sessions sessions;
    private final AuthorizationQueries authorization;

    PermissionController(PermissionQueries permissions, Sessions sessions,AuthorizationQueries authorization) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.authorization=authorization;
    }

    @RequireAuthenticated
    @GetMapping("/api/iam/permissions")
    R<Set<String>> permissions(@RequestHeader("Authorization") String token) {
        return R.ok(permissions.permissionsOf(sessions.authenticate(token)));
    }
    @RequireAuthenticated
    @GetMapping("/api/iam/permission-profile")
    R<com.freesia.system.iam.internal.authorization.dto.PermissionProfile> profile(@RequestHeader("Authorization")String token) {
        return R.ok(authorization.profile(sessions.authenticate(token)));
    }
}
