package com.freesia.common.error;

import java.text.MessageFormat;
import java.util.Locale;

/** The one exception type for expected failures. The message shown to users is resolved from the error code. */
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    private final transient Object[] args;

    public ApiException(ErrorCode errorCode, Object... args) {
        this(null, errorCode, args);
    }

    public ApiException(Throwable cause, ErrorCode errorCode, Object... args) {
        super(format(errorCode.message(), args), cause);
        this.errorCode = errorCode;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object[] args() {
        return args.clone();
    }

    /** Formats only when there are arguments, so plain text containing quotes or braces is left untouched. */
    public static String format(String pattern, Object... args) {
        return args == null || args.length == 0 ? pattern : new MessageFormat(pattern, Locale.ROOT).format(args);
    }
}
