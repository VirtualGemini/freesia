package com.freesia.admin;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.Modulithic;

/**
 * Direct sub-packages are the business modules. Nothing else lives in this package: technical
 * wiring belongs to the framework starters, which sit outside {@code com.freesia.admin} so that
 * Spring Modulith does not mistake them for modules.
 */
@SpringBootApplication
@EnableCaching
@Modulithic(systemName = "Freesia Admin")
public class FreesiaAdminApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FreesiaAdminApplication.class);

    public static void main(String[] args) {

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
