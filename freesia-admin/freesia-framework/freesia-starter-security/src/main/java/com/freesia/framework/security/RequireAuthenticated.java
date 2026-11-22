package com.freesia.framework.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The endpoint requires a signed-in account. On a controller class it applies to every handler method. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAuthenticated {
    /** Only credential-revocation endpoints may opt in; permission checks never accept expired Access. */
    boolean allowExpiredAccess() default false;
}
