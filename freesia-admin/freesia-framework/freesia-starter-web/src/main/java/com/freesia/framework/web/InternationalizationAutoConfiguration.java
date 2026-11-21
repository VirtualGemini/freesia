package com.freesia.framework.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Properties;

/** Shared resource messages for HTTP, validation and explicit-locale non-request work. */
@AutoConfiguration(before = MessageSourceAutoConfiguration.class)
@EnableConfigurationProperties(MessageSourceProperties.class)
public class InternationalizationAutoConfiguration {
    @Bean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    @ConditionalOnMissingBean(name = AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    ResourceBundleMessageSource messageSource(MessageSourceProperties properties) throws IOException {
        var source = new ResourceBundleMessageSource();
        var basenames = new LinkedHashSet<>(properties.getBasename());
        basenames.add("i18n/common/messages");
        source.setBasenames(basenames.toArray(String[]::new));
        source.setDefaultEncoding(properties.getEncoding().name());
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        source.setAlwaysUseMessageFormat(properties.isAlwaysUseMessageFormat());
        if (properties.getCacheDuration() != null) {
            source.setCacheMillis(properties.getCacheDuration().toMillis());
        }
        if (properties.getCommonMessages() != null) {
            var common = new Properties();
            for (var resource : properties.getCommonMessages()) {
                common.putAll(PropertiesLoaderUtils.loadProperties(new EncodedResource(resource, properties.getEncoding())));
            }
            source.setCommonMessages(common);
        }
        return source;
    }
}
