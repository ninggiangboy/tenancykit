package dev.ngb.email.sending.application.email.port;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.content.api.render.RenderedEmail;
import dev.ngb.email.shared.EmailKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adds the marketing affordances after rendering: the open pixel, the rewritten click links and the unsubscribe
 * headers. It is a separate seam so the send path is testable without a tracking key and so a transactional email is
 * provably untouched.
 */
public interface TrackingHook {

    /** Variables the template may read before rendering, such as the unsubscribe URL. */
    Map<String, Object> systemVariables(EmailKind kind, UUID emailId, TenantId tenantId);

    /** @param rendered the rendered parts; its html may be rewritten */
    Tracked prepare(RenderedEmail rendered, EmailKind kind, UUID emailId, TenantId tenantId);

    /** Records a link that {@link #prepare} produced, so the click endpoint can find it later. */
    void recordLink(Link link, UUID emailId, TenantId tenantId, Instant now);

    record Tracked(String html, Map<String, String> headers, List<Link> links) {}

    record Link(int position, String url) {}
}
