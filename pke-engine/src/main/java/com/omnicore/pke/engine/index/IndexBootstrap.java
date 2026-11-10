package com.omnicore.pke.engine.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

/** After the schema bootstrap: the active embedding generation, collection nodes and system collections. */
@Component
@Order(40)
@EnableScheduling
public class IndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

    private final EmbeddingGenerations generations;
    private final Collections collections;

    public IndexBootstrap(EmbeddingGenerations generations, Collections collections) {
        this.generations = generations;
        this.collections = collections;
    }

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    public void load() {
        var active = generations.ensureActive();
        collections.sync();
        log.info("Vector collections ready ({} generation {}, model {})", collections.content().size(), active.id(), active.model());
    }
}
