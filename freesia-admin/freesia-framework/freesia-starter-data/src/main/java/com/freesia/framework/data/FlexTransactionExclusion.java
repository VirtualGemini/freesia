package com.freesia.framework.data;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/** Leaves transaction management to Spring Boot's JDBC transaction manager; see {@link DataAutoConfiguration}. */
class FlexTransactionExclusion implements AutoConfigurationImportFilter {
    private static final String FLEX_TRANSACTIONS = "com.mybatisflex.spring.boot.v4.FlexTransactionAutoConfiguration";

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < matches.length; i++) {
            matches[i] = !FLEX_TRANSACTIONS.equals(autoConfigurationClasses[i]);
        }
        return matches;
    }
}
