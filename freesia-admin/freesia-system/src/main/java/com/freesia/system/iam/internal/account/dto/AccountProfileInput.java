package com.freesia.system.iam.internal.account.dto;

public record AccountProfileInput(String username, String remark, String nickname, String email, String phone) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name,Object value) {
        throw new IllegalArgumentException("Unsupported account profile field");
    }
}
