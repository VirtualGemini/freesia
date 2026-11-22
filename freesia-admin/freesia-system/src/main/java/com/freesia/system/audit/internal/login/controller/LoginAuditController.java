package com.freesia.system.audit.internal.login.controller;

import com.freesia.common.result.PageResult;
import com.freesia.common.result.R;
import com.freesia.framework.security.RequirePermission;
import com.freesia.system.audit.internal.login.dto.LoginAuditRecord;
import com.freesia.system.audit.internal.login.query.LoginAuditQueries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The minimal authorized observation interface; full audit filtering/management is a later slice. */
@RestController
class LoginAuditController {
    private final LoginAuditQueries queries;

    LoginAuditController(LoginAuditQueries queries) { this.queries = queries; }

    @RequirePermission("system:log:login:query")
    @GetMapping("/api/log/login/list")
    R<PageResult<LoginAuditRecord>> list(@RequestParam(defaultValue="1") long current,
                                       @RequestParam(defaultValue="20") long size) {
        return R.ok(queries.list(current, size));
    }
}
