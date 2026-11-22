package dev.ngb.attribution.links.application.redirect.service;

import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkStatus;
import dev.ngb.attribution.links.domain.redirect.DestinationSelector;
import dev.ngb.core.visitor.DeviceClass;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * The redirect's decision, with no HTTP and no persistence port of its own: the cache is supplied, and a capped link's
 * counter is a single conditional update through the link repository.
 *
 * <p>The order of checks is the contract. Unknown, archived or paused is {@code NOT_FOUND}; expired or cap reached is
 * {@code GONE}; a password-protected link is {@code PASSWORD_REQUIRED} until the password is verified. Only an ACTIVE,
 * unexpired link under its cap reaches the point where a click is spent, and a second click is never spent for a
 * request that will not redirect.
 *
 * <p>A capped link is the one link that touches the database on the redirect path, because its cap must be exact under
 * concurrent requests: a conditional {@code update … where clicks_counted < cap} returns zero rows once the cap is
 * reached and the answer becomes {@code GONE}. Every other link is answered from memory alone.
 */
public class RedirectDecider {

    /** What the redirect answers, before it becomes an HTTP status. */
    public sealed interface Decision {
        /** Send the visitor to this destination. */
        record Redirect(Link link, Destination destination) implements Decision {}

        /** Unknown, archived, paused, or the tenant has no entitlement. */
        record NotFound() implements Decision {}

        /** The link is expired or has reached its click cap. */
        record Gone() implements Decision {}

        /** The link is password-protected and no password has been verified yet. */
        record PasswordRequired(Link link) implements Decision {}
    }

    private final LinkRepository links;
    private final RandomGenerator random;

    public RedirectDecider(LinkRepository links) {
        this(links, new Random());
    }

    public RedirectDecider(LinkRepository links, RandomGenerator random) {
        this.links = links;
        this.random = random;
    }

    /**
     * Decide one redirect.
     *
     * @param link the link the cache resolved, already scoped to the host's tenant
     * @param device the visitor's classified device class name
     * @param country the visitor's country, or null/blank when GeoIP had none
     * @param now the instant the redirect answers
     */
    public Decision decide(Link link, DeviceClass device, String country, Instant now) {
        if (link.status() != LinkStatus.ACTIVE) {
            // A paused or archived link is a 404, the same answer as an unknown slug, so a probe learns nothing about it.
            return new Decision.NotFound();
        }
        if (link.expiresAt() != null && !link.expiresAt().isAfter(now)) {
            return new Decision.Gone();
        }
        if (link.hasPassword()) {
            // The password is verified by the unlock path; a GET of a protected link only says one is needed.
            return new Decision.PasswordRequired(link);
        }
        return spendAndSelect(link, device, country);
    }

    /**
     * Choose the destination for a link already known to be live, spending one click if the link is capped.
     *
     * <p>Shared by the GET path and the unlock path, so a password-protected link spends its click only when the visitor
     * actually gets a destination.
     */
    public Decision spendAndSelect(Link link, DeviceClass device, String country) {
        if (link.maxClicks() != null && !links.incrementIfBelow(link.id(), link.maxClicks())) {
            return new Decision.Gone();
        }
        Destination destination = DestinationSelector.select(link, device, country, random);
        return new Decision.Redirect(link, destination);
    }

    /**
     * Decide without spending anything, for a {@code HEAD} or a link preview's probe.
     *
     * <p>It answers where a {@code GET} would send a visitor, including the cap: a link already at its cap is {@code
     * GONE}. The one difference is that the click counter is not updated, so a probe never eats a capped link's last
     * click.
     */
    public Decision peek(Link link, DeviceClass device, String country, Instant now) {
        if (link.status() != LinkStatus.ACTIVE) {
            return new Decision.NotFound();
        }
        if (link.expiresAt() != null && !link.expiresAt().isAfter(now)) {
            return new Decision.Gone();
        }
        if (link.maxClicks() != null && links.clicksCounted(link.id()) >= link.maxClicks()) {
            return new Decision.Gone();
        }
        if (link.hasPassword()) {
            return new Decision.PasswordRequired(link);
        }
        return new Decision.Redirect(link, DestinationSelector.select(link, device, country, random));
    }

    /** The password hash for an unlock attempt, or empty when the link has none or is no longer live. */
    public Optional<String> passwordHashOf(Link link, Instant now) {
        if (!link.hasPassword() || link.status() != LinkStatus.ACTIVE) {
            return Optional.empty();
        }
        if (link.expiresAt() != null && !link.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(link.passwordHash());
    }
}
