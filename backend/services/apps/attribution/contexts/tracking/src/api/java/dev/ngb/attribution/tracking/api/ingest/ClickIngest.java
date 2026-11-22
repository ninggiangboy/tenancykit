package dev.ngb.attribution.tracking.api.ingest;

/**
 * The redirect offers a click here and never waits for the broker.
 *
 * <p>Measurement must never fail or slow a redirect, so {@link #offer} is a non-blocking hand-off: the implementation
 * puts the event in a bounded buffer and a background publisher sends batches to the ingest exchange. When the buffer
 * is full the oldest event is dropped and counted rather than the redirect blocking or failing.
 */
public interface ClickIngest {

    /** @return false when the event was dropped because the buffer was full */
    boolean offer(ClickEvent event);
}
