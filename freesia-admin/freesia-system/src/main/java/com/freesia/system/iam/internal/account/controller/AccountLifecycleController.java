package com.freesia.system.iam.internal.account.controller;

import com.freesia.common.result.R;
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.framework.security.RequireAuthenticated;
import com.freesia.system.iam.internal.account.service.AccountLifecycle;
import com.freesia.system.iam.internal.account.dto.AccountDeletionPreview;
import com.freesia.system.iam.internal.account.dto.AccountStatusPreview;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
class AccountLifecycleController {
    private final AccountLifecycle lifecycle;
    private final Sessions sessions;
    AccountLifecycleController(AccountLifecycle lifecycle, Sessions sessions) {
        this.lifecycle = lifecycle; this.sessions = sessions;
    }
    record Status(Boolean enabled, String status) {}

    @RequireAuthenticated
    @PutMapping("/api/account/{id}/status")
    R<Boolean> status(@RequestHeader("Authorization") String token, @PathVariable UUID id, @RequestBody Status input) {
        if (input.enabled() != null && input.status() != null || input.enabled() == null && input.status() == null) {
            throw new ApiException(CommonErrorCode.BAD_REQUEST);
        }
        String status = input.status() != null ? input.status() : input.enabled() ? "1" : "2";
        return R.ok(lifecycle.status(sessions.authenticate(token), List.of(id), status, org.slf4j.MDC.get("traceId")));
    }

    @RequireAuthenticated
    @PutMapping("/api/account/disable-batch")
    R<Boolean> disable(@RequestHeader("Authorization") String token, @RequestParam List<UUID> ids) {
        return R.ok(lifecycle.status(sessions.authenticate(token), ids, "2", org.slf4j.MDC.get("traceId")));
    }

    record Confirmation(String confirmationToken) {}

    @RequireAuthenticated
    @GetMapping("/api/account/lifecycle-preview")
    R<AccountStatusPreview> statusPreview(@RequestHeader("Authorization") String token, @RequestParam List<UUID> ids,
                                         @RequestParam String action) {
        if (!List.of("enable", "disable").contains(action)) throw new ApiException(CommonErrorCode.BAD_REQUEST);
        return R.ok(lifecycle.statusPreview(sessions.authenticate(token), ids, action.equals("enable")));
    }

    @RequireAuthenticated
    @GetMapping("/api/account/deletion-preview")
    R<AccountDeletionPreview> preview(@RequestHeader("Authorization") String token, @RequestParam List<UUID> ids) {
        return R.ok(lifecycle.preview(sessions.authenticate(token), ids));
    }

    @RequireAuthenticated
    @DeleteMapping("/api/account/{id}")
    R<Boolean> delete(@RequestHeader("Authorization") String token, @PathVariable UUID id,
                      @RequestBody(required = false) Confirmation input) {
        return R.ok(lifecycle.delete(sessions.authenticate(token), List.of(id),
                input == null ? null : input.confirmationToken(), org.slf4j.MDC.get("traceId")));
    }

    @RequireAuthenticated
    @DeleteMapping("/api/account/delete-batch")
    R<Boolean> deleteBatch(@RequestHeader("Authorization") String token, @RequestParam List<UUID> ids,
                           @RequestBody(required = false) Confirmation input) {
        return R.ok(lifecycle.delete(sessions.authenticate(token), ids,
                input == null ? null : input.confirmationToken(), org.slf4j.MDC.get("traceId")));
    }
}
