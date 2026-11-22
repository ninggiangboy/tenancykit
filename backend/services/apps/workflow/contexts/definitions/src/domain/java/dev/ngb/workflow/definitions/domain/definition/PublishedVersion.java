package dev.ngb.workflow.definitions.domain.definition;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.definitions.domain.expression.Json;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable published version of a definition. An instance pins its id, so editing the draft later cannot change
 * what a running instance does.
 *
 * @param contentHash a digest of the canonical graph document, so two publishes of the same graph are recognisable
 */
public record PublishedVersion(
        UUID id,
        TenantId tenantId,
        UUID definitionId,
        int number,
        Object graph,
        String contentHash,
        Instant publishedAt,
        String publishedBy) {

    public PublishedVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(publishedAt, "publishedAt");
        graph = Json.copy(graph);
    }
}
