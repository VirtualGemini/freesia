package com.freesia.system.iam.internal.account.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.account.dto.AccountInfo;
import com.freesia.system.iam.internal.account.query.AccountQueries;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {
    private final AccountQueries accounts;
    private final Sessions sessions;

    AccountController(AccountQueries accounts, Sessions sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @RequireAuthenticated
    @GetMapping("/api/account/info")
    R<AccountInfo> current(@RequestHeader("Authorization") String token) {
        return R.ok(accounts.current(sessions.authenticate(token)));
    }
}
