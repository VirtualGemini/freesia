package com.freesia.framework.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sessions are Sa-Token's, stored in Valkey through the application's Redis connection. This starter
 * adds the access annotations on top; signing in and out belongs to the module that owns accounts.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityAutoConfiguration {
    @Bean
    WebMvcConfigurer accessInterceptorRegistration(ObjectProvider<AuthorizationSource> authorizationSource) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AccessInterceptor(authorizationSource));
            }
        };
    }
}
