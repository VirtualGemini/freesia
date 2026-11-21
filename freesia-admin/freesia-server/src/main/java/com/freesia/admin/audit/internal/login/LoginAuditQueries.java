package com.freesia.admin.audit.internal.login;

import com.freesia.common.result.PageResult;
import com.freesia.common.result.R;
import com.freesia.framework.security.RequirePermission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** The minimal authorized observation interface; full audit filtering/management is a later slice. */
@RestController
class LoginAuditQueries {
    private final JdbcClient jdbc;
    LoginAuditQueries(JdbcClient jdbc) { this.jdbc = jdbc; }
    record Record(UUID id, UUID accountId, String username, String sessionId, String eventType,
                  String loginMethod, int result, String outcomeCode, String clientIp, String traceId,
                  String countryName, String provinceName, String cityName, String isp, String countryCode,
                  Instant eventTime, Instant createTime) {}

    @RequirePermission("system:log:login:query")
    @GetMapping("/api/log/login/list")
    R<PageResult<Record>> list(@RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        long page = Math.max(1, Math.min(current, 10000));
        long limit = Math.max(1, Math.min(size, 100));
        var records = jdbc.sql("""
                SELECT * FROM freesia.sys_login_log ORDER BY event_time DESC,id DESC LIMIT :size OFFSET :offset
                """).param("size", limit).param("offset", (page - 1) * limit).query((rs,row) -> new Record(
                        rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("username"),
                        rs.getString("session_id"), rs.getString("event_type"), rs.getString("login_method"), rs.getInt("result"),
                        rs.getString("outcome_code"), rs.getString("client_ip"), rs.getString("trace_id"),
                        rs.getString("country_name"), rs.getString("province_name"), rs.getString("city_name"),
                        rs.getString("isp"), rs.getString("country_code"), rs.getTimestamp("event_time").toInstant(),
                        rs.getTimestamp("created_at").toInstant())).list();
        return R.ok(new PageResult<>(records, page, limit, jdbc.sql("SELECT count(*) FROM freesia.sys_login_log").query(Long.class).single()));
    }
}
