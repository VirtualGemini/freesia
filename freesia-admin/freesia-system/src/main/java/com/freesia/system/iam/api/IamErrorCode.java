package com.freesia.system.iam.api;

import com.freesia.common.error.ErrorCode;

/** Stable authentication outcomes shared with clients, independent of translated messages. */
public enum IamErrorCode implements ErrorCode {
    INVALID_CREDENTIALS(10001, 400, "iam.error.invalid-credentials", "Invalid username or password"),
    ACCESS_EXPIRED(10002, 401, "iam.error.access-expired", "Access credential expired before the request was executed"),
    SESSION_INVALID(10003, 401, "iam.error.session-invalid", "Session is no longer valid"),
    PASSWORD_PAUSED(10004, 429, "iam.error.password-paused", "Too many failed password attempts; try again later"),
    PASSWORD_CAPACITY(10005, 503, "iam.error.password-capacity", "Password verification is temporarily busy"),
    BOOTSTRAP_COMPLETED(10006, 409, "iam.error.bootstrap-completed", "Administrator initialization is already complete"),
    ORGANIZATION_REQUIRED(10007, 409, "iam.error.organization-required", "Assign an organization or remove incompatible roles first"),
    ORGANIZATION_REFERENCED(10008, 409, "iam.error.organization-referenced", "Resolve child organizations, accounts or scope references first"),
    ORGANIZATION_CYCLE(10009, 409, "iam.error.organization-cycle", "An organization cannot be moved below itself"),
    EXCLUSIVE_OWNER(10010, 403, "iam.error.exclusive-owner", "The initial owner, root and owner role are exclusive and protected"),
    GRANT_EXCEEDED(10011, 403, "iam.error.grant-exceeded", "The role contains permissions or scope you cannot grant"),
    DUPLICATE_RESOURCE(10012, 409, "iam.error.duplicate-resource", "The username or resource code already exists"),
    MENU_HAS_CHILDREN(10013, 409, "iam.error.menu-has-children", "Remove child menus and permission buttons first"),
    MENU_PARENT_INVALID(10014, 409, "iam.error.menu-parent-invalid", "The parent must be a menu and cannot form a cycle"),
    MENU_ROUTE_REQUIRED(10015, 400, "iam.error.menu-route-required", "Provide a route path and an internal component or redirect when no external link is set"),
    MENU_PERMISSION_BOUND(10016, 409, "iam.error.menu-permission-bound", "Menu type is fixed; remove ordinary role bindings before changing its permission code"),
    ROLE_DELETION_CHANGED(10017, 409, "iam.error.role-deletion-changed", "The deletion preview expired or its impact changed; review a new preview before confirming"),
    ACCOUNT_MUST_BE_DISABLED(10018, 409, "iam.error.account-must-be-disabled", "Disable these accounts before deletion: {0}"),
    ACCOUNT_DELETION_CHANGED(10019, 409, "iam.error.account-deletion-changed", "The account deletion preview expired or changed; no accounts were deleted"),
    SUPER_ACCOUNT_PROTECTED(10020, 403, "iam.error.super-account-protected", "Super administrator accounts cannot be operated on");

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
