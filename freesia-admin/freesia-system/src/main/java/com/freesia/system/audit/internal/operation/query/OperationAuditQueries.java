package com.freesia.system.audit.internal.operation.query;

import com.freesia.system.audit.internal.operation.dto.OperationObservation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

/** Narrow write-observation seam; full log filtering and organization-aware browsing belong to 13. */
@Component
public class OperationAuditQueries {
    private final JdbcClient jdbc;
    public OperationAuditQueries(JdbcClient jdbc) { this.jdbc=jdbc; }
    public List<OperationObservation> observations(UUID actor,String target) {
        return jdbc.sql("""
                SELECT action,target_type,target_id,result,outcome_code,trace_id,occurred_at
                FROM freesia.sys_operation_log WHERE actor_id=:actor AND target_id=:target
                ORDER BY occurred_at DESC,id DESC LIMIT 100
                """).param("actor",actor).param("target",target).query((rs,n)->{
                    return new OperationObservation(rs.getString("action"),rs.getString("target_type"),rs.getString("target_id"),
                            rs.getInt("result")==1,rs.getString("outcome_code"),rs.getString("trace_id"),rs.getTimestamp("occurred_at").toInstant());
                }).list();
    }
}
