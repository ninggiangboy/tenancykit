package dev.ngb.attribution.links.application.campaign.port;

import java.util.UUID;

/**
 * Asks for a campaign's conversions to be re-credited after its model or window changed.
 *
 * <p>The campaign side does not know how a recompute runs — that is the attribution context's — so it only raises the
 * request. A deployment with no recompute wired leaves this a no-op, which is the safe state before the engine exists.
 */
@FunctionalInterface
public interface RecomputeTrigger {

    /**
     * @param campaignId the campaign whose model or window changed
     * @param reason a short label for what changed, such as {@code model} or {@code window}
     */
    void request(UUID campaignId, String reason);

    /** A trigger that asks for nothing, for a deployment or a test without a recompute. */
    static RecomputeTrigger none() {
        return (campaignId, reason) -> {};
    }
}
