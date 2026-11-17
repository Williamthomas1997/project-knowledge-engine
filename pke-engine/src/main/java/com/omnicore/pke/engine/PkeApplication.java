package com.omnicore.pke.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// API keys only (ADR-017): no generated user, so no generated password is ever logged
@SpringBootApplication(excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@ConfigurationPropertiesScan
public class PkeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PkeApplication.class, args);
    }
}
