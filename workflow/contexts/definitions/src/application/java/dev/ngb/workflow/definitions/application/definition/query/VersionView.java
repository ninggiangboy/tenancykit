package dev.ngb.workflow.definitions.application.definition.query;

import java.time.Instant;
import java.util.UUID;

/** One published version as the GET handlers show it, graph included because that is what a client pins. */
public record VersionView(
        UUID id,
        UUID definitionId,
        int version,
        Object graph,
        String contentHash,
        Instant publishedAt,
        String publishedBy) {}
