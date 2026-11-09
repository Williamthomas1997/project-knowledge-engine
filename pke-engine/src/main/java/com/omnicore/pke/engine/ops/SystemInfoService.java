package com.omnicore.pke.engine.ops;

import com.omnicore.pke.engine.ai.ModelAvailability;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class SystemInfoService {

    private final Neo4jCapabilityCheck neo4j;
    private final ModelAvailability models;
    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildProperties;

    public SystemInfoService(Neo4jCapabilityCheck neo4j, ModelAvailability models, Environment environment,
                             ObjectProvider<BuildProperties> buildProperties) {
        this.neo4j = neo4j;
        this.models = models;
        this.environment = environment;
        this.buildProperties = buildProperties;
    }

    public SystemInfo info() {
        Neo4jServerInfo server = neo4j.detect();
        BuildProperties build = buildProperties.getIfAvailable();
        return new SystemInfo(
                build != null ? build.getVersion() : "unknown",
                server.version(),
                server.edition(),
                List.of(environment.getActiveProfiles()),
                models.chatModelConfigured(),
                models.embeddingModelConfigured());
    }
}
