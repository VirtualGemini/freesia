package com.freesia.system.audit.internal.operation.mapper;

import com.freesia.common.audit.OperationAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationAuditWrites {
    @Insert("""
            INSERT INTO freesia.sys_operation_log(actor_id,action,target_type,target_id,result,outcome_code,trace_id,occurred_at)
            VALUES(#{actorId},#{action},#{targetType},#{targetId},#{success}::boolean::int,#{outcomeCode},#{traceId},#{occurredAt})
            """)
    int insertOperation(OperationAudit event);
}
