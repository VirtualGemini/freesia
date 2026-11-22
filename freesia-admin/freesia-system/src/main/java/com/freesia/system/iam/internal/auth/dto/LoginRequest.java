package com.freesia.system.iam.internal.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message="{iam.validation.username-required}") @Size(max=50,message="{iam.validation.username-length}") String userName,
        @NotBlank(message="{iam.validation.password-required}") String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
