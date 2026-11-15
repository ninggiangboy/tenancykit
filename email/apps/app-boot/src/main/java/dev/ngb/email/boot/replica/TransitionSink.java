package dev.ngb.email.boot.replica;

/**
 * What the replica handler calls once an event has been applied. The dispatch use case is the one production sink;
 * the list is injectable so the replica can be tested with none, and so a second consumer would not need the handler
 * changed.
 */
@FunctionalInterface
public interface TransitionSink {

    /** Runs inside the dispatcher's transaction, after the upsert; throwing rolls the whole event back. */
    void after(Transition transition);
}
