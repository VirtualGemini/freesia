package com.freesia.system.iam.internal.role.controller;

import com.freesia.common.result.R;
import com.freesia.common.result.PageResult;
import com.freesia.system.iam.internal.account.dto.VisibleRoleMembers;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.framework.security.RequirePermission;
import com.freesia.system.iam.internal.role.dto.*;
import com.freesia.system.iam.internal.role.query.RoleManagementQueries;
import com.freesia.system.iam.internal.role.service.SaveRole;
import com.freesia.system.iam.internal.session.service.Sessions;
import com.freesia.system.iam.internal.account.query.AccountManagementQueries;
import com.freesia.system.iam.internal.authorization.query.PermissionQueries;
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
class RoleManagementController {
    private final Sessions sessions; private final RoleManagementQueries roles; private final SaveRole save;
    private final AccountManagementQueries accounts;
    private final PermissionQueries permissions;
    RoleManagementController(Sessions sessions, RoleManagementQueries roles, SaveRole save, AccountManagementQueries accounts,PermissionQueries permissions) {
        this.sessions = sessions; this.roles = roles; this.save = save; this.accounts=accounts;this.permissions=permissions;
    }
    @RequirePermission("system:role:query")
    @GetMapping("/api/role/list")
    R<PageResult<RoleDetail>> list(@RequestHeader("Authorization") String token,
            @RequestParam(defaultValue="") String roleName, @RequestParam(defaultValue="") String roleCode,
            @RequestParam(defaultValue="")String description,@RequestParam(required=false)Boolean enabled,
            @RequestParam(required=false)Integer type,@RequestParam(required=false)java.time.Instant createTimeStart,
            @RequestParam(required=false)java.time.Instant createTimeEnd,@RequestParam(required=false)java.time.Instant updateTimeStart,
            @RequestParam(required=false)java.time.Instant updateTimeEnd,
            @RequestParam(defaultValue="1") int current, @RequestParam(defaultValue="20") int size) {
        var search=new RoleSearch(roleName,roleCode,description,enabled,type,createTimeStart,createTimeEnd,updateTimeStart,updateTimeEnd);
        var all = roles.list(sessions.authenticate(token), roleName, roleCode).stream().filter(search::matches).toList();
        int limit=Math.clamp(size,1,100), start=(int)Math.min(all.size(),Math.max(0L,(long)(current-1)*limit));
        return R.ok(new PageResult<>(all.subList(start,Math.min(all.size(),start+limit)),current,limit,all.size()));
    }
    @RequireAuthenticated
    @PostMapping("/api/role")
    R<UUID> create(@RequestHeader("Authorization") String token, @RequestBody RoleInput input) {
        return R.ok(save.save(sessions.authenticate(token), null, input, org.slf4j.MDC.get("traceId")));
    }
    @RequireAuthenticated
    @PutMapping("/api/role/{id}")
    R<UUID> update(@RequestHeader("Authorization") String token, @PathVariable UUID id, @RequestBody RoleInput input) {
        return R.ok(save.save(sessions.authenticate(token), id, input, org.slf4j.MDC.get("traceId")));
    }
    @RequirePermission("system:role:query")
    @GetMapping("/api/role/{id}")
    R<RoleDetail> detail(@RequestHeader("Authorization") String token, @PathVariable UUID id) {
        return R.ok(roles.detail(sessions.authenticate(token), id));
    }
    @RequireAuthenticated
    @GetMapping("/api/role/permission-options")
    R<List<PermissionOption>> options(@RequestHeader("Authorization") String token) {
        UUID actor=sessions.authenticate(token);var functions=permissions.permissionsOf(actor);
        if(java.util.Set.of("system:role:query","system:role:create","system:role:update","system:role:permission")
                .stream().noneMatch(functions::contains))throw new ApiException(CommonErrorCode.FORBIDDEN);
        return R.ok(roles.options(actor));
    }
    @RequirePermission("system:role:members")
    @GetMapping("/api/role/{id}/members")
    R<VisibleRoleMembers> members(@RequestHeader("Authorization")String token,@PathVariable UUID id) {
        UUID actor=sessions.authenticate(token);roles.detail(actor,id);
        return R.ok(accounts.members(actor,id));
    }
    @RequireAuthenticated
    @PutMapping("/api/role/{id}/permissions")
    R<UUID> permissions(@RequestHeader("Authorization")String token,@PathVariable UUID id,@RequestBody RoleInput input) {
        return R.ok(save.permissions(sessions.authenticate(token),id,input,org.slf4j.MDC.get("traceId")));
    }
    record RoleState(boolean enabled) {}
    @RequireAuthenticated
    @PutMapping("/api/role/{id}/status")
    R<Boolean> state(@RequestHeader("Authorization")String token,@PathVariable UUID id,@RequestBody RoleState input) {
        return R.ok(save.status(sessions.authenticate(token),id,input.enabled(),org.slf4j.MDC.get("traceId")));
    }
}
