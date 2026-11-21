package com.freesia.framework.web.error;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.ErrorCode;
import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * Renders stable error identities through the shared MessageSource. Exceptions keep diagnostic text;
 * only the outgoing response uses the request locale.
 */
public class ErrorMessages {
    private final MessageSource messages;

    public ErrorMessages(MessageSource messages) {
        this.messages = messages;
    }

    public String resolve(ErrorCode code, Object[] args, Locale locale) {
        String key = code.i18nKey();
        String message = messages.getMessage(key, args, null, locale);
        if (message != null && !message.equals(key)) {
            return message;
        }
        return ApiException.format(code.message(), args);
    }
}
