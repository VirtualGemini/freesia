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
        if (!StpUtil.isLogin()) {
            throw new ApiException(CommonErrorCode.UNAUTHORIZED);
        }
        if (permission != null && !holds(StpUtil.getLoginIdAsLong(), permission.value())) {
            throw new ApiException(CommonErrorCode.FORBIDDEN);
        }
        return true;
    }

    private boolean holds(long accountId, String permission) {
        AuthorizationSource source = authorizationSource.getIfAvailable();
        return source != null && source.permissionsOf(accountId).contains(permission);
    }

    private static <A extends java.lang.annotation.Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }
}
