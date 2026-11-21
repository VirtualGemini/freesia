package com.freesia.admin.iam.internal.bootstrap;

import com.freesia.admin.iam.api.IamErrorCode;
import com.freesia.admin.iam.internal.account.AccountRow;
import com.freesia.admin.iam.internal.account.AccountWrites;
import com.freesia.admin.iam.internal.password.Passwords;
import com.freesia.common.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class InitializeAdministrator {
    private final JdbcClient jdbc;
    private final AccountWrites writes;
    private final Passwords passwords;

    public InitializeAdministrator(JdbcClient jdbc, AccountWrites writes, Passwords passwords) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.passwords = passwords;
    }

    @Transactional
    public UUID initialize(String username, char[] password) {
        if (username == null || !username.matches("[\\p{L}\\p{N}_.@-]{3,50}") || password.length < 12 || password.length > 256
                || java.nio.CharBuffer.wrap(password).chars().allMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Username must be 3-50 identity characters; password must be 12-256 characters");
        }
        boolean complete = jdbc.sql("SELECT completed_at IS NOT NULL FROM freesia.sys_bootstrap WHERE purpose='first-super' FOR UPDATE")
                .query(Boolean.class).single();
        if (complete || jdbc.sql("SELECT EXISTS(SELECT 1 FROM freesia.sys_account)").query(Boolean.class).single()) {
            throw new ApiException(IamErrorCode.BOOTSTRAP_COMPLETED);
        }
        var account = new AccountRow();
        account.setUsername(username);
        account.setPasswordHash(passwords.hash(java.nio.CharBuffer.wrap(password)));
        writes.insertSelective(account);
        writes.createProfile(account.getId());
        if (writes.bindFirstSuper(account.getId()) != 1 || writes.completeBootstrap(account.getId()) != 1) {
            throw new IllegalStateException("Administrator initialization could not complete");
        }
        return account.getId();
    }
}
