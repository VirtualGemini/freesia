package com.freesia.system.audit.internal.login.query;

import com.freesia.common.result.PageResult;
import com.freesia.system.audit.internal.login.dto.LoginAuditRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LoginAuditQueries {
    private final JdbcClient jdbc;
    public LoginAuditQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public PageResult<LoginAuditRecord> list(long current, long size) {
        long page = Math.max(1, Math.min(current, 10000));
        long limit = Math.max(1, Math.min(size, 100));
        var records = jdbc.sql("""
                SELECT * FROM freesia.sys_login_log ORDER BY event_time DESC,id DESC LIMIT :size OFFSET :offset
                """).param("size", limit).param("offset", (page - 1) * limit).query((rs,row) -> new LoginAuditRecord(
                        rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("username"),
                        rs.getString("session_id"), rs.getString("event_type"), rs.getString("login_method"), rs.getInt("result"),
                        rs.getString("outcome_code"), rs.getString("client_ip"), rs.getString("trace_id"),
                        rs.getString("country_name"), rs.getString("province_name"), rs.getString("city_name"),
                        rs.getString("isp"), rs.getString("country_code"), rs.getTimestamp("event_time").toInstant(),
                        rs.getTimestamp("created_at").toInstant())).list();
        return new PageResult<>(records, page, limit, jdbc.sql("SELECT count(*) FROM freesia.sys_login_log").query(Long.class).single());
    }
}
