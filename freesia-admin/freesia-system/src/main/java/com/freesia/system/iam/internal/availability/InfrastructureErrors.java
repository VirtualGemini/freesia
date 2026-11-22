package com.freesia.system.iam.internal.availability;

import com.freesia.common.error.CommonErrorCode;
import com.freesia.common.result.R;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Infrastructure errors are never converted to authentication denial or leaked SQL/connection text. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class InfrastructureErrors {
    private final MessageSource messages;
    InfrastructureErrors(MessageSource messages) { this.messages = messages; }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<R<Void>> unavailable(DataAccessException failure) {
        org.slf4j.LoggerFactory.getLogger(InfrastructureErrors.class).error("Authentication infrastructure unavailable: {}", failure.getClass().getSimpleName());
        var error = CommonErrorCode.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(503).body(R.fail(error.code(),
                messages.getMessage(error.i18nKey(), null, error.message(), LocaleContextHolder.getLocale())));
    }
}
