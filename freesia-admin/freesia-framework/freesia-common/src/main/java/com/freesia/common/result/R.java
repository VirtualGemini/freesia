package com.freesia.common.result;

/**
 * Response envelope of every JSON endpoint. {@code code} follows HTTP status semantics for
 * framework-level outcomes; business modules use codes from 10000 upwards.
 */
public record R<T>(int code, String msg, T data) {
    public static final int SUCCESS = 200;
    private static final String OK = "OK";

    public static <T> R<T> ok() {
        return new R<>(SUCCESS, OK, null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, OK, data);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }
}
