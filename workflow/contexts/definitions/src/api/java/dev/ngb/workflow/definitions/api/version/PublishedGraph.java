package dev.ngb.workflow.definitions.api.version;

import java.util.UUID;

/**
 * A version an instance may pin.
 *
 * @param versionId the row an instance names
 * @param definitionId the definition it belongs to
 * @param version the number a Tenant sees
 * @param graph the graph as it was published
 */
public record PublishedGraph(UUID versionId, UUID definitionId, int version, GraphView graph) {}
