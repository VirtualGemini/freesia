package com.freesia.admin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Artifact ownership is part of the startup-only module contract. */
class ServerAssemblyTest {
    @Test
    void theServerArtifactContainsOnlyTheEntryPointAndApplicationConfiguration() throws Exception {
        Path classes = Path.of(FreesiaAdminApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var files = Files.walk(classes)) {
            assertThat(files.filter(Files::isRegularFile).map(classes::relativize).map(Path::toString).sorted().toList())
                    .containsExactly("application-dev.yml", "application-prod.yml", "application-test.yml",
                            "application.yml", "com/freesia/admin/FreesiaAdminApplication.class");
        }
    }
}
