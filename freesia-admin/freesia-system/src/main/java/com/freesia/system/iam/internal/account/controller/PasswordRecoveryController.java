package com.freesia.system.iam.internal.account.controller;

import com.freesia.common.result.R;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.account.dto.ResetPasswordInput;
import com.freesia.system.iam.internal.account.service.ResetAccountPassword;
import com.freesia.system.iam.internal.session.service.Sessions;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
class PasswordRecoveryController {
    private final ResetAccountPassword reset;
    private final Sessions sessions;
    PasswordRecoveryController(ResetAccountPassword reset, Sessions sessions) {
        this.reset = reset; this.sessions = sessions;
    }

    @RequireAuthenticated
    @PutMapping("/api/account/{id}/security/password")
    R<Boolean> reset(@RequestHeader("Authorization") String token, @PathVariable UUID id,
                     @RequestBody ResetPasswordInput input, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok(reset.reset(sessions.authenticate(token), id, input.newPassword(), org.slf4j.MDC.get("traceId")));
    }
}
