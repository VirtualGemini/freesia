package com.freesia.system.iam.internal.role.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequirePermission;
import com.freesia.system.iam.internal.role.dto.RoleOption;
import com.freesia.system.iam.internal.role.query.RoleQueries;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class RoleController {
    private final RoleQueries roles;
    private final Sessions sessions;

    RoleController(RoleQueries roles, Sessions sessions) {
        this.roles = roles;
        this.sessions = sessions;
    }

    @RequirePermission("system:account:query")
    @GetMapping("/api/role/options")
    R<List<RoleOption>> roles(@RequestHeader("Authorization") String token) {
        return R.ok(roles.assignableRoles(sessions.authenticate(token)));
    }
}
