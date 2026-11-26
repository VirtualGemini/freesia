package com.freesia.system.iam.internal.account.dto;

import java.util.List;
import java.util.UUID;

public record AccountStatusPreview(List<Target> accounts) {
    public record Target(UUID accountId, String username, String status) {}
}
