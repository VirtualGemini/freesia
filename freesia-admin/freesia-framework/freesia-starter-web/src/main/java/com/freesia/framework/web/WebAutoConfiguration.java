package com.freesia.framework.web;

import com.freesia.framework.web.error.ErrorMessages;
import com.freesia.framework.web.error.GlobalExceptionHandler;
import com.freesia.framework.web.error.ServletErrorController;
import com.freesia.framework.web.json.JsonConventions;
import com.freesia.framework.web.servlet.SecurityHeadersFilter;
import com.freesia.framework.web.servlet.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/** Web conventions shared by every Freesia HTTP endpoint. */
@AutoConfiguration(before = {WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebProperties.class)
public class WebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ErrorMessages errorMessages(MessageSource messageSource) {
        return new ErrorMessages(messageSource);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler(ErrorMessages errorMessages) {
        return new GlobalExceptionHandler(errorMessages);
    }

    @Bean
    @ConditionalOnMissingBean(ErrorController.class)
    ServletErrorController servletErrorController(ErrorMessages errorMessages) {
        return new ServletErrorController(errorMessages);
    }

    @Bean
    JsonMapperBuilderCustomizer freesiaJsonConventions() {
        return JsonConventions::apply;
    }

    /** Simplified Chinese unless the client asks for a supported language through Accept-Language. */
    @Bean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    @ConditionalOnMissingBean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    LocaleResolver localeResolver() {
        var resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.CHINESE, Locale.ENGLISH));
        resolver.setDefaultLocale(Locale.CHINESE);
        return resolver;
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        var registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "freesia.web.security-headers", name = "enabled", matchIfMissing = true)
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter(WebProperties properties) {
        var registration = new FilterRegistrationBean<>(new SecurityHeadersFilter(properties.securityHeaders()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
