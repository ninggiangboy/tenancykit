package dev.ngb.attribution.links.adapter.redirect.web;

import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.DailyVisitorHash;
import dev.ngb.core.visitor.GeoCountryReader;
import dev.ngb.core.visitor.ReferrerDomain;
import dev.ngb.core.visitor.TrafficClass;
import dev.ngb.core.visitor.UserAgentClassifier;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns one redirect request into the click it produces.
 *
 * <p>This is where the raw material of a click is read and immediately forgotten: the user agent becomes a device class,
 * a browser family and a traffic class, the referrer a registrable domain, and the address plus user agent a daily hash.
 * Nothing here logs, returns or stores the address or the agent, and the outgoing {@link ClickEvent} carries only the
 * classified facts. The visitor hash is what a click and a page view share inside a day, so it is the one value the
 * collection endpoint can later match on.
 *
 * <p>Classification is defensive by construction: a missing address, an unreadable GeoIP file or a hostile referrer
 * yields an absent value, never an exception, because a redirect must not fail on analytics.
 */
public class ClickFactory {

    private final Clock clock;
    private final IdGenerator ids;
    private final TenantContext tenantContext;
    private final DailyVisitorHash visitorHash;
    private final GeoCountryReader countries;

    public ClickFactory(
            Clock clock, IdGenerator ids, TenantContext tenantContext, DailyVisitorHash visitorHash,
            GeoCountryReader countries) {
        this.clock = clock;
        this.ids = ids;
        this.tenantContext = tenantContext;
        this.visitorHash = visitorHash;
        this.countries = countries;
    }

    /** The click id a redirect mints before it knows the destination, so the token can name it. */
    public UUID newClickId() {
        return ids.newId();
    }

    /** The daily visitor hash of this request, which the public limits and the suspect counter key on. */
    public String visitorHash(HttpServletRequest request) {
        return visitorHash.of(
                tenantContext.requireTenant().value().toString(),
                clientAddress(request),
                request.getHeader("User-Agent"),
                clock.now());
    }

    /** The visitor's country, or null when GeoIP is unavailable or has no entry for the address. */
    public String countryOf(HttpServletRequest request) {
        if (!countries.available()) {
            return null;
        }
        return address(request)
                .flatMap(countries::country)
                .map(GeoCountryReader.Country::code)
                .orElse(null);
    }

    /**
     * Build the event for a click that is about to redirect.
     *
     * @param request the visitor's request
     * @param link the link being followed
     * @param destination the destination the selector chose
     * @param clickId the click id already signed into the token
     * @param entry the {@code e} entry label, or null
     * @param suspect whether the per-link rate classified this click as suspect
     */
    public ClickEvent click(
            HttpServletRequest request, Link link, Destination destination, UUID clickId, String entry,
            boolean suspect) {
        UserAgentClassifier.Classification classification =
                UserAgentClassifier.classify(request.getHeader("User-Agent"));
        TrafficClass traffic = suspect ? TrafficClass.SUSPECT : classification.traffic();
        return new ClickEvent(
                ids.newId(),
                tenantContext.requireTenant(),
                clickId,
                link.id(),
                destination.id(),
                link.campaignId(),
                clock.now(),
                classification.device(),
                classification.browserFamily(),
                countryOf(request),
                ReferrerDomain.of(request.getHeader("Referer")).orElse(null),
                entry,
                traffic,
                visitorHash(request));
    }

    /** The tenant's click parameter name, so the redirect appends the token under the name the Tenant chose. */
    public static String clickParam(ClickParamProvider provider) {
        String parameter = provider.current();
        return parameter == null || parameter.isBlank() ? "aid" : parameter;
    }

    private static Optional<InetAddress> address(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(remote));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    /**
     * The client address as the request carries it.
     *
     * <p>The proxy in front of the app is the suite's own, so the socket address is the peer; a forwarded header is not
     * trusted here because a client could set it. That makes the daily hash a property of the proxy's peer address
     * unless the proxy is configured to pass the address through, which is documented for the deployment.
     */
    private static String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }
}
