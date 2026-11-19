package com.freesia.common.error;

/** A stable error identity. Each module declares its own enum; the web starter localizes and renders it. */
public interface ErrorCode {
    int code();

    /** Default text, a {@link java.text.MessageFormat} pattern, used when no translation exists. */
    String message();

    /** HTTP status sent with this error. Business errors are client errors unless stated otherwise. */
    default int httpStatus() {
        return 400;
    }

    default String i18nKey() {
        if (this instanceof Enum<?> constant) {
            return constant.getDeclaringClass().getSimpleName() + "." + constant.name();
        }
        return "error." + code();
    }
}
