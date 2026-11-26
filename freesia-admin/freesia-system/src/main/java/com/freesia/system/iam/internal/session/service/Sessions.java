package com.freesia.system.iam.internal.session.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.common.error.ApiException;
import com.freesia.common.audit.ClientOrigin;
import com.freesia.common.audit.LoginAudit;
import com.freesia.framework.security.AuthenticatedSessionSource;
import com.freesia.system.iam.internal.account.query.AccountQueries;
import com.freesia.system.iam.internal.session.dto.SessionCredentials;
import com.freesia.system.iam.internal.session.dto.SessionPolicy;
import com.freesia.system.iam.internal.session.query.SessionPolicyQueries;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
                       'accessExpiry',ARGV[4],'deadline',ARGV[5],'active','0','username',ARGV[7],'version',ARGV[8])
            redis.call('expireat',KEYS[1],ARGV[5])
            redis.call('set',KEYS[2],ARGV[6]..':'..ARGV[4],'EXAT',ARGV[5])
            redis.call('set',KEYS[3],ARGV[6],'EXAT',ARGV[5])
            redis.call('set',KEYS[4],ARGV[6],'EXAT',ARGV[5])
            redis.call('set',KEYS[5],ARGV[6],'EXAT',ARGV[5])
            redis.call('sadd',KEYS[6],ARGV[6])
            local remaining=redis.call('ttl',KEYS[6])
            if remaining<tonumber(ARGV[5])-tonumber(redis.call('time')[1]) then
                redis.call('expireat',KEYS[6],ARGV[5])
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ACTIVATE = new DefaultRedisScript<>("""
            if redis.call('exists',KEYS[1])==0 then return 0 end
            redis.call('hset',KEYS[1],'active','1') return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            local deadline=redis.call('hget',KEYS[1],'deadline')
            if redis.call('hget',KEYS[1],'active')~='1' or not deadline
                or tonumber(deadline)<=tonumber(redis.call('time')[1])
                or deadline~=ARGV[5] or redis.call('hget',KEYS[1],'refresh')~=ARGV[2]
                or redis.call('get',KEYS[2])~=ARGV[1] then return 0 end
            redis.call('del',KEYS[2])
            redis.call('hset',KEYS[1],'access',ARGV[3],'refresh',ARGV[4],'accessExpiry',ARGV[6])
            redis.call('set',KEYS[3],ARGV[1]..':'..ARGV[6],'EXAT',deadline)
            redis.call('set',KEYS[4],ARGV[1],'EXAT',deadline)
            redis.call('set',KEYS[5],ARGV[1],'EXAT',deadline)
            redis.call('set',KEYS[6],ARGV[1],'EXAT',deadline)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            local access=redis.call('hget',KEYS[1],'access')
            local refresh=redis.call('hget',KEYS[1],'refresh')
            local account=redis.call('hget',KEYS[1],'account')
            redis.call('del',KEYS[1])
            if account then redis.call('srem',ARGV[1]..'account-sessions:'..account,ARGV[2]) end
            if access then redis.call('del',ARGV[1]..'access:'..access) end
            if refresh then redis.call('del',ARGV[1]..'refresh:'..refresh) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE_ALL = new DefaultRedisScript<>("""
            local sessions=redis.call('smembers',KEYS[1])
            for _,sid in ipairs(sessions) do
                local key=ARGV[1]..'session:'..sid
                local access=redis.call('hget',key,'access')
                local refresh=redis.call('hget',key,'refresh')
                redis.call('del',key)
                if access then redis.call('del',ARGV[1]..'access:'..access) end
                if refresh then redis.call('del',ARGV[1]..'refresh:'..refresh) end
            end
            redis.call('del',KEYS[1])
            return #sessions
            """, Long.class);
    private static final DefaultRedisScript<String> LOGOUT = new DefaultRedisScript<>("""
            local accessSid=redis.call('get',KEYS[1]) or redis.call('get',KEYS[2])
            local refreshSid=redis.call('get',KEYS[3]) or redis.call('get',KEYS[4])
            local sid=ARGV[2]
            if sid=='' then sid=accessSid or refreshSid end
            if not sid or (sid~=accessSid and sid~=refreshSid) then return '' end
            local result=sid..':'..(refreshSid==sid and '1' or '0')..':'..(accessSid==sid and '1' or '0')
            local ended=ARGV[1]..'logout:session:'..sid
            if redis.call('exists',ended)==1 then return result end
            local session=ARGV[1]..'session:'..sid
            local deadline=redis.call('hget',session,'deadline')
            if redis.call('hget',session,'active')~='1' or not deadline
                or tonumber(deadline)<=tonumber(redis.call('time')[1]) then return '' end
            local access=redis.call('hget',session,'access')
            local refresh=redis.call('hget',session,'refresh')
            redis.call('hset',ended,'account',redis.call('hget',session,'account'),
                'username',redis.call('hget',session,'username'),'sid',sid,
                'ip',ARGV[3],'trace',ARGV[4],'agent',ARGV[5],'country',ARGV[6],
                'province',ARGV[7],'city',ARGV[8],'isp',ARGV[9],'countryCode',ARGV[10],
                'occurred',ARGV[11])
            redis.call('expire',ended,86400)
            if accessSid==sid then redis.call('set',KEYS[2],sid,'EX',86400) end
            if refreshSid==sid then redis.call('set',KEYS[4],sid,'EX',86400) end
            redis.call('set',ARGV[1]..'logout:retry:'..access,sid,'EX',86400)
            redis.call('set',ARGV[1]..'logout:retry:'..refresh,sid,'EX',86400)
            redis.call('del',session,ARGV[1]..'access:'..access,ARGV[1]..'refresh:'..refresh)
            redis.call('srem',ARGV[1]..'account-sessions:'..redis.call('hget',ended,'account'),sid)
            return result
            """, String.class);
    private final StringRedisTemplate valkey;
    private final AccountQueries accounts;
    private final SessionPolicyQueries policy;
    private final TransactionTemplate transaction;
    public Sessions(StringRedisTemplate valkey, AccountQueries accounts, SessionPolicyQueries policy,
                    PlatformTransactionManager transactions) {
        this.valkey = valkey;
        this.accounts = accounts;
        this.policy = policy;
        transaction = new TransactionTemplate(transactions);
    }

    public SessionCredentials create(UUID accountId, String username, SessionPolicy policy, long version) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String sid = random();
        String access = random();
        String refresh = random();
        var result = new SessionCredentials(access, refresh, sid, now.plusSeconds(policy.accessSeconds()), now.plusSeconds(policy.refreshSeconds()));
        try {
            StpUtil.getStpLogic().createLoginSession(accountId.toString(),
                    new SaLoginParameter().setToken(access).setIsShare(false).setIsConcurrent(true)
                            .setTimeout(policy.accessSeconds()).setActiveTimeout(-1).setMaxLoginCount(-1));
            Long created = valkey.execute(CREATE, List.of(key(sid), PREFIX + "access:" + digest(access), PREFIX + "refresh:" + digest(refresh),
                            logoutLocator(access), logoutLocator(refresh), PREFIX + "account-sessions:" + accountId),
                    accountId.toString(), digest(access), digest(refresh), Long.toString(result.accessExpiresAt().getEpochSecond()),
                    Long.toString(result.sessionExpiresAt().getEpochSecond()), sid, username, Long.toString(version));
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

    /** Prepare Sa-Token first; only the atomic IAM compare-and-swap can authorize the new Access. */
    public SessionCredentials refresh(String refresh, String expectedSession) {
        if (!validToken(refresh)) throw invalid();
        String oldDigest = digest(refresh);
        String sid = valkey.opsForValue().get(PREFIX + "refresh:" + oldDigest);
        if (sid == null || expectedSession != null && !sid.equals(expectedSession)) throw invalid();
        Map<Object, Object> state = valkey.opsForHash().entries(key(sid));
        if (!state.containsKey("account")) throw invalid();
        UUID account = UUID.fromString((String) state.get("account"));
        return transaction.execute(status -> {
            var current = accounts.lock(account);
            if (!current.enabled() || current.deleted() || current.version() != version(state)) {
                valkey.execute(REVOKE, List.of(key(sid)), PREFIX, sid);
                throw invalid();
            }
            return rotate(refresh, oldDigest, sid, state, account);
        });
    }

    private SessionCredentials rotate(String refresh, String oldDigest, String sid, Map<Object, Object> state, UUID account) {
        if (!"1".equals(state.get("active")) || !oldDigest.equals(state.get("refresh"))) throw invalid();
        long deadline = Long.parseLong((String) state.get("deadline"));
        long now = Instant.now().getEpochSecond();
        if (now >= deadline) throw invalid();
        long expires = Math.min(deadline, now + policy.policy().accessSeconds());
        String access = random();
        String nextRefresh = random();
        try {
            StpUtil.getStpLogic().createLoginSession(account.toString(),
                    new SaLoginParameter().setToken(access).setIsShare(false).setIsConcurrent(true)
                            .setTimeout(expires - now).setActiveTimeout(-1).setMaxLoginCount(-1));
            Long rotated = valkey.execute(ROTATE, List.of(key(sid), PREFIX + "refresh:" + oldDigest,
                            PREFIX + "access:" + digest(access), PREFIX + "refresh:" + digest(nextRefresh),
                            logoutLocator(access), logoutLocator(nextRefresh)),
                    sid, oldDigest, digest(access), digest(nextRefresh), Long.toString(deadline), Long.toString(expires), PREFIX);
            if (!Long.valueOf(1).equals(rotated)) throw invalid();
            return new SessionCredentials(access, nextRefresh, sid, Instant.ofEpochSecond(expires), Instant.ofEpochSecond(deadline));
        } catch (RuntimeException failure) {
            // A prepared token cannot pass the IAM gate, even when Sa-Token cleanup is unavailable.
            cleanup(access);
            throw failure;
        }
    }

    public record Session(UUID accountId, String sessionId, Instant deadline) {}
    public Session current(String token) {
        return current(token, false);
    }
    public Session current(String token, boolean allowExpiredAccess) {
        if (!validToken(token)) throw invalid();
        String mapping = valkey.opsForValue().get(PREFIX + "access:" + digest(token));
        if (mapping == null) throw invalid();
        String[] fields = mapping.split(":");
        if (fields.length != 2) throw invalid();
        String sid = fields[0];
        Map<Object, Object> state = valkey.opsForHash().entries(key(sid));
        if (!"1".equals(state.get("active"))) throw invalid();
        long deadline = Long.parseLong((String) state.get("deadline"));
        if (Instant.now().getEpochSecond() >= deadline) throw invalid();
        UUID id = UUID.fromString((String) state.get("account"));
        if (accounts.activeVersion(id) != version(state)) {
            revoke(sid, token);
            throw invalid();
        }
        boolean expired = Instant.now().getEpochSecond() >= Long.parseLong(fields[1]);
        if (expired && !allowExpiredAccess) {
            throw new ApiException(IamErrorCode.ACCESS_EXPIRED);
        }
        if (!expired && !id.toString().equals(String.valueOf(StpUtil.getStpLogic().getLoginIdByToken(token)))) throw invalid();
        return new Session(id, sid, Instant.ofEpochSecond(deadline));
    }
    public UUID authenticate(String token) { return current(token).accountId(); }
    @Override public UUID authenticate(String token, boolean allowExpiredAccess) { return current(token, allowExpiredAccess).accountId(); }

    public void revoke(String sid, String access) {
        valkey.execute(REVOKE, List.of(key(sid)), PREFIX, sid);
        cleanup(access);
    }

    /** Caller holds the account row lock; a failure is never reported as completed revocation. */
    public void revokeAll(UUID account) {
        if (valkey.execute(REVOKE_ALL, List.of(PREFIX + "account-sessions:" + account), PREFIX) == null) {
            throw new ApiException(com.freesia.common.error.CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static long version(Map<Object, Object> state) {
        // Pre-lifecycle sessions have no revocation index and must not bypass conservative revocation.
        Object value = state.get("version");
        if (!(value instanceof String number)) throw invalid();
        try { return Long.parseLong(number); }
        catch (NumberFormatException malformed) { throw invalid(); }
    }
    /**
     * Digest-only locators survive rotation until the absolute deadline, solely to revoke a session.
     * The atomic script always revokes its latest generation, never a prepared or stale snapshot.
     * Audit retry snapshots last at most 24h and cannot restore any credential.
     */
    public record Logout(LoginAudit audit, boolean clearCookie) {}
    public Logout beginLogout(String access, String refresh, String expectedSession, ClientOrigin origin) {
        if (!validToken(access) && !validToken(refresh)) throw invalid();
        if (expectedSession != null && !validToken(expectedSession)) throw invalid();
        var location=origin.location();
        String result = valkey.execute(LOGOUT, List.of(logoutLocator(access), logoutRetry(access), logoutLocator(refresh), logoutRetry(refresh)),
                PREFIX, text(expectedSession),
                origin.clientIp(),text(origin.traceId()),text(origin.userAgent()),text(location.countryName()),
                text(location.provinceName()),text(location.cityName()),text(location.isp()),text(location.countryCode()),Instant.now().toString());
        if (result == null || result.isEmpty()) throw invalid();
        String[] selection = result.split(":");
        String sid = selection[0];
        if ("1".equals(selection[2])) cleanup(access);
        Map<Object,Object> ended=valkey.opsForHash().entries(PREFIX + "logout:session:" + sid);
        if (ended.isEmpty()) throw invalid();
        var audit = new LoginAudit(UUID.fromString((String)ended.get("account")),(String)ended.get("username"),
                (String)ended.get("sid"),"LOGOUT",true,"SUCCESS",
                new ClientOrigin((String)ended.get("ip"),empty(ended.get("trace")),empty(ended.get("agent")),
                        new ClientOrigin.Location(empty(ended.get("country")),empty(ended.get("province")),
                                empty(ended.get("city")),empty(ended.get("isp")),empty(ended.get("countryCode")))),
                Instant.parse((String)ended.get("occurred")));
        return new Logout(audit, "1".equals(selection[1]));
    }
    private static String text(String value) { return value==null ? "" : value; }
    private static String empty(Object value) { return value==null || value.toString().isEmpty() ? null : value.toString(); }
    private static String logoutLocator(String credential) {
        return PREFIX + "logout:credential:" + (validToken(credential) ? digest(credential) : "absent");
    }
    private static String logoutRetry(String credential) {
        return PREFIX + "logout:retry:" + (validToken(credential) ? digest(credential) : "absent");
    }
    private static void cleanup(String access) {
        try { StpUtil.logoutByTokenValue(access); }
        catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(Sessions.class).warn("Access cleanup unavailable; IAM session gate remains authoritative");
        }
    }
    private static String key(String sid) { return PREFIX + "session:" + sid; }
    private static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
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
