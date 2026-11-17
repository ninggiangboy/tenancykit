package dev.ngb.workflow.definitions.application.version.port;

import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import java.util.Optional;
import java.util.UUID;

/** The published graph a runtime pins, as the adapter reads it. */
public interface VersionGraphRows {

    Optional<PublishedGraph> graph(UUID versionId);
}
