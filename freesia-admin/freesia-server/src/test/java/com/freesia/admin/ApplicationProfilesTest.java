package com.freesia.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Load the deployment configuration without connecting to external services. */
class ApplicationProfilesTest {
    @Test
    void localStartupDefaultsToDevAndUsesTheComposeEndpoints() {
        try (var context = application()) {
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).isEmpty();
            assertThat(environment.getDefaultProfiles()).containsExactly("dev");
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost:15432/freesia");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("freesia");
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("freesia-local");
            assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("localhost");
            assertThat(environment.getProperty("spring.data.redis.port", Integer.class)).isEqualTo(16379);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "test"})
    void anExplicitEnvironmentUsesItsSuppliedConnectionsAndSharedSafetyDefaults(String profile) {
        try (var context = application("--spring.profiles.active=" + profile,
                "--FREESIA_DB_URL=jdbc:postgresql://database.example:5432/isolated",
                "--FREESIA_DB_USERNAME=isolated-user", "--FREESIA_DB_PASSWORD=isolated-password",
                "--FREESIA_REDIS_HOST=valkey.example", "--FREESIA_REDIS_PORT=16380",
                "--FREESIA_REDIS_PASSWORD=isolated-secret")) {
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly(profile);
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://database.example:5432/isolated");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("isolated-user");
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("isolated-password");
            assertThat(environment.getProperty("spring.data.redis.host")).isEqualTo("valkey.example");
            assertThat(environment.getProperty("spring.data.redis.port", Integer.class)).isEqualTo(16380);
            assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("isolated-secret");
            assertThat(environment.getProperty("spring.messages.fallback-to-system-locale", Boolean.class)).isFalse();
            assertThat(environment.getProperty("spring.flyway.default-schema")).isEqualTo("public");
            assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "test"})
    void nonDevelopmentEnvironmentsNeverFallBackToLocalDatabaseCredentials(String profile) {
        try (var context = application("--spring.profiles.active=" + profile)) {
            var environment = context.getEnvironment();
            assertThatThrownBy(() -> environment.getRequiredProperty("spring.datasource.url"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FREESIA_DB_URL");
            assertThatThrownBy(() -> environment.getRequiredProperty("spring.datasource.username"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FREESIA_DB_USERNAME");
            assertThatThrownBy(() -> environment.getRequiredProperty("spring.datasource.password"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FREESIA_DB_PASSWORD");
            assertThatThrownBy(() -> environment.getRequiredProperty("spring.data.redis.host"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FREESIA_REDIS_HOST");
        }
    }

    @Test
    void productionRequiresAnExplicitValkeyAuthenticationChoice() {
        try (var context = application("--spring.profiles.active=prod")) {
            assertThatThrownBy(() -> context.getEnvironment().getRequiredProperty("spring.data.redis.password"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FREESIA_REDIS_PASSWORD");
        }
        try (var context = application("--spring.profiles.active=prod", "--FREESIA_REDIS_PASSWORD=")) {
            assertThat(context.getEnvironment().getProperty("spring.data.redis.password")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "test"})
    void refreshCookiesDefaultToSecureExceptForExplicitLocalAndTestProfiles(String profile) {
        try (var context = application("--spring.profiles.active=" + profile)) {
            assertThat(context.getEnvironment().getProperty("freesia.iam.browser-session.cookie-secure", Boolean.class))
                    .isEqualTo(profile.equals("prod"));
        }
        try (var context = application("--spring.profiles.active=" + profile, "--FREESIA_REFRESH_COOKIE_SECURE=true")) {
            assertThat(context.getEnvironment().getProperty("freesia.iam.browser-session.cookie-secure", Boolean.class)).isTrue();
        }
    }

    @Test
    void databaseInitializationDefaultsToChineseAndCanSelectOnlyTheEnglishAlternative() {
        try (var context = application()) {
            assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                    .isEqualTo("classpath:db/migration/zh");
            assertThat(context.getEnvironment().getProperty("spring.flyway.fail-on-missing-locations", Boolean.class))
                    .isTrue();
        }
        try (var context = application("--FREESIA_DB_LANGUAGE=en")) {
            assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                    .isEqualTo("classpath:db/migration/en");
        }
    }

    private ConfigurableApplicationContext application(String... arguments) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var application = new SpringApplication(ConfigurationProbe.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(java.util.Map.of(
                "spring.config.location", "classpath:/application.yml", "spring.main.banner-mode", "off"));
        return application.run(arguments);
    }

    static class ConfigurationProbe {}
}
