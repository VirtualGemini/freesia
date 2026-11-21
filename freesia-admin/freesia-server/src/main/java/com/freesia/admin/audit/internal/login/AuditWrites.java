package com.freesia.admin.audit.internal.login;

import com.freesia.common.audit.LoginAudit;
import com.freesia.common.audit.OperationAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditWrites {
    @Insert("""
            INSERT INTO freesia.sys_login_log(account_id,username,session_id,event_type,result,outcome_code,
                client_ip,trace_id,user_agent,country_name,province_name,city_name,isp,country_code,event_time)
            VALUES(#{accountId},#{username},#{sessionId},#{eventType},#{success}::boolean::int,#{outcomeCode},
                #{origin.clientIp}::inet,#{origin.traceId},#{origin.userAgent},#{origin.location.countryName},
                #{origin.location.provinceName},#{origin.location.cityName},#{origin.location.isp},#{origin.location.countryCode},#{occurredAt})
            ON CONFLICT DO NOTHING
            """)
    int insertLogin(LoginAudit event);

    @Insert("""
            INSERT INTO freesia.sys_operation_log(actor_id,action,target_type,target_id,result,outcome_code,trace_id,occurred_at)
            VALUES(#{actorId},#{action},#{targetType},#{targetId},#{success}::boolean::int,#{outcomeCode},#{traceId},#{occurredAt})
            """)
    int insertOperation(OperationAudit event);
}
