package dev.ngb.attribution.links.domain.link;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A tracked link: the slug a visitor follows, the destinations it may send them to, and the rules that limit it.
 *
 * <p>Exactly one destination is the fallback, so the redirect always has an answer when no rule passes. A link with a
 * password stores only its hash and never returns it; the password is accepted on write and forgotten. Expiry must be in
 * the future when the link is saved; the redirect re-checks it on every request because time passes.
 */
public record Link(
        UUID id,
        TenantId tenantId,
        UUID campaignId,
        Slug slug,
        LinkKind kind,
        String externalRef,
        LinkStatus status,
        Instant expiresAt,
        Long maxClicks,
        String passwordHash,
        Map<String, String> utm,
        List<Destination> destinations,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_DESTINATIONS = 10;
    public static final int MAX_EXTERNAL_REF = 128;

    public Link {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (maxClicks != null && maxClicks < 1) {
            throw new BadRequestException("A click cap is at least 1.");
        }
        externalRef = externalRef == null || externalRef.isBlank() ? null : externalRef.strip();
        if (externalRef != null && externalRef.length() > MAX_EXTERNAL_REF) {
            throw new BadRequestException("An external reference is at most " + MAX_EXTERNAL_REF + " characters.");
        }
        utm = utm == null ? Map.of() : Map.copyOf(utm);
        destinations = destinations == null ? List.of() : List.copyOf(destinations);
        requireDestinations(destinations);
    }

    public static Link create(
            UUID id,
            TenantId tenantId,
            UUID campaignId,
            Slug slug,
            LinkKind kind,
            String externalRef,
            Instant expiresAt,
            Long maxClicks,
            String passwordHash,
            Map<String, String> utm,
            List<Destination> destinations,
            Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new BadRequestException("A link's expiry is in the future.");
        }
        return new Link(
                id, tenantId, campaignId, slug, kind, externalRef, LinkStatus.ACTIVE, expiresAt, maxClicks,
                passwordHash, utm, destinations, 1, now, now);
    }

    public Link update(
            UUID campaignId,
            String externalRef,
            Instant expiresAt,
            Long maxClicks,
            String passwordHash,
            Map<String, String> utm,
            List<Destination> destinations,
            Instant now) {
        if (status == LinkStatus.ARCHIVED) {
            throw new ConflictException("An archived link can no longer be edited.");
        }
        if (expiresAt != null && expiresAt.isBefore(now)) {
            throw new BadRequestException("A link's expiry is in the future.");
        }
        return new Link(
                id, tenantId, campaignId, slug, kind, externalRef, status, expiresAt, maxClicks, passwordHash,
                utm, destinations, version + 1, createdAt, now);
    }

    public Link pause(Instant now) {
        return transition(LinkStatus.PAUSED, now);
    }

    public Link resume(Instant now) {
        return transition(LinkStatus.ACTIVE, now);
    }

    public Link archive(Instant now) {
        return transition(LinkStatus.ARCHIVED, now);
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    /** The one destination a visitor is sent to when no rule passes. */
    public Destination fallback() {
        return destinations.stream()
                .filter(Destination::fallback)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("a link always has a fallback"));
    }

    private Link transition(LinkStatus next, Instant now) {
        return new Link(
                id, tenantId, campaignId, slug, kind, externalRef, next, expiresAt, maxClicks, passwordHash,
                utm, destinations, version + 1, createdAt, now);
    }

    private static void requireDestinations(List<Destination> destinations) {
        if (destinations.isEmpty()) {
            throw new BadRequestException("A link needs at least one destination.");
        }
        if (destinations.size() > MAX_DESTINATIONS) {
            throw new BadRequestException("A link has at most " + MAX_DESTINATIONS + " destinations.");
        }
        long fallbacks = destinations.stream().filter(Destination::fallback).count();
        if (fallbacks != 1) {
            throw new BadRequestException("A link has exactly one fallback destination.");
        }
        long positions = destinations.stream().map(Destination::position).distinct().count();
        if (positions != destinations.size()) {
            throw new BadRequestException("Two destinations of one link cannot share a position.");
        }
    }

    /** The UTM parameter names, lower-cased, so a caller cannot smuggle a second spelling of one. */
    public Map<String, String> normalizedUtm() {
        Map<String, String> normalized = new java.util.LinkedHashMap<>();
        utm.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return Map.copyOf(normalized);
    }
}
