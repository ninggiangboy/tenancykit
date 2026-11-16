package dev.ngb.loyalty.points.application.redemption.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The redemptions a member made.
 *
 * <p>A redemption is looked up by its idempotency key only after the member's row is locked, so two calls carrying one
 * key cannot both decide the key is free: the second waits for the member and reads what the first committed.
 */
public interface RedemptionRepository {

    /** Inserts the redemption; a key the tenant already used inserts nothing and answers false. */
    boolean add(RedemptionRow redemption);

    /** The redemption a key produced, or empty when the tenant has not used that key. */
    Optional<RedemptionRow> findByIdempotencyKey(String idempotencyKey);

    /** The redemption by id, under a row lock, for the reversal that must not run twice. */
    Optional<RedemptionRow> lockById(UUID id);

    /** Marks the redemption reversed; the caller has already restored its lots. */
    void markReversed(UUID id, Instant reversedAt);

    /**
     * Clears the Tenant's own reference on every redemption the member made, which is the free text an erasure cannot keep:
     * the redemption itself stays as an anonymous spend of points keyed by the opaque member id.
     *
     * @return how many rows held a reference to clear
     */
    int clearMemberReferences(UUID memberId);
}
