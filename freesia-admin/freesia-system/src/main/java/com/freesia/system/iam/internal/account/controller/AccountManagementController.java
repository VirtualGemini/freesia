package com.freesia.system.iam.internal.account.controller;

import com.freesia.common.result.R;
import com.freesia.common.result.PageResult;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.framework.security.RequirePermission;
import com.freesia.system.iam.internal.account.dto.*;
import com.freesia.system.iam.internal.account.query.AccountManagementQueries;
import com.freesia.system.iam.internal.account.service.ManageAccounts;
import com.freesia.system.iam.internal.account.service.AssignAccountRoles;
import com.freesia.system.iam.internal.account.service.TransferAccount;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
class AccountManagementController {
    private final AccountManagementQueries accounts;private final ManageAccounts management;private final Sessions sessions;
    private final AssignAccountRoles assignments;
    private final TransferAccount transfers;
    AccountManagementController(AccountManagementQueries accounts,ManageAccounts management,Sessions sessions,AssignAccountRoles assignments,
                                TransferAccount transfers) {
        this.accounts=accounts;this.management=management;this.sessions=sessions;this.assignments=assignments;this.transfers=transfers;
    }
    @RequirePermission("system:account:query")
    @GetMapping("/api/account/list")
    R<PageResult<ManagedAccount>> list(@RequestHeader("Authorization")String token,
            @RequestParam(defaultValue="")String username,@RequestParam(defaultValue="")String email,
            @RequestParam(defaultValue="")String remark,@RequestParam(defaultValue="")String status,
            @RequestParam(required=false)java.time.Instant createTimeStart,@RequestParam(required=false)java.time.Instant createTimeEnd,
            @RequestParam(required=false)java.time.Instant updateTimeStart,@RequestParam(required=false)java.time.Instant updateTimeEnd,
            @RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size) {
        return R.ok(accounts.list(sessions.authenticate(token),username,email,remark,status,current,size,createTimeStart,createTimeEnd,updateTimeStart,updateTimeEnd));
    }
    @RequirePermission("system:account:query")
    @GetMapping("/api/account/{id}/detail-card")
    R<AccountDetailCard> detail(@RequestHeader("Authorization")String token,@PathVariable UUID id) {
        return R.ok(accounts.detail(sessions.authenticate(token),id));
    }
    @RequireAuthenticated
    @PostMapping("/api/account")
    R<UUID> create(@RequestHeader("Authorization")String token,@RequestBody CreateAccountInput input) {
        return R.ok(management.create(sessions.authenticate(token),input,org.slf4j.MDC.get("traceId")));
    }
    @RequireAuthenticated
    @PutMapping("/api/account/{id}")
    R<Boolean> update(@RequestHeader("Authorization")String token,@PathVariable UUID id,@RequestBody AccountProfileInput input) {
        return R.ok(management.profile(sessions.authenticate(token),id,input,org.slf4j.MDC.get("traceId")));
    }
    @RequireAuthenticated
    @PutMapping("/api/account/{id}/roles")
    R<Boolean> assign(@RequestHeader("Authorization")String token,@PathVariable UUID id,@RequestBody RoleAssignmentInput input) {
        return R.ok(assignments.assign(sessions.authenticate(token),id,input,org.slf4j.MDC.get("traceId")));
    }
    record OrganizationChange(UUID organizationId) {}
    @RequireAuthenticated
    @PutMapping("/api/account/{id}/organization")
    R<Boolean> transfer(@RequestHeader("Authorization")String token,@PathVariable UUID id,@RequestBody OrganizationChange input) {
        return R.ok(transfers.transfer(sessions.authenticate(token),id,input.organizationId(),org.slf4j.MDC.get("traceId")));
    }
}
