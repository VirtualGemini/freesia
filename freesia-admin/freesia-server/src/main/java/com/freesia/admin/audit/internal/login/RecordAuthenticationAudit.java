package com.freesia.admin.audit.internal.login;

import com.freesia.common.audit.LoginAudit;
import com.freesia.common.audit.OperationAudit;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class RecordAuthenticationAudit {
    private final AuditWrites writes;
    private final TransactionTemplate independent;
    public RecordAuthenticationAudit(AuditWrites writes, PlatformTransactionManager transactions) {
        this.writes = writes;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void login(LoginAudit event) { writes.insertLogin(event); }

    @EventListener
    public void operation(OperationAudit event) {
        if (event.success()) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Successful operation audit requires the business transaction");
            }
            writes.insertOperation(event);
        } else {
            independent.executeWithoutResult(status -> writes.insertOperation(event));
        }
    }
}
