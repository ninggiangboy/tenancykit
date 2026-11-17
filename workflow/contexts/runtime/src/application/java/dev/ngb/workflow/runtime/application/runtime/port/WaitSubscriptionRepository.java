package dev.ngb.workflow.runtime.application.runtime.port;

import dev.ngb.workflow.runtime.domain.wait.WaitSubscription;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The subscriptions a token parks on, and the conditional closes that make one resume happen once. */
public interface WaitSubscriptionRepository {

    void add(WaitSubscription subscription);

    /** The open subscriptions an event of this source and type may resume. */
    List<WaitSubscription> matching(String source, String eventType, Instant now);

    /** Closes a subscription when it is still open; false means another side already resolved it. */
    boolean resolve(UUID subscriptionId, Instant now);

    /** Closes every open subscription of a token, so a timeout leaves no event able to resume it. */
    int resolveForToken(UUID tokenId, Instant now);
}
