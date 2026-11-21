package com.freesia.framework.web.error;

import com.freesia.common.error.CommonErrorCode;
import com.freesia.common.result.R;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/** Keeps container error dispatches on the same localized JSON contract as MVC exceptions. */
@RestController
public class ServletErrorController implements ErrorController {
    private final ErrorMessages messages;

    public ServletErrorController(ErrorMessages messages) {
        this.messages = messages;
    }

    @RequestMapping("${spring.web.error.path:${error.path:/error}}")
    ResponseEntity<R<Void>> error(HttpServletRequest request, Locale locale) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
        CommonErrorCode code = CommonErrorCode.ofStatus(status)
                .orElse(status >= 500 ? CommonErrorCode.INTERNAL_ERROR : CommonErrorCode.BAD_REQUEST);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(status, messages.resolve(code, null, locale)));
    }
}
