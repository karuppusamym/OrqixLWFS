package com.orqix.stub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Development and test fixture for the procurement contract. It is never evidence of a real integration. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProcurementStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcurementStubApplication.class, args);
    }
}
