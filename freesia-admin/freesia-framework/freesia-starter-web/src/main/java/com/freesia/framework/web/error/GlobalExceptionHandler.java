package com.freesia.framework.web.error;

import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import com.freesia.common.error.ErrorCode;
import com.freesia.common.result.R;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Renders every failure as {@link R} with a matching HTTP status. Runs last so that more specific
 * advices, such as the security starter's, are consulted first.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorMessages messages;

    public GlobalExceptionHandler(ErrorMessages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<R<Void>> handleApiException(ApiException exception, HttpServletRequest request) {
        ErrorCode code = exception.errorCode();
        log.warn("{} {} rejected: {} {}", request.getMethod(), request.getRequestURI(), code.code(), exception.getMessage());
        String message = messages.resolve(code, exception.args(), LocaleContextHolder.getLocale());
        return ResponseEntity.status(code.httpStatus()).body(R.fail(code.code(), message));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<R<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("{} {} failed", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.internalServerError().body(R.fail(CommonErrorCode.INTERNAL_ERROR.code(), describe(HttpStatus.INTERNAL_SERVER_ERROR)));
    }

    /** Spring MVC's own exceptions (binding, validation, 404, 405, ...) arrive here with their status. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = switch (exception) {
            case MethodArgumentNotValidException invalid -> join(invalid.getBindingResult().getAllErrors());
            case HandlerMethodValidationException invalid -> join(invalid.getAllErrors());
            default -> null;
        };
        if (message == null || message.isBlank()) {
            message = describe(status);
        }
        if (status.is5xxServerError()) {
            log.error("Request failed with {}", status, exception);
        }
        return super.handleExceptionInternal(exception, R.fail(status.value(), message), headers, status, request);
    }

    private String describe(HttpStatusCode status) {
        return CommonErrorCode.ofStatus(status.value())
                .map(code -> messages.resolve(code, null, LocaleContextHolder.getLocale()))
                .orElseGet(() -> status instanceof HttpStatus known ? known.getReasonPhrase() : status.toString());
    }

    private static String join(List<? extends MessageSourceResolvable> errors) {
        return errors.stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("; "));
    }
}
