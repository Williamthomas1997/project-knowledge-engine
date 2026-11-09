package com.omnicore.pke.spi.journey;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * What an adapter needs to know about the source tree it reads.
 *
 * @param root              root of the (checked-out or exported) repository
 * @param identityHeaders   gateway headers that identify an API (e.g. moduleId, subModuleId, serviceId, screenId)
 * @param serviceHeaders    all gateway service headers to capture with their codes (identity headers + unit, channel)
 * @param javaClassPatterns regular expressions for Java journey/simulation class names (e.g. {@code .*Journey})
 */
public record JourneyContext(Path root, List<String> identityHeaders, Set<String> serviceHeaders,
                             List<String> javaClassPatterns) {
}
