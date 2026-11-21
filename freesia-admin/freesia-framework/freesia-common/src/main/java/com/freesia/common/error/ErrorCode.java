package com.freesia.common.error;

/** A stable error identity. Each module declares its own enum; the web starter localizes and renders it. */
public interface ErrorCode {
    int code();

    /** Locale-independent diagnostic fallback, a {@link java.text.MessageFormat} pattern. */
    String message();

    /** Explicit module-qualified translation key; independent of Java class and constant names. */
    String i18nKey();

    /** HTTP status sent with this error. Business errors are client errors unless stated otherwise. */
    default int httpStatus() {
        return 400;
    }
}
