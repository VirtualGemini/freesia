package com.freesia.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Direct sub-packages are the business modules. Nothing else lives in this package: technical
 * wiring belongs to the framework starters, which sit outside {@code com.freesia.admin} so that
 * Spring Modulith does not mistake them for modules.
 */
@SpringBootApplication
@Modulithic(systemName = "Freesia Admin")
public class FreesiaAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(FreesiaAdminApplication.class, args);
    }
}
