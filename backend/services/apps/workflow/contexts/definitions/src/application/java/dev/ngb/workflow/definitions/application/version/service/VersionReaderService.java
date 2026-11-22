package dev.ngb.workflow.definitions.application.version.service;

import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.definitions.application.version.port.VersionGraphRows;
import java.util.Optional;
import java.util.UUID;

/** Serves the runtime the graph of a pinned version. The class name and package are what {@code api-implementations-are-services} requires. */
public class VersionReaderService implements VersionReader {

    private final VersionGraphRows rows;

    public VersionReaderService(VersionGraphRows rows) {
        this.rows = rows;
    }

    @Override
    public Optional<PublishedGraph> graph(UUID versionId) {
        return rows.graph(versionId);
    }

    @Override
    public Optional<PublishedGraph> latest(UUID definitionId) {
        return rows.latest(definitionId);
    }

    @Override
    public Optional<PublishedGraph> graphOf(String definitionCode, Integer version) {
        return rows.graphOf(definitionCode, version);
    }

    @Override
    public Optional<String> definitionCode(UUID definitionId) {
        return rows.definitionCode(definitionId);
    }
}
