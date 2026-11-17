package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.token.Token;
import dev.ngb.workflow.runtime.domain.token.TokenStatus;
import java.time.Instant;
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

    /** How many tokens of the instance are still READY, RUNNING or WAITING. */
    long liveCount(UUID instanceId);

    /** How many tokens of the instance are READY or RUNNING, which is what "still moving" means. */
    long activeCount(UUID instanceId);

    /** Makes every token whose lease ran out READY again, and returns how many were taken over. */
    int requeueExpired(Instant now);
}
