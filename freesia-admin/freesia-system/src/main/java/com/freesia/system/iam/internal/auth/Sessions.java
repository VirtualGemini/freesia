package com.freesia.system.iam.internal.auth;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.ApiException;
import com.freesia.common.audit.ClientOrigin;
import com.freesia.common.audit.LoginAudit;
import com.freesia.framework.security.AuthenticatedSessionSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable session state is separate from short-lived Sa-Token access mappings. */
@Component
public class Sessions implements AuthenticatedSessionSource {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "freesia:iam:";
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            if redis.call('exists',KEYS[1])==1 then return 0 end
            redis.call('hset',KEYS[1],'account',ARGV[1],'access',ARGV[2],'refresh',ARGV[3],
                       'accessExpiry',ARGV[4],'deadline',ARGV[5],'active','0','username',ARGV[7])
            redis.call('expireat',KEYS[1],ARGV[5])
            redis.call('set',KEYS[2],ARGV[6],'EXAT',ARGV[5])
            redis.call('set',KEYS[3],ARGV[6],'EXAT',ARGV[5])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ACTIVATE = new DefaultRedisScript<>("""
            if redis.call('exists',KEYS[1])==0 then return 0 end
            redis.call('hset',KEYS[1],'active','1') return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            local access=redis.call('hget',KEYS[1],'access')
            local refresh=redis.call('hget',KEYS[1],'refresh')
            redis.call('del',KEYS[1])
            if access then redis.call('del',ARGV[1]..'access:'..access) end
            if refresh then redis.call('del',ARGV[1]..'refresh:'..refresh) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> LOGOUT = new DefaultRedisScript<>("""
            if redis.call('exists',KEYS[2])==1 then return 1 end
            if redis.call('hget',KEYS[1],'active')~='1' then return 0 end
            local access=redis.call('hget',KEYS[1],'access')
            local refresh=redis.call('hget',KEYS[1],'refresh')
            redis.call('hset',KEYS[2],'account',redis.call('hget',KEYS[1],'account'),
                'username',redis.call('hget',KEYS[1],'username'),'sid',ARGV[2],
                'ip',ARGV[3],'trace',ARGV[4],'agent',ARGV[5],'country',ARGV[6],
                'province',ARGV[7],'city',ARGV[8],'isp',ARGV[9],'countryCode',ARGV[10],
                'occurred',ARGV[11])
            redis.call('expire',KEYS[2],86400)
            redis.call('del',KEYS[1],ARGV[1]..'access:'..access,ARGV[1]..'refresh:'..refresh)
            return 1
            """, Long.class);
    private final StringRedisTemplate valkey;
    private final AccountQueries accounts;
    public Sessions(StringRedisTemplate valkey, AccountQueries accounts) {
        this.valkey = valkey;
        this.accounts = accounts;
    }

    public SessionCredentials create(UUID accountId, String username, AccountQueries.Policy policy) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String sid = random();
        String access = random();
        String refresh = random();
        var result = new SessionCredentials(access, refresh, sid, now.plusSeconds(policy.accessSeconds()), now.plusSeconds(policy.refreshSeconds()));
        try {
            StpUtil.getStpLogic().createLoginSession(accountId.toString(),
                    new SaLoginParameter().setToken(access).setIsShare(false).setIsConcurrent(true)
                            .setTimeout(policy.accessSeconds()).setActiveTimeout(-1).setMaxLoginCount(-1));
            Long created = valkey.execute(CREATE, List.of(key(sid), PREFIX + "access:" + digest(access), PREFIX + "refresh:" + digest(refresh)),
                    accountId.toString(), digest(access), digest(refresh), Long.toString(result.accessExpiresAt().getEpochSecond()),
                    Long.toString(result.sessionExpiresAt().getEpochSecond()), sid, username);
            if (!Long.valueOf(1).equals(created)) throw new IllegalStateException("Session could not be created");
            return result;
        } catch (RuntimeException failure) {
            cleanup(access);
            throw failure;
        }
    }

    public void activate(String sessionId) {
        if (!Long.valueOf(1).equals(valkey.execute(ACTIVATE, List.of(key(sessionId))))) {
            throw new ApiException(IamErrorCode.SESSION_INVALID);
        }
    }

    public record Session(UUID accountId, String sessionId, Instant deadline) {}
    public Session current(String token) {
        return current(token, false);
    }
    public Session current(String token, boolean allowExpiredAccess) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String sid = valkey.opsForValue().get(PREFIX + "access:" + digest(token));
        if (sid == null) {
            if (allowExpiredAccess) {
                Map<Object,Object> ended = valkey.opsForHash().entries(logoutKey(token));
                if (!ended.isEmpty()) return new Session(UUID.fromString((String)ended.get("account")),
                        (String)ended.get("sid"), Instant.EPOCH);
            }
            throw invalid();
        }
        Map<Object, Object> state = valkey.opsForHash().entries(key(sid));
        if (!"1".equals(state.get("active")) || !digest(token).equals(state.get("access"))) throw invalid();
        long deadline = Long.parseLong((String) state.get("deadline"));
        if (Instant.now().getEpochSecond() >= deadline) throw invalid();
        UUID id = UUID.fromString((String) state.get("account"));
        if (!accounts.active(id)) {
            revoke(sid, token);
            throw invalid();
        }
        boolean expired = Instant.now().getEpochSecond() >= Long.parseLong((String) state.get("accessExpiry"));
        if (expired && !allowExpiredAccess) {
            throw new ApiException(IamErrorCode.ACCESS_EXPIRED);
        }
        if (!expired && !id.toString().equals(String.valueOf(StpUtil.getStpLogic().getLoginIdByToken(token)))) throw invalid();
        return new Session(id, sid, Instant.ofEpochSecond(deadline));
    }
    public UUID authenticate(String token) { return current(token).accountId(); }
    @Override public UUID authenticate(String token, boolean allowExpiredAccess) { return current(token, allowExpiredAccess).accountId(); }

    public void revoke(String sid, String access) {
        valkey.execute(REVOKE, List.of(key(sid)), PREFIX);
        cleanup(access);
    }
    /** Only a revocation endpoint can read this short-lived retry context; it grants no business access. */
    public LoginAudit beginLogout(String access, ClientOrigin origin) {
        Session session = current(access, true);
        var location=origin.location();
        Long result = valkey.execute(LOGOUT,List.of(key(session.sessionId()),logoutKey(access)),PREFIX,session.sessionId(),
                origin.clientIp(),text(origin.traceId()),text(origin.userAgent()),text(location.countryName()),
                text(location.provinceName()),text(location.cityName()),text(location.isp()),text(location.countryCode()),Instant.now().toString());
        if (!Long.valueOf(1).equals(result)) throw invalid();
        cleanup(access);
        Map<Object,Object> ended=valkey.opsForHash().entries(logoutKey(access));
        return new LoginAudit(UUID.fromString((String)ended.get("account")),(String)ended.get("username"),
                (String)ended.get("sid"),"LOGOUT",true,"SUCCESS",
                new ClientOrigin((String)ended.get("ip"),empty(ended.get("trace")),empty(ended.get("agent")),
                        new ClientOrigin.Location(empty(ended.get("country")),empty(ended.get("province")),
                                empty(ended.get("city")),empty(ended.get("isp")),empty(ended.get("countryCode")))),
                Instant.parse((String)ended.get("occurred")));
    }
    private static String text(String value) { return value==null ? "" : value; }
    private static String empty(Object value) { return value==null || value.toString().isEmpty() ? null : value.toString(); }
    private static String logoutKey(String access) { return PREFIX+"logout:"+digest(access); }
    private static void cleanup(String access) {
        try { StpUtil.logoutByTokenValue(access); }
        catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(Sessions.class).warn("Access cleanup unavailable; IAM session gate remains authoritative");
        }
    }
    private static String key(String sid) { return PREFIX + "session:" + sid; }
    private static ApiException invalid() { return new ApiException(IamErrorCode.SESSION_INVALID); }
    public static String digest(String secret) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
