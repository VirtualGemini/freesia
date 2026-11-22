package com.freesia.system.audit.internal.operation.service;

import com.freesia.common.audit.OperationAudit;
import com.freesia.system.audit.internal.operation.mapper.OperationAuditWrites;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RecordOperationAudit {
    private final OperationAuditWrites writes;
    private final TransactionTemplate independent;

    public RecordOperationAudit(OperationAuditWrites writes, PlatformTransactionManager transactions) {
        this.writes = writes;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

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
