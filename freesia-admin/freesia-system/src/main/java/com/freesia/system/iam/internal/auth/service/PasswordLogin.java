package com.freesia.system.iam.internal.auth.service;

import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.account.mapper.AccountWrites;
import com.freesia.system.iam.internal.account.dto.AccountCredential;
import com.freesia.system.iam.internal.account.query.AccountQueries;
import com.freesia.system.iam.internal.password.service.Passwords;
import com.freesia.system.iam.internal.session.dto.SessionCredentials;
import com.freesia.system.iam.internal.session.query.SessionPolicyQueries;
import com.freesia.system.iam.internal.session.service.Sessions;
import com.freesia.common.error.ApiException;
import com.freesia.common.audit.ClientOrigin;
import com.freesia.common.audit.LoginAudit;
import com.freesia.common.error.CommonErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PasswordLogin {
    private final AccountQueries accounts;
    private final AccountWrites writes;
    private final Passwords passwords;
    private final Sessions sessions;
    private final SessionPolicyQueries policy;
    private final ApplicationEventPublisher events;
    private final PasswordFailures failures;
    private final TransactionTemplate transaction;
    public PasswordLogin(AccountQueries accounts, AccountWrites writes, Passwords passwords, Sessions sessions,
                         SessionPolicyQueries policy, ApplicationEventPublisher events, PasswordFailures failures,
                         PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.writes = writes;
        this.passwords = passwords;
        this.sessions = sessions;
        this.policy = policy;
        this.events = events;
        this.failures = failures;
        transaction = new TransactionTemplate(transactions);
    }
    public SessionCredentials login(String username, String password, ClientOrigin origin) {
        try { failures.check(username, origin.clientIp()); }
        catch (ApiException paused) {
            failure(null, username, origin, "PASSWORD_PAUSED");
            throw paused;
        }
        var account = accounts.credential(username);
        String upgraded = passwords.verifyAndUpgrade(password, account.map(AccountCredential::passwordHash).orElse(null));
        if (upgraded == null || account.isEmpty() || !account.get().enabled() || account.get().deleted()) {
            try { failures.failed(username, origin.clientIp()); }
            catch (ApiException paused) {
                failure(account.map(AccountCredential::id).orElse(null), username, origin, "PASSWORD_PAUSED");
                throw paused;
            }
            failure(account.map(AccountCredential::id).orElse(null), username, origin, "INVALID_CREDENTIALS");
            throw new ApiException(IamErrorCode.INVALID_CREDENTIALS);
        }
        var valid = account.get();
        try { failures.succeeded(username, origin.clientIp()); }
        catch (ApiException paused) {
            failure(valid.id(), username, origin, "PASSWORD_PAUSED");
            throw paused;
        }
        // Prepare an inactive, indexed session under the account lock, but never retain the
        // transaction connection while synchronous auditing opens its independent transaction.
        var prepared = new java.util.concurrent.atomic.AtomicReference<SessionCredentials>();
        try {
            var credentials = transaction.execute(status -> {
                var current = accounts.lock(valid.id());
                if (!current.enabled() || current.deleted()) throw new ApiException(IamErrorCode.INVALID_CREDENTIALS);
                String replacement = current.passwordHash().equals(valid.passwordHash())
                        ? upgraded : passwords.verifyAndUpgrade(password, current.passwordHash());
                if (replacement == null) throw new ApiException(IamErrorCode.INVALID_CREDENTIALS);
                if (!replacement.equals(current.passwordHash())) writes.upgradePassword(valid.id(), current.passwordHash(), replacement);
                var issued = sessions.create(valid.id(), valid.username(), policy.policy(), current.version());
                prepared.set(issued);
                return issued;
            });
            events.publishEvent(new LoginAudit(valid.id(), valid.username(), credentials.sessionId(), "LOGIN", true, "SUCCESS", origin, Instant.now()));
            return transaction.execute(status -> {
                var current = accounts.lock(valid.id());
                // Disable may have revoked the inactive session while auditing. Activation
                // only changes an existing session; it can never recreate a revoked one.
                if (!current.enabled() || current.deleted()) throw new ApiException(IamErrorCode.INVALID_CREDENTIALS);
                sessions.activate(credentials.sessionId());
                return credentials;
            });
        } catch (RuntimeException failed) {
            var credentials = prepared.get();
            if (credentials != null) {
                try { sessions.revoke(credentials.sessionId(), credentials.token()); }
                catch (RuntimeException cleanupFailure) {
                    org.slf4j.LoggerFactory.getLogger(PasswordLogin.class).error("Pending login cleanup unavailable; revoked generation remains unusable");
                }
            }
            if (failed instanceof ApiException api
                    && (api.errorCode() == IamErrorCode.INVALID_CREDENTIALS || api.errorCode() == IamErrorCode.SESSION_INVALID)) {
                failure(valid.id(), username, origin, "INVALID_CREDENTIALS");
                throw new ApiException(IamErrorCode.INVALID_CREDENTIALS);
            }
            if (failed instanceof ApiException api && api.errorCode() == IamErrorCode.PASSWORD_CAPACITY) throw api;
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
