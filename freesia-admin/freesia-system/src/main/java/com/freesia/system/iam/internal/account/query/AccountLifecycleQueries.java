package com.freesia.system.iam.internal.account.query;

import com.freesia.common.error.ApiException;
import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.account.dto.AccountLifecycleTarget;
import com.freesia.system.iam.internal.authorization.query.AuthorizationQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class AccountLifecycleQueries {
    private final JdbcClient jdbc;
    private final AuthorizationQueries authorization;
    private final AccountQueries accounts;
    public AccountLifecycleQueries(JdbcClient jdbc, AuthorizationQueries authorization, AccountQueries accounts) {
        this.jdbc = jdbc; this.authorization = authorization; this.accounts = accounts;
    }

    /** Caller holds the management lock; authorize every target before disclosing any names. */
    public List<AccountLifecycleTarget> lock(UUID actor, String permission, List<UUID> ids) {
        for (UUID id : ids) {
            var organization = jdbc.sql("SELECT organization_id FROM freesia.sys_account WHERE id=:id AND NOT deleted")
                    .param("id", id).query((rs, row) -> new Scope(rs.getObject(1, UUID.class))).optional()
                    .orElseThrow(() -> new ApiException(com.freesia.common.error.CommonErrorCode.NOT_FOUND));
            if (!authorization.rights(actor).range(permission).allows(actor, id, organization.id())) {
                throw new ApiException(com.freesia.common.error.CommonErrorCode.FORBIDDEN);
            }
            if (jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM freesia.sys_bootstrap WHERE account_id=:id)
                    OR EXISTS(SELECT 1 FROM freesia.sys_account_role ar JOIN freesia.sys_role r ON r.id=ar.role_id
                              WHERE ar.account_id=:id AND r.role_code='R_SUPER')
                    """).param("id", id).query(Boolean.class).single()) throw new ApiException(IamErrorCode.SUPER_ACCOUNT_PROTECTED);
        }
        var targets = new ArrayList<AccountLifecycleTarget>();
        for (UUID id : ids) {
            var state = accounts.lock(id);
            var roles = jdbc.sql("SELECT role_id FROM freesia.sys_account_role WHERE account_id=:id ORDER BY role_id")
                    .param("id", id).query(UUID.class).list();
            targets.add(jdbc.sql("SELECT username,updated_at,abnormal FROM freesia.sys_account WHERE id=:id")
                    .param("id", id).query((rs, row) -> new AccountLifecycleTarget(id, rs.getString(1),
                            state.enabled(), rs.getBoolean(3), state.version(), rs.getTimestamp(2).toInstant(), roles)).single());
        }
        return List.copyOf(targets);
    }
    private record Scope(UUID id) {}
}
