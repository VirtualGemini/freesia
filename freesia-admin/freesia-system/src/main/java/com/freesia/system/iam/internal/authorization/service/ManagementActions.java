package com.freesia.system.iam.internal.authorization.service;

import com.freesia.common.audit.OperationAudit;
import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.internal.authorization.query.PermissionQueries;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/** One transaction orders IAM management changes with permission revocation and successful audit. */
@Component
public class ManagementActions {
    private final JdbcClient jdbc;
    private final PermissionQueries permissions;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public ManagementActions(JdbcClient jdbc, PermissionQueries permissions, ApplicationEventPublisher events,
                             PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.permissions = permissions; this.events = events;
        transaction = new TransactionTemplate(transactions);
    }

    public <T> T run(UUID actor, String permission, String type, String target, String trace, Supplier<T> work) {
        try {
            return transaction.execute(status -> {
                jdbc.sql("SELECT purpose FROM freesia.sys_bootstrap WHERE purpose='first-super' FOR UPDATE")
                        .query(String.class).single();
                if (!permissions.permissionsOf(actor).contains(permission)) throw new ApiException(CommonErrorCode.FORBIDDEN);
                T result = work.get();
                events.publishEvent(new OperationAudit(actor, permission, type,
                        target == null && result instanceof UUID ? result.toString() : target, true, "OK", trace, Instant.now()));
                return result;
            });
        } catch (RuntimeException failure) {
            RuntimeException error = failure instanceof DataIntegrityViolationException
                    ? new ApiException(IamErrorCode.DUPLICATE_RESOURCE) : failure;
            try {
                events.publishEvent(new OperationAudit(actor, permission, type, target, false,
                        error instanceof ApiException api ? String.valueOf(api.errorCode().code()) : "INTERNAL_ERROR",
                        trace, Instant.now()));
            } catch (RuntimeException unavailable) {
                LoggerFactory.getLogger(ManagementActions.class).error("Failed management audit unavailable for {}", permission);
            }
            throw error;
        }
    }
}
