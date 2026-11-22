package dev.ngb.loyalty.points.application.earn.port;

import java.util.UUID;

/**
 * The rise-only tier check that runs after an order earned or a return took its points back: a member that crossed a
 * tier's qualifying spend moves up, and a member never moves down because an order was returned.
 *
 * <p>It is a port so this context declares what it needs without knowing who answers it: the deployment wires the
 * evaluation over the member's spend, and this context imports nothing of that context to ask.
 */
public interface TierEvaluator {

    /** Runs inside the earn's transaction, after the points are applied to the member. */
    void afterEarn(UUID memberId);

    /**
     * Runs inside the return's transaction, after the clawback is applied: the member keeps the tier it holds, and a
     * return that took its spend below that tier asks for the next evaluation to decide.
     */
    void afterReturn(UUID memberId);
}
