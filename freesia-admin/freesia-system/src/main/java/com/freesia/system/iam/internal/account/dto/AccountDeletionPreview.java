package com.freesia.system.iam.internal.account.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccountDeletionPreview(String confirmationToken, Instant expiresAt, List<Target> accounts,
                                     List<Target> blockedAccounts) {
    public record Target(UUID accountId, String username) {}
}
