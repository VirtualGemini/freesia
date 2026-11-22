package com.freesia.system.audit.internal.login.service;

import com.freesia.common.audit.LoginAudit;
import com.freesia.system.audit.internal.login.mapper.LoginAuditWrites;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RecordLoginAudit {
    private final LoginAuditWrites writes;
    public RecordLoginAudit(LoginAuditWrites writes) {
        this.writes = writes;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void login(LoginAudit event) { writes.insertLogin(event); }
}
