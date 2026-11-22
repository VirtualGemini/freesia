package com.freesia.system.iam;

import com.freesia.system.SystemTestApplication;
import com.freesia.system.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThat(Files.readString(output)).contains("password must be 12-256 characters").doesNotContain("Administrator initialization completed.");
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
