package com.freesia.system.iam.internal.account.service;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.account.mapper.AccountWrites;
import com.freesia.system.iam.internal.account.dto.AccountDeletionPreview;
import com.freesia.system.iam.internal.account.dto.AccountLifecycleTarget;
import com.freesia.system.iam.internal.account.dto.AccountStatusPreview;
import com.freesia.system.iam.internal.account.query.AccountLifecycleQueries;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.session.service.Sessions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class AccountLifecycle {
    private final ManagementActions actions;
    private final AccountLifecycleQueries queries;
    private final AccountWrites writes;
    private final Sessions sessions;
    private final StringRedisTemplate valkey;
    private final JsonMapper json;
    private static final String DELETE = "system:account:delete";
    private static final String PREFIX = "freesia:iam:account-deletion:";
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    public AccountLifecycle(ManagementActions actions, AccountLifecycleQueries queries,
                            AccountWrites writes, Sessions sessions, StringRedisTemplate valkey, JsonMapper json) {
        this.actions = actions; this.queries = queries;
        this.writes = writes; this.sessions = sessions; this.valkey = valkey; this.json = json;
    }

    public boolean status(UUID actor, List<UUID> requested, String status, String trace) {
        if (!List.of("1", "2", "3").contains(status)) throw new ApiException(CommonErrorCode.BAD_REQUEST);
        boolean enabled = !status.equals("2");
        var ids = normalize(requested);
        String permission = enabled ? "system:account:enable" : "system:account:disable";
        return actions.runBatch(actor, permission, "account", ids.stream().map(UUID::toString).toList(), trace, () -> {
            var targets = queries.lock(actor, permission, ids);
            for (var target : targets) {
                // Revocation is conservative: a later DB/audit failure cannot resurrect sessions.
                if (!enabled || !target.enabled()) sessions.revokeAll(target.accountId());
                writes.changeStatus(target.accountId(), enabled, status.equals("3"));
            }
            return true;
        });
    }

    public AccountStatusPreview statusPreview(UUID actor, List<UUID> requested, boolean enabled) {
        var ids = normalize(requested);
        String permission = enabled ? "system:account:enable" : "system:account:disable";
        return actions.inspect(actor, permission, () -> new AccountStatusPreview(queries.lock(actor, permission, ids).stream()
                .map(target -> new AccountStatusPreview.Target(target.accountId(), target.username(),
                        target.enabled() ? target.abnormal() ? "3" : "1" : "2")).toList()));
    }

    public AccountDeletionPreview preview(UUID actor, List<UUID> requested) {
        var ids = normalize(requested);
        return actions.inspect(actor, DELETE, () -> {
            var targets = queries.lock(actor, DELETE, ids);
            var blocked = targets.stream().filter(AccountLifecycleTarget::enabled)
                    .map(target -> new AccountDeletionPreview.Target(target.accountId(), target.username())).toList();
            if (!blocked.isEmpty()) return new AccountDeletionPreview(null, null, List.of(), blocked);
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            valkey.opsForValue().set(PREFIX + Sessions.digest(token), digest(actor, targets), VALIDITY);
            return new AccountDeletionPreview(token, Instant.now().plus(VALIDITY), targets.stream()
                    .map(target -> new AccountDeletionPreview.Target(target.accountId(), target.username())).toList(), List.of());
        });
    }

    public boolean delete(UUID actor, List<UUID> requested, String confirmation, String trace) {
        var ids = normalize(requested);
        return actions.runBatch(actor, DELETE, "account", ids.stream().map(UUID::toString).toList(), trace, () -> {
            var targets = queries.lock(actor, DELETE, ids);
            requireDisabled(targets);
            if (confirmation == null || !confirmation.matches("[A-Za-z0-9_-]{43}")
                    || !digest(actor, targets).equals(valkey.opsForValue().get(PREFIX + Sessions.digest(confirmation)))) {
                throw new ApiException(IamErrorCode.ACCOUNT_DELETION_CHANGED);
            }
            for (UUID id : ids) {
                sessions.revokeAll(id);
                writes.clearRoles(id);
                writes.markDeleted(id);
            }
            return true;
        });
    }

    private void requireDisabled(List<AccountLifecycleTarget> targets) {
        var enabled = targets.stream().filter(AccountLifecycleTarget::enabled)
                .map(AccountLifecycleTarget::username).toList();
        if (!enabled.isEmpty()) throw new ApiException(IamErrorCode.ACCOUNT_MUST_BE_DISABLED, String.join(", ", enabled));
    }

    private String digest(UUID actor, List<AccountLifecycleTarget> targets) {
        return Sessions.digest(actor + ":" + json.writeValueAsString(targets));
    }

    static List<UUID> normalize(List<UUID> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100 || requested.stream().anyMatch(Objects::isNull)) {
            throw new ApiException(CommonErrorCode.BAD_REQUEST);
        }
        return requested.stream().distinct().sorted().toList();
    }
}
