package com.freesia.system.iam.api;

import com.freesia.common.error.ErrorCode;

/** Stable authentication outcomes shared with clients, independent of translated messages. */
public enum IamErrorCode implements ErrorCode {
    INVALID_CREDENTIALS(10001, 400, "iam.error.invalid-credentials", "Invalid username or password"),
    ACCESS_EXPIRED(10002, 401, "iam.error.access-expired", "Access credential expired before the request was executed"),
    SESSION_INVALID(10003, 401, "iam.error.session-invalid", "Session is no longer valid"),
    PASSWORD_PAUSED(10004, 429, "iam.error.password-paused", "Too many failed password attempts; try again later"),
    PASSWORD_CAPACITY(10005, 503, "iam.error.password-capacity", "Password verification is temporarily busy"),
    BOOTSTRAP_COMPLETED(10006, 409, "iam.error.bootstrap-completed", "Administrator initialization is already complete");

    private final int code;
    private final int status;
    private final String key;
    private final String diagnostic;

    IamErrorCode(int code, int status, String key, String diagnostic) {
        this.code = code;
        this.status = status;
        this.key = key;
        this.diagnostic = diagnostic;
    }
    @Override public int code() { return code; }
    @Override public int httpStatus() { return status; }
    @Override public String i18nKey() { return key; }
    @Override public String message() { return diagnostic; }
}
