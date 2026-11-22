package dev.ngb.workflow.definitions.application.version.port;

import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import java.util.Optional;
import java.util.UUID;

/** The published graph a runtime pins, as the adapter reads it. */
public interface VersionGraphRows {

    Optional<PublishedGraph> graph(UUID versionId);

    Optional<PublishedGraph> latest(UUID definitionId);

    Optional<PublishedGraph> graphOf(String definitionCode, Integer version);

    Optional<PublishedGraph> latestByCode(String definitionCode);

    Optional<String> definitionCode(UUID definitionId);
}
