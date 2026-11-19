package com.freesia.common.error;

import java.util.Optional;

/** Framework-level outcomes. The code equals the HTTP status, which is what freesia-web's ApiStatus expects. */
public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有访问权限"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    CONFLICT(409, "资源冲突"),
    PAYLOAD_TOO_LARGE(413, "请求数据过大"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),
    INTERNAL_ERROR(500, "系统内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用");

    private final int code;
    private final String message;

    CommonErrorCode(int code, String message) {
        this.code = code;
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
