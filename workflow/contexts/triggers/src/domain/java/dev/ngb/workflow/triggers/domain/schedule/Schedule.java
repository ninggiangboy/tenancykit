package dev.ngb.workflow.triggers.domain.schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * A cron schedule of one trigger.
 *
 * @param nextDue when the next tick is due; the firer advances it before it starts an instance, so two workers never
 *     fire the same tick
 */
public record Schedule(
        UUID id,
        UUID tenantId,
        UUID triggerId,
        String cron,
        String timezone,
        Instant nextDue,
        Instant lastFired,
        long version) {

    public Schedule fired(Instant nextDue, Instant firedAt) {
        return new Schedule(id, tenantId, triggerId, cron, timezone, nextDue, firedAt, version + 1);
    }
}
