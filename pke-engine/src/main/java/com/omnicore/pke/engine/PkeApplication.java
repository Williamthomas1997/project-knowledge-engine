package com.omnicore.pke.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PkeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PkeApplication.class, args);
    }
}
