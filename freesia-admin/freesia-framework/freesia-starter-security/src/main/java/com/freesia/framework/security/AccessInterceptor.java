package com.freesia.framework.security;

import cn.dev33.satoken.stp.StpUtil;
import com.freesia.common.error.ApiException;
import com.freesia.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/** Enforces {@link RequireAuthenticated} and {@link RequirePermission} before a handler method runs. */
class AccessInterceptor implements HandlerInterceptor {
    private final ObjectProvider<AuthorizationSource> authorizationSource;

    AccessInterceptor(ObjectProvider<AuthorizationSource> authorizationSource) {
        this.authorizationSource = authorizationSource;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePermission permission = find(method, RequirePermission.class);
        if (permission == null && find(method, RequireAuthenticated.class) == null) {
            return true;
        }
        UUID accountId = authenticatedAccountId();
        if (permission != null && !holds(accountId, permission.value())) {
            throw new ApiException(CommonErrorCode.FORBIDDEN);
        }
        return true;
    }

    private static UUID authenticatedAccountId() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId instanceof UUID accountId) {
            return accountId;
        }
        if (!(loginId instanceof String value)) {
            throw new ApiException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            UUID accountId = UUID.fromString(value);
            if (!accountId.toString().equalsIgnoreCase(value)) {
                throw new ApiException(CommonErrorCode.UNAUTHORIZED);
            }
            return accountId;
        } catch (IllegalArgumentException invalidIdentity) {
            throw new ApiException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    private boolean holds(UUID accountId, String permission) {
        AuthorizationSource source = authorizationSource.getIfAvailable();
        return source != null && source.permissionsOf(accountId).contains(permission);
    }

    private static <A extends java.lang.annotation.Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }
}
