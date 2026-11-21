package com.freesia.common.error;

import java.util.Optional;

/** Framework-level outcomes. The code equals the HTTP status, which is what freesia-web's ApiStatus expects. */
public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(400, "common.error.bad-request", "Bad request"),
    UNAUTHORIZED(401, "common.error.unauthorized", "Not signed in or the session has expired"),
    FORBIDDEN(403, "common.error.forbidden", "Access denied"),
    NOT_FOUND(404, "common.error.not-found", "Resource not found"),
    METHOD_NOT_ALLOWED(405, "common.error.method-not-allowed", "Method not allowed"),
    NOT_ACCEPTABLE(406, "common.error.not-acceptable", "Requested response type is not supported"),
    REQUEST_TIMEOUT(408, "common.error.request-timeout", "Request timed out"),
    CONFLICT(409, "common.error.conflict", "Resource conflict"),
    PAYLOAD_TOO_LARGE(413, "common.error.payload-too-large", "Request payload too large"),
    UNSUPPORTED_MEDIA_TYPE(415, "common.error.unsupported-media-type", "Request content type is not supported"),
    TOO_MANY_REQUESTS(429, "common.error.too-many-requests", "Too many requests"),
    INTERNAL_ERROR(500, "common.error.internal-error", "Internal server error"),
    NOT_IMPLEMENTED(501, "common.error.not-implemented", "Feature not implemented"),
    BAD_GATEWAY(502, "common.error.bad-gateway", "Upstream service error"),
    SERVICE_UNAVAILABLE(503, "common.error.service-unavailable", "Service unavailable"),
    GATEWAY_TIMEOUT(504, "common.error.gateway-timeout", "Upstream service timed out");

    private final int code;
    private final String i18nKey;
    private final String message;

    CommonErrorCode(int code, String i18nKey, String message) {
        this.code = code;
        this.i18nKey = i18nKey;
        this.message = message;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public String i18nKey() {
        return i18nKey;
    }

    @Override
    public int httpStatus() {
        return code;
    }

    public static Optional<CommonErrorCode> ofStatus(int status) {
        for (CommonErrorCode value : values()) {
            if (value.code == status) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
