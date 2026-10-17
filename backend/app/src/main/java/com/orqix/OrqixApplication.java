package com.orqix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(sharedModules = "shared")
public class OrqixApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(OrqixApplication.class, args);
        if (context.getEnvironment().matchesProfiles("migrate")) {
            // The migration job applies Flyway and the engine schema during startup, then exits (S1 section 5).
            System.exit(SpringApplication.exit(context));
        }
    }
}
