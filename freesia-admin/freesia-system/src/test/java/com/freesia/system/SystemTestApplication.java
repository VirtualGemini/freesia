package com.freesia.system;

import com.freesia.system.iam.api.BootstrapAdministratorCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.Modulithic;

/** Runs system integration tests without a dependency on the executable server module. */
@SpringBootApplication
@Import(FreesiaSystemConfiguration.class)
@Modulithic(systemName = "Freesia Admin")
public class SystemTestApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("bootstrap-admin")) {
            BootstrapAdministratorCommand.run(SystemTestApplication.class, args);
            return;
        }
        SpringApplication.run(SystemTestApplication.class, args);
    }
}
