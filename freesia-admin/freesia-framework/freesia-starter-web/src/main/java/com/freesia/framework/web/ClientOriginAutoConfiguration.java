package com.freesia.framework.web;

import com.freesia.framework.web.servlet.ClientIpResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Also available in non-web command contexts; no request or mutable thread context is created. */
@AutoConfiguration
@EnableConfigurationProperties(ClientIpResolver.Properties.class)
public class ClientOriginAutoConfiguration {
    @Bean
    ClientIpResolver clientIpResolver(ClientIpResolver.Properties properties) {
        return new ClientIpResolver(properties);
    }
}
