package com.freesia.system.iam.internal.account.service;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.account.mapper.AccountWrites;
import com.freesia.system.iam.internal.account.query.AccountLifecycleQueries;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.password.service.Passwords;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ResetAccountPassword {
    private static final String PERMISSION = "system:account:reset-password";
    private final ManagementActions actions;
    private final AccountLifecycleQueries accounts;
    private final AccountWrites writes;
    private final Passwords passwords;
    private final Sessions sessions;

    public ResetAccountPassword(ManagementActions actions, AccountLifecycleQueries accounts,
                                AccountWrites writes, Passwords passwords, Sessions sessions) {
        this.actions = actions; this.accounts = accounts; this.writes = writes;
        this.passwords = passwords; this.sessions = sessions;
    }

    public boolean reset(UUID actor, UUID target, String newPassword, String trace) {
        return actions.run(actor, PERMISSION, "account", target.toString(), trace, () -> {
            var account = accounts.lock(actor, PERMISSION, List.of(target)).getFirst();
            if (!account.enabled() || newPassword == null || newPassword.isBlank()) {
                throw new ApiException(CommonErrorCode.BAD_REQUEST);
            }
            String hash = passwords.hash(newPassword);
            // Same conservative ordering as lifecycle: a subsequent PG/audit rollback
            // preserves the original password but never recreates revoked sessions.
            sessions.revokeAll(target);
            writes.resetPassword(target, hash);
            return true;
        });
    }
}
