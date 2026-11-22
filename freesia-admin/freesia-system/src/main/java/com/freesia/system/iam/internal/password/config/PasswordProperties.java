package com.freesia.system.iam.internal.password.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("freesia.iam.password")
public record PasswordProperties(
        @DefaultValue("65536") int memoryKib,
        @DefaultValue("3") int iterations,
        @DefaultValue("4") int parallelism,
        @DefaultValue("4") int maxConcurrent) {
    public PasswordProperties {
        if (memoryKib < 8192 || memoryKib > 1048576 || iterations < 1 || iterations > 20
                || parallelism < 1 || parallelism > 16 || memoryKib < 8 * parallelism
                || maxConcurrent < 1 || maxConcurrent > 32) {
            throw new IllegalArgumentException("Invalid bounded Argon2id configuration");
        }
    }
}
