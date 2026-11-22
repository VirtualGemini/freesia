package com.freesia.admin;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import com.freesia.system.FreesiaSystemConfiguration;
import com.freesia.system.iam.api.BootstrapAdministratorCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Executable entry point only. Business assembly and implementation belong to freesia-system;
 * technical infrastructure belongs to the framework starters.
 */
@SpringBootApplication
@Import(FreesiaSystemConfiguration.class)
public class FreesiaAdminApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FreesiaAdminApplication.class);

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("bootstrap-admin")) {
            BootstrapAdministratorCommand.run(FreesiaAdminApplication.class, args);
            return;
        }

        long start = System.currentTimeMillis();

        SpringApplication app = new SpringApplication(FreesiaAdminApplication.class);
        app.setApplicationStartup(new BufferingApplicationStartup(2048));
        ConfigurableApplicationContext context = app.run(args);

        long cost = System.currentTimeMillis() - start;
        String name = context.getEnvironment().getProperty("spring.application.name", "");
        String port = context.getEnvironment().getProperty("server.port", "");
        String profile = context.getEnvironment().getProperty("spring.profiles.active", "");
        String finishedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String banner = """

                ------------------------------------------------------------------------
                START SUCCESS
                ------------------------------------------------------------------------
                Application : {}
                Port        : {}
                Profile     : {}
                GitHub      : https://github.com/VirtualGemini/freesia
                ------------------------------------------------------------------------
                Total time  : {} ms
                Finished at : {}
                ------------------------------------------------------------------------
                """;

        log.info(banner, name, port, profile, cost, finishedAt);
    }
}
