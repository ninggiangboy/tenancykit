package dev.ngb.attribution.tracking.api.ingest;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.visitor.DeviceClass;
import dev.ngb.core.visitor.TrafficClass;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One click the redirect produced, as the redirect hands it to the ingest buffer.
 *
 * <p>It carries no address and no user agent: device, browser, country and traffic class are already classified, and
 * {@code visitorHash} is the daily HMAC a click and a page view can share only inside one UTC day. That is the whole of
 * what a stored row may say about the person, so the wire schema and the table are the same set of facts.
 *
 * @param eventId the event's identity, so a redelivered batch writes nothing twice
 * @param tenantId the tenant the host resolved to
 * @param clickId the click's identity, which the signed token in the destination carries back
 * @param destinationId the destination the redirect chose, so a report can break clicks down by it
 * @param campaignId the campaign of the link, absent for a link outside a campaign
 * @param referrerDomain the referrer's registrable domain, absent when there was none
 * @param entry the {@code e} entry label the redirect received, absent when it received none
 */
public record ClickEvent(
        UUID eventId,
        TenantId tenantId,
        UUID clickId,
        UUID linkId,
        UUID destinationId,
        UUID campaignId,
        Instant occurredAt,
        DeviceClass deviceClass,
        String browserFamily,
        String country,
        String referrerDomain,
        String entry,
        TrafficClass trafficClass,
        String visitorHash) {

    public ClickEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(clickId, "clickId");
        Objects.requireNonNull(linkId, "linkId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(deviceClass, "deviceClass");
        Objects.requireNonNull(trafficClass, "trafficClass");
        Objects.requireNonNull(visitorHash, "visitorHash");
        browserFamily = bounded(browserFamily, 32);
        country = bounded(country, 2);
        referrerDomain = bounded(referrerDomain, 253);
        entry = bounded(entry, 32);
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
