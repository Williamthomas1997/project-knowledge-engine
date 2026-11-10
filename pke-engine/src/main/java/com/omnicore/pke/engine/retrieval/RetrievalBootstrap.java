package com.omnicore.pke.engine.retrieval;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Loads the query templates after the schema registry (templates are checked against its vocabulary). */
@Component
@Order(45)
public class RetrievalBootstrap implements ApplicationRunner {

    private final QueryTemplates templates;

    public RetrievalBootstrap(QueryTemplates templates) {
        this.templates = templates;
    }

    @Override
    public void run(ApplicationArguments args) {
        templates.load();
    }
}
