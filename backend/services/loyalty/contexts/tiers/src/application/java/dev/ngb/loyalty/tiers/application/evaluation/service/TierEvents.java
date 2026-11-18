package dev.ngb.loyalty.tiers.application.evaluation.service;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.IdGenerator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one fact the tier evaluation announces: a member's tier changed, written to the outbox inside the transaction that
 * writes the new tier.
 *
 * <p>Nothing is announced when the tier did not change, so a member that stays where it is costs its endpoints nothing.
 * A rise carries {@code UP} and a fall carries {@code DOWN}, which is also what tells the Tenant which way the member
 * moved without comparing the two codes.
 */
public class TierEvents {

    public static final String TIER_CHANGED = "tier.changed";

    private final EventPublisher publisher;
    private final IdGenerator ids;

    public TierEvents(EventPublisher publisher, IdGenerator ids) {
        this.publisher = publisher;
        this.ids = ids;
    }

    public void tierChanged(
            TenantId tenantId,
            Instant at,
            UUID memberId,
            String externalRef,
            String fromTier,
            String toTier,
            String direction,
            long qualifyingSpend) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId.toString());
        payload.put("externalRef", externalRef);
        payload.put("fromTier", fromTier);
        payload.put("toTier", toTier);
        payload.put("direction", direction);
        payload.put("qualifyingSpend", qualifyingSpend);
        publisher.publish(new EventEnvelope(ids.newId(), tenantId, TIER_CHANGED, "member/" + memberId, at, payload));
    }
}
