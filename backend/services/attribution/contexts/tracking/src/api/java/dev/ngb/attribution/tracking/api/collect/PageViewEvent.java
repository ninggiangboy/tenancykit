package dev.ngb.attribution.tracking.api.collect;

import java.time.Instant;
import java.util.UUID;

/**
 * One page view the snippet reported, already validated and stripped of anything that identifies the person unless the
 * visitor consented.
 *
 * <p>The path never carries a query string or a fragment, so a landing page's own parameters are not stored. The visitor
 * and session ids are absent when consent was false, which is what makes "no cookie before consent" true at the storage
 * boundary rather than only in the client.
 *
 * @param eventId the event's identity, so a replayed batch writes nothing twice
 * @param tenantId the tenant the host resolved to
 * @param clickId the click the page view arrived from, verified against the signed token before it got here
 * @param visitorId the consented visitor's id, absent without consent
 * @param sessionId the consented session's id, absent without consent
 * @param path the page's path only, at most 2,048 characters
 * @param bot whether the request's user agent classified as a machine
 */
public record PageViewEvent(
        UUID eventId,
        dev.ngb.core.id.TenantId tenantId,
        UUID clickId,
        String visitorId,
        String sessionId,
        String path,
        Instant occurredAt,
        boolean bot) {

    public PageViewEvent {
        path = path == null ? "/" : path;
        visitorId = blankToNull(visitorId);
        sessionId = blankToNull(sessionId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
