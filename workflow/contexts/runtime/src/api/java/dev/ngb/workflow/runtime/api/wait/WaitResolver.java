package dev.ngb.workflow.runtime.api.wait;

import java.time.Instant;

/**
 * What another context tells the runtime when an event arrives: which source sent it, its type, its envelope, and when
 * it was received. Every open wait that accepts it resumes inside the caller's transaction, so an event and the waits it
 * satisfies commit together.
 */
public interface WaitResolver {

    /**
     * Resumes every open wait that accepts the event.
     *
     * @return how many tokens were resumed; zero is normal when nothing was waiting
     */
    int resolve(String source, String eventType, Object document, Instant receivedAt);
}
