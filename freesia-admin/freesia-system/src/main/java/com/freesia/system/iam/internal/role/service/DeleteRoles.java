package com.freesia.system.iam.internal.role.service;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.authorization.service.ManagementActions;
import com.freesia.system.iam.internal.role.dto.RoleDeletionImpact;
import com.freesia.system.iam.internal.role.dto.RoleDeletionSnapshot;
import com.freesia.system.iam.internal.role.mapper.RoleWrites;
import com.freesia.system.iam.internal.role.query.RoleDeletionQueries;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class DeleteRoles {
    private static final String PERMISSION = "system:role:delete";
    private static final String PREFIX = "freesia:iam:role-deletion:";
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ManagementActions actions;
    private final RoleDeletionQueries queries;
    private final StringRedisTemplate valkey;
    private final RoleWrites writes;
    private final JsonMapper json;

    public DeleteRoles(ManagementActions actions, RoleDeletionQueries queries, StringRedisTemplate valkey,
                       RoleWrites writes, JsonMapper json) {
        this.actions = actions;
        this.queries = queries;
        this.valkey = valkey;
        this.writes = writes;
        this.json = json;
    }

    public RoleDeletionImpact preview(UUID actor, List<UUID> requested) {
        List<UUID> ids = normalize(requested);
        return actions.inspect(actor, PERMISSION, () -> {
            var snapshot = queries.snapshot(actor, ids);
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Instant expiresAt = Instant.now().plus(VALIDITY);
            valkey.opsForValue().set(PREFIX + digest(token), snapshotDigest(actor, snapshot), VALIDITY);
            return new RoleDeletionImpact(token, expiresAt, snapshot.impacts(), snapshot.records(),
                    snapshot.records().size(), snapshot.accountsComplete());
        });
    }

    public boolean delete(UUID actor, List<UUID> requested, String token, String trace) {
        List<UUID> ids = normalize(requested);
        return actions.runBatch(actor, PERMISSION, "role", ids.stream().map(UUID::toString).toList(), trace, () -> {
            var snapshot = queries.snapshot(actor, ids);
            if (token == null || !token.matches("[A-Za-z0-9_-]{43}")
                    || !snapshotDigest(actor, snapshot).equals(valkey.opsForValue().get(PREFIX + digest(token)))) {
                throw new ApiException(IamErrorCode.ROLE_DELETION_CHANGED);
            }
            for (UUID id : ids) {
                writes.clearMembers(id);
                writes.clearUse(id);
                writes.clearGrant(id);
                writes.clearOrganizations(id);
                if (writes.markDeleted(id) != 1) throw new ApiException(IamErrorCode.ROLE_DELETION_CHANGED);
            }
            return true;
        });
    }

    private String snapshotDigest(UUID actor, RoleDeletionSnapshot snapshot) {
        return digest(actor + ":" + json.writeValueAsString(snapshot));
    }

    static List<UUID> normalize(List<UUID> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100 || requested.stream().anyMatch(Objects::isNull)) {
            throw new ApiException(CommonErrorCode.BAD_REQUEST);
        }
        return requested.stream().distinct().sorted().toList();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
