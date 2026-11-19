package com.freesia.framework.web.error;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.ErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

/**
 * Localizes error codes: the application's own messages first, then the framework bundle, and finally
 * the default text of the code, so a missing translation never surfaces as a raw key.
 */
public class ErrorMessages {
    private final MessageSource application;
    private final ResourceBundleMessageSource builtin = new ResourceBundleMessageSource();

    public ErrorMessages(MessageSource application) {
        this.application = application;
        builtin.setBasename("i18n/freesia-web");
        builtin.setDefaultEncoding("UTF-8");
        builtin.setFallbackToSystemLocale(false);
    }

    public String resolve(ErrorCode code, Object[] args, Locale locale) {
        String key = code.i18nKey();
        String message = application.getMessage(key, args, null, locale);
        if (message != null && !message.equals(key)) {
            return message;
        }
        return builtin.getMessage(key, args, ApiException.format(code.message(), args), locale);
    }
}
