package com.freesia.system.iam.internal.organization.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequirePermission;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.organization.dto.OrganizationInput;
import com.freesia.system.iam.internal.organization.dto.OrganizationNode;
import com.freesia.system.iam.internal.organization.query.OrganizationQueries;
import com.freesia.system.iam.internal.organization.service.CreateOrganization;
import com.freesia.system.iam.internal.organization.service.ChangeOrganization;
import com.freesia.system.iam.internal.authorization.query.PermissionQueries;
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.session.service.Sessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
class OrganizationController {
    private final OrganizationQueries organizations;
    private final CreateOrganization create;
    private final Sessions sessions;
    private final ChangeOrganization change;
    private final PermissionQueries permissions;
    OrganizationController(OrganizationQueries organizations, CreateOrganization create, Sessions sessions, ChangeOrganization change,
                           PermissionQueries permissions) {
        this.organizations = organizations; this.create = create; this.sessions = sessions; this.change = change;
        this.permissions=permissions;
    }
    @RequirePermission("system:organization:query")
    @GetMapping("/api/organization/tree")
    R<List<OrganizationNode>> tree(@RequestHeader("Authorization")String token) {
        return R.ok(organizations.tree(sessions.authenticate(token)));
    }
    @RequireAuthenticated
    @GetMapping("/api/organization/options")
    R<List<OrganizationNode>> options(@RequestHeader("Authorization")String token,@RequestParam String permission) {
        UUID actor=sessions.authenticate(token);
        if(!java.util.Set.of("system:account:create","system:account:transfer","system:role:create",
                "system:role:update","system:role:permission").contains(permission)
                ||!permissions.permissionsOf(actor).contains(permission))throw new ApiException(CommonErrorCode.FORBIDDEN);
        return R.ok(organizations.treeFor(actor,permission));
    }

    @RequireAuthenticated
    @PostMapping("/api/organization")
    R<UUID> create(@RequestHeader("Authorization") String token, @Valid @RequestBody OrganizationInput input,
                   HttpServletRequest request) {
        return R.ok(create.create(sessions.authenticate(token), input, org.slf4j.MDC.get("traceId")));
    }
    @RequireAuthenticated
    @PutMapping("/api/organization/{id}")
    R<Boolean> update(@RequestHeader("Authorization") String token, @PathVariable UUID id,
                      @Valid @RequestBody OrganizationInput input) {
        return R.ok(change.update(sessions.authenticate(token), id, input, org.slf4j.MDC.get("traceId")));
    }
    @RequireAuthenticated
    @DeleteMapping("/api/organization/{id}")
    R<Boolean> delete(@RequestHeader("Authorization") String token, @PathVariable UUID id) {
        return R.ok(change.delete(sessions.authenticate(token), id, org.slf4j.MDC.get("traceId")));
    }
}
