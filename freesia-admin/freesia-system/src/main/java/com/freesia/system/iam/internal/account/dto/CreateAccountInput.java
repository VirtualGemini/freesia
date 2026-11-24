package com.freesia.system.iam.internal.account.dto;

import java.util.List;
import java.util.UUID;

public record CreateAccountInput(String username, String password, String remark, UUID organizationId,
                                 List<UUID> roleIds, List<String> roleCodes) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name,Object value) {
        throw new IllegalArgumentException("Unsupported account creation field");
    }
}
