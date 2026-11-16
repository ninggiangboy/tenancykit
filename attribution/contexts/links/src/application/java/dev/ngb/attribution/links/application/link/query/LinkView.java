package dev.ngb.attribution.links.application.link.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One link as the API shows it, with its destinations and never its password hash. */
public record LinkView(
        UUID id,
        UUID campaignId,
        String slug,
        String kind,
        String externalRef,
        String status,
        String url,
        Instant expiresAt,
        Long maxClicks,
        boolean hasPassword,
        Map<String, String> utm,
        List<DestinationView> destinations,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** One destination of a link as the API shows it. */
    public record DestinationView(
            UUID id,
            String url,
            int position,
            int weight,
            boolean fallback,
            List<String> devices,
            List<String> countries) {}
}
