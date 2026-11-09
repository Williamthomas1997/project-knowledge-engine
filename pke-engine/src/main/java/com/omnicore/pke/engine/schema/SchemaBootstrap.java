package com.omnicore.pke.engine.schema;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Promotes the Git-versioned definitions into the running registry at startup (after constraints exist). */
@Component
@Order(20)
@ConditionalOnProperty(name = "pke.schema.bootstrap", havingValue = "true", matchIfMissing = true)
public class SchemaBootstrap implements ApplicationRunner {

    private final SchemaService schemaService;

    public SchemaBootstrap(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Override
    public void run(ApplicationArguments args) {
        schemaService.bootstrap();
    }
}
