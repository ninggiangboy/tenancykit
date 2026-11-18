package dev.ngb.attribution.tracking.api.chain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The touchpoints that led to one conversion, read from the stored clicks and the visitor's bindings.
 *
 * <p>This is the one thing the attribution context needs from tracking: the clicks a visitor met inside a window, plus
 * the conversion's own click. The chain is read here and then snapshotted on the conversion, so a recompute after the
 * raw events age out still reproduces the same credits.
 */
public interface TouchpointReader {

    /**
     * The human clicks bound to a visitor inside the window before a conversion, oldest first, plus the conversion's own
     * click if it has one. Bots and suspect clicks are excluded, and a click after the conversion is never included.
     *
     * @param clickId the conversion's own click, or null when it names only a visitor
     * @param visitorId the consented visitor id, or null when there is none
     * @param since the earliest instant a click may have happened
     * @param until the conversion instant; a click after it is never credited
     */
    List<Touchpoint> chain(UUID clickId, String visitorId, Instant since, Instant until);

    /**
     * One click in the chain.
     *
     * @param human whether the click classified as a person; a bot or suspect click is never credited
     */
    record Touchpoint(UUID clickId, UUID linkId, UUID campaignId, Instant at, boolean human) {}
}
