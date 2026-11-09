package com.omnicore.pke.engine.ingest;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for deterministic ingestion ({@code pke.ingestion.*}). The defaults match the Dukhan/OCS conventions
 * found in the real repositories; everything is overridable.
 *
 * @param identityHeaders       gateway headers that identify an API (with method and path)
 * @param serviceHeaders        all gateway service headers captured with their codes
 * @param javaJourneyPatterns   class-name patterns for Java journeys and Gatling simulations
 * @param errorCodePattern      string constants (and literals in throws) matching this are error codes
 * @param errorConfigTable      release-script table defining error codes
 * @param errorConfigCodeColumn column holding the code
 * @param errorConfigDescriptionColumn column holding a description (kept as evidence excerpt)
 * @param loggerNames           field names treated as loggers
 * @param routeExportFiles      gateway route exports (SQL INSERT scripts of the route table)
 * @param routeTable            route table name in the export
 * @param batchSize             entities per write transaction
 * @param maxCommits            commits recorded per incremental run
 * @param maxFileBytes          files larger than this are not exported for analysis
 */
@ConfigurationProperties("pke.ingestion")
public record IngestionProperties(
        List<String> identityHeaders,
        List<String> serviceHeaders,
        List<String> javaJourneyPatterns,
        String errorCodePattern,
        String errorConfigTable,
        String errorConfigCodeColumn,
        String errorConfigDescriptionColumn,
        List<String> loggerNames,
        List<String> routeExportFiles,
        String routeTable,
        Integer batchSize,
        Integer maxCommits,
        Long maxFileBytes) {

    public IngestionProperties {
        identityHeaders = identityHeaders == null ? List.of("moduleId", "subModuleId", "serviceId", "screenId") : identityHeaders;
        serviceHeaders = serviceHeaders == null
                ? List.of("moduleId", "subModuleId", "serviceId", "screenId", "unit", "channel") : serviceHeaders;
        javaJourneyPatterns = javaJourneyPatterns == null ? List.of(".*Journey", ".*E2ETest", ".*Simulation") : javaJourneyPatterns;
        errorCodePattern = errorCodePattern == null ? "^(?!000000$)(?:[A-Z]{2,6}-)?\\d{6}$" : errorCodePattern;
        errorConfigTable = errorConfigTable == null ? "OCS_T_ERROR_CONFIG" : errorConfigTable;
        errorConfigCodeColumn = errorConfigCodeColumn == null ? "OCS_ERR_CODE" : errorConfigCodeColumn;
        errorConfigDescriptionColumn = errorConfigDescriptionColumn == null ? "OCS_ERR_DESC" : errorConfigDescriptionColumn;
        loggerNames = loggerNames == null ? List.of("log", "logger", "LOG", "LOGGER") : loggerNames;
        routeExportFiles = routeExportFiles == null ? List.of() : routeExportFiles;
        routeTable = routeTable == null ? "ocs_t_gateway_api_route" : routeTable;
        batchSize = batchSize == null ? 200 : batchSize;
        maxCommits = maxCommits == null ? 500 : maxCommits;
        maxFileBytes = maxFileBytes == null ? 5L * 1024 * 1024 : maxFileBytes;
    }
}
