package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.token.Token;
import dev.ngb.workflow.runtime.domain.token.TokenStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The token rows: the claim, the completion, the park, the resume, the live count and the lease sweep. */
public interface TokenRepository {

    void add(Token token);

    /**
     * Claims one due READY token: sets it RUNNING with this owner and lease and returns it, or leaves it for another
     * worker when none is due. The claim commits before the node runs.
     */
    Optional<Token> claimNext(String owner, Instant now, Instant leaseUntil);

    /** Completes a claimed token when this owner still holds the lease; false means someone else owns it now. */
    boolean finish(UUID tokenId, String owner, TokenStatus status, Instant now);

    /** Parks a claimed token on a timer or an event; false when this owner no longer holds the lease. */
    boolean park(UUID tokenId, String owner, Instant now);

    /**
     * Makes a WAITING token READY with the branch it resumed on. The update is conditional on WAITING, so the timer and
     * the event racing for one token produce exactly one resume.
     */
    boolean resume(UUID tokenId, String branch, Object payload, Instant now);

    /**
     * Makes a claimed token READY again at a later time, keeping its id and attempt, so the next claim of the same step
     * sends the same idempotency key. The update is conditional on this owner still holding the lease.
     */
    boolean retryLater(UUID tokenId, String owner, Instant availableAt, Instant now);

    /** How many tokens of the instance are still READY, RUNNING or WAITING. */
    long liveCount(UUID instanceId);

    /** How many tokens of the instance are READY or RUNNING, which is what "still moving" means. */
    long activeCount(UUID instanceId);

    /**
     * Abandons every live token of the instance except one, because an {@code any} or {@code n_of} join has fired. A
     * token cancelled mid-call writes nothing when its worker finishes, so its successors are never created.
     */
    int cancelLive(UUID instanceId, UUID exceptTokenId, Instant now);

    /** Makes every token whose lease ran out READY again, and returns how many were taken over. */
    int requeueExpired(Instant now);

    /** The live tokens of one instance, which is what a cancel stops. */
    List<UUID> liveTokenIds(UUID instanceId);

    /** Cancels one live token whatever its status, which is what a cancel does with the ids it read. */
    boolean cancel(UUID tokenId, Instant now);

    /** Re-queues the failed tokens of a dead-lettered instance, optionally only the ones of one node. */
    int requeueFailed(UUID instanceId, String nodeId, Instant now);
}
