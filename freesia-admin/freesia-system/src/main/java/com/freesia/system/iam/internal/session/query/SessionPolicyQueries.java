package com.freesia.system.iam.internal.session.query;

import com.freesia.system.iam.internal.session.dto.SessionPolicy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SessionPolicyQueries {
    private final JdbcClient jdbc;

    public SessionPolicyQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public SessionPolicy policy() {
        return jdbc.sql("SELECT access_seconds, refresh_seconds FROM freesia.sys_access_control WHERE scope_key='global'")
                .query((rs, row) -> new SessionPolicy(rs.getInt(1), rs.getInt(2))).single();
    }
}
