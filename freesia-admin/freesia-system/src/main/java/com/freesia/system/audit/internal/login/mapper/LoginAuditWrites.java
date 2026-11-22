package com.freesia.system.audit.internal.login.mapper;

import com.freesia.common.audit.LoginAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginAuditWrites {
    @Insert("""
            INSERT INTO freesia.sys_login_log(account_id,username,session_id,event_type,result,outcome_code,
                client_ip,trace_id,user_agent,country_name,province_name,city_name,isp,country_code,event_time)
            VALUES(#{accountId},#{username},#{sessionId},#{eventType},#{success}::boolean::int,#{outcomeCode},
                #{origin.clientIp}::inet,#{origin.traceId},#{origin.userAgent},#{origin.location.countryName},
                #{origin.location.provinceName},#{origin.location.cityName},#{origin.location.isp},#{origin.location.countryCode},#{occurredAt})
            ON CONFLICT DO NOTHING
            """)
    int insertLogin(LoginAudit event);
}
