package com.freesia.admin.iam.internal.auth;

import com.freesia.admin.iam.api.IamErrorCode;
import com.freesia.admin.iam.internal.account.AccountWrites;
import com.freesia.admin.iam.internal.password.Passwords;
import com.freesia.common.error.ApiException;
import com.freesia.common.audit.ClientOrigin;
import com.freesia.common.audit.LoginAudit;
import com.freesia.common.error.CommonErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PasswordLogin {
    private final AccountQueries accounts;
    private final AccountWrites writes;
    private final Passwords passwords;
    private final Sessions sessions;
    private final ApplicationEventPublisher events;
    private final PasswordFailures failures;
    public PasswordLogin(AccountQueries accounts, AccountWrites writes, Passwords passwords, Sessions sessions, ApplicationEventPublisher events, PasswordFailures failures) {
        this.accounts = accounts;
        this.writes = writes;
        this.passwords = passwords;
        this.sessions = sessions;
        this.events = events;
        this.failures = failures;
    }
    public SessionCredentials login(String username, String password, ClientOrigin origin) {
        try { failures.check(username, origin.clientIp()); }
        catch (ApiException paused) {
            failure(null, username, origin, "PASSWORD_PAUSED");
            throw paused;
        }
        var account = accounts.credential(username);
        String upgraded = passwords.verifyAndUpgrade(password, account.map(AccountQueries.Credential::passwordHash).orElse(null));
        if (upgraded == null || account.isEmpty() || !account.get().enabled() || account.get().deleted()) {
            try { failures.failed(username, origin.clientIp()); }
            catch (ApiException paused) {
                failure(account.map(AccountQueries.Credential::id).orElse(null), username, origin, "PASSWORD_PAUSED");
                throw paused;
            }
            failure(account.map(AccountQueries.Credential::id).orElse(null), username, origin, "INVALID_CREDENTIALS");
            throw new ApiException(IamErrorCode.INVALID_CREDENTIALS);
        }
        var valid = account.get();
        try { failures.succeeded(username, origin.clientIp()); }
        catch (ApiException paused) {
            failure(valid.id(), username, origin, "PASSWORD_PAUSED");
            throw paused;
        }
        if (!upgraded.equals(valid.passwordHash())) writes.upgradePassword(valid.id(), valid.passwordHash(), upgraded);
        var credentials = sessions.create(valid.id(), valid.username(), accounts.policy());
        try {
            events.publishEvent(new LoginAudit(valid.id(), valid.username(), credentials.sessionId(), "LOGIN", true, "SUCCESS", origin, Instant.now()));
            sessions.activate(credentials.sessionId());
            return credentials;
        } catch (RuntimeException failedAudit) {
            try { sessions.revoke(credentials.sessionId(), credentials.token()); }
            catch (RuntimeException cleanupFailure) {
                org.slf4j.LoggerFactory.getLogger(PasswordLogin.class).error("Pending login cleanup unavailable; unactivated session is unusable");
            }
            throw new ApiException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private void failure(java.util.UUID account, String username, ClientOrigin origin, String outcome) {
        try { events.publishEvent(new LoginAudit(account, username, null, "LOGIN", false, outcome, origin, Instant.now())); }
        catch (RuntimeException auditFailure) {
            org.slf4j.LoggerFactory.getLogger(PasswordLogin.class).error("Failed login audit could not be persisted");
            throw new ApiException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
