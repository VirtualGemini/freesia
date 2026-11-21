package com.freesia.system.iam.internal.auth;

import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** Server time and atomic per-pair transitions; unrelated IPs and established sessions are untouched. */
@Component
public class PasswordFailures {
    private static final String PREFIX = "freesia:iam:password-failures:";
    private static final DefaultRedisScript<Long> TRANSITION = new DefaultRedisScript<>("""
            local now=tonumber(redis.call('TIME')[1])
            local paused=tonumber(redis.call('hget',KEYS[1],'paused') or '0')
            if paused>now then return 0 end
            if paused>0 then redis.call('del',KEYS[1]) end
            if ARGV[1]=='check' then return 1 end
            if ARGV[1]=='success' then redis.call('del',KEYS[1]) return 1 end
            local started=tonumber(redis.call('hget',KEYS[1],'started') or '0')
            if started==0 or now-started>=tonumber(ARGV[3]) then
                redis.call('del',KEYS[1])
                redis.call('hset',KEYS[1],'started',now,'count',0)
            end
            local count=redis.call('hincrby',KEYS[1],'count',1)
            if count>=tonumber(ARGV[2]) then
                redis.call('hset',KEYS[1],'paused',now+tonumber(ARGV[4]))
                redis.call('expire',KEYS[1],ARGV[4])
            else redis.call('expire',KEYS[1],ARGV[3]) end
            return 1
            """, Long.class);
    private final StringRedisTemplate valkey;
    private final JdbcClient jdbc;
    public PasswordFailures(StringRedisTemplate valkey, JdbcClient jdbc) { this.valkey = valkey; this.jdbc = jdbc; }
    record Policy(int maximum, int window, int pause) {}
    public void check(String username, String ip) { transition(username, ip, "check"); }
    public void failed(String username, String ip) { transition(username, ip, "failure"); }
    public void succeeded(String username, String ip) { transition(username, ip, "success"); }
    private void transition(String username, String ip, String action) {
        Policy policy = jdbc.sql("SELECT max_failures, window_seconds, pause_seconds FROM freesia.sys_verification_policy WHERE scene='password'")
                .query((rs,row) -> new Policy(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();
        String key = PREFIX + Sessions.digest(username + "\0" + ip);
        Long result = valkey.execute(TRANSITION, List.of(key), action,
                Integer.toString(policy.maximum()), Integer.toString(policy.window()), Integer.toString(policy.pause()));
        if (!Long.valueOf(1).equals(result)) throw new ApiException(IamErrorCode.PASSWORD_PAUSED);
    }
}
