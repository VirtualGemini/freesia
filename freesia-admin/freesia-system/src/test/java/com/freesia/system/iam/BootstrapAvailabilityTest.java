package com.freesia.system.iam;

import com.freesia.system.SystemTestApplication;
import com.freesia.system.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAvailabilityTest extends IntegrationTest {
    @TempDir Path temporary;

    @Test
    void aBlankBootstrapSecretIsRejectedBeforeCompletingTheInitializationMarker() throws Exception {
        Path secret=temporary.resolve("blank.secret");
        Files.writeString(secret,"            ");
        Path output=temporary.resolve("blank-bootstrap.log");
        var command=List.of(
                Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                SystemTestApplication.class.getName(),"bootstrap-admin","--username=blank-owner","--password-file="+secret,
                "--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),
                "--spring.datasource.password="+POSTGRES.getPassword(),"--spring.data.redis.host="+VALKEY.getHost(),
                "--spring.data.redis.port="+VALKEY.getMappedPort(6379),"--spring.main.banner-mode=off");
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean ended=process.waitFor(30,TimeUnit.SECONDS);
        if(!ended)process.destroyForcibly();
        assertThat(ended).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Files.readString(output)).contains("Password must not be empty or whitespace.")
                .doesNotContain("Administrator initialization completed.");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 300})
    void anyNonblankSecretCanInitializeAnAdministratorWithoutStrengthChecks(int length) throws Exception {
        try (var database = new PostgreSQLContainer("postgres:18.4")
                .withDatabaseName("bootstrap_password").withUsername("freesia").withPassword("test-only")) {
            database.start();
            Path secret = temporary.resolve("nonblank.secret");
            String password = "1".repeat(length);
            Files.writeString(secret, password);
            Path output = temporary.resolve("nonblank-bootstrap.log");
            var command = List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    SystemTestApplication.class.getName(), "bootstrap-admin",
                    "--username=nonblank-owner", "--password-file=" + secret,
                    "--spring.datasource.url=" + database.getJdbcUrl(),
                    "--spring.datasource.username=" + database.getUsername(),
                    "--spring.datasource.password=" + database.getPassword(),
                    "--spring.data.redis.host=" + VALKEY.getHost(),
                    "--spring.data.redis.port=" + VALKEY.getMappedPort(6379),
                    "--spring.main.banner-mode=off");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean ended = process.waitFor(30, TimeUnit.SECONDS);
            if (!ended) process.destroyForcibly();
            assertThat(ended).isTrue();
            assertThat(process.exitValue()).withFailMessage(() -> {
                try { return Files.readString(output); }
                catch (java.io.IOException failure) { return failure.toString(); }
            }).isZero();
            assertThat(Files.readString(output)).contains("Administrator initialization completed.");
            var jdbc = JdbcClient.create(new DriverManagerDataSource(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword()));
            assertThat(jdbc.sql("SELECT password_hash FROM freesia.sys_account WHERE username='nonblank-owner'")
                    .query(String.class).single()).startsWith("$argon2id$");
            assertThat(jdbc.sql("SELECT completed_at IS NOT NULL FROM freesia.sys_bootstrap WHERE purpose='first-super'")
                    .query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void aStartupWithoutValkeyFailsInsteadOfStartingWithLocalSessionState() throws Exception {
        Path output = temporary.resolve("startup.log");
        // No password and no bootstrap operation: a normal application start must fail.
        var command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SystemTestApplication.class.getName(),
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=1",
                "--spring.data.redis.connect-timeout=300ms",
                "--spring.data.redis.timeout=300ms",
                "--spring.main.banner-mode=off");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean ended = process.waitFor(30, TimeUnit.SECONDS);
        if (!ended) process.destroyForcibly();
        assertThat(ended).isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(Files.readString(output)).contains("Valkey is required at startup").doesNotContain("START SUCCESS");
    }
}
