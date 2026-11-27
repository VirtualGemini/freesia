package com.freesia.system.iam.internal.account.dto;

public record ResetPasswordInput(String newPassword) {
    @Override public String toString() { return "ResetPasswordInput[redacted]"; }
}
