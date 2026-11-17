package dev.ngb.workflow.definitions.application.definition.query;

import dev.ngb.workflow.definitions.domain.definition.DefinitionStatus;
import java.time.Instant;
import java.util.UUID;

/** A definition as the GET handlers show it. */
public record DefinitionView(
        UUID id,
        String code,
        String name,
        DefinitionStatus status,
        int latestVersion,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
