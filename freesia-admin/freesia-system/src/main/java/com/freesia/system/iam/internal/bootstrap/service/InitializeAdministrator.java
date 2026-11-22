package com.freesia.system.iam.internal.bootstrap.service;

import com.freesia.system.iam.api.IamErrorCode;
import com.freesia.system.iam.internal.account.entity.AccountRow;
import com.freesia.system.iam.internal.account.mapper.AccountWrites;
import com.freesia.system.iam.internal.bootstrap.mapper.BootstrapWrites;
import com.freesia.system.iam.internal.password.service.Passwords;
import com.freesia.common.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class InitializeAdministrator {
    private final JdbcClient jdbc;
    private final AccountWrites writes;
    private final BootstrapWrites bootstrap;
    private final Passwords passwords;

    public InitializeAdministrator(JdbcClient jdbc, AccountWrites writes, BootstrapWrites bootstrap, Passwords passwords) {
        this.jdbc = jdbc;
        this.writes = writes;
        this.bootstrap = bootstrap;
        this.passwords = passwords;
    }

    @Transactional
    public UUID initialize(String username, char[] password) {
        if (username == null || !username.matches("[\\p{L}\\p{N}_.@-]{3,50}")) {
            throw new IllegalArgumentException("Username must contain 3-50 letters, numbers, or the characters _ . @ -");
        }
        if (password == null || password.length == 0
                || java.nio.CharBuffer.wrap(password).chars().allMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Password must not be empty or whitespace.");
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
        if (bootstrap.bindFirstSuper(account.getId()) != 1 || bootstrap.completeBootstrap(account.getId()) != 1) {
            throw new IllegalStateException("Administrator initialization could not complete");
        }
        return account.getId();
    }
}
