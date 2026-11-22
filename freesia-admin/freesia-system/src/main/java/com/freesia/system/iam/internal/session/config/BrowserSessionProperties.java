package com.freesia.system.iam.internal.session.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("freesia.iam.browser-session")
public record BrowserSessionProperties(@DefaultValue("true") boolean cookieSecure) {}
