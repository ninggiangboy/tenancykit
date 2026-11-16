package dev.ngb.loyalty.points.application.earn.port;

import java.util.UUID;

/**
 * The rise-only tier check that runs after an order earned: a member that crossed a tier's qualifying spend moves up,
 * and a member never moves down because an order was returned.
 *
 * <p>It is a port so this context declares what it needs without knowing who answers it: the deployment wires the
 * evaluation over the member's spend, and this context imports nothing of that context to ask.
 */
public interface TierEvaluator {

    /** Runs inside the earn's transaction, after the points are applied to the member. */
    void afterEarn(UUID memberId);
}
