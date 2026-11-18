package dev.ngb.workflow.triggers.application.trigger.query;

import dev.ngb.workflow.triggers.domain.trigger.TriggerKind;
import java.time.Instant;
import java.util.UUID;

/** A trigger as the management API shows it. The signing secret never appears. */
public record TriggerView(
        UUID id,
        String name,
        TriggerKind kind,
        String source,
        String eventType,
        UUID definitionId,
        Object filter,
        Object correlation,
        Object input,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
