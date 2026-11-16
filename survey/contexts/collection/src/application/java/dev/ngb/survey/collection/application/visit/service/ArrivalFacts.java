package dev.ngb.survey.collection.application.visit.service;

import dev.ngb.core.visitor.BoundedParams;
import dev.ngb.core.visitor.GeoCountryReader;
import dev.ngb.core.visitor.ReferrerDomain;
import dev.ngb.core.visitor.UserAgentClassifier;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase.ArrivalRequest;
import dev.ngb.survey.collection.domain.visit.Arrival;
import java.net.InetAddress;

/**
 * Reduces what a request carried into the bounded, non-identifying facts a visit stores.
 *
 * <p>The client address is used only to resolve a country, the user agent only to name a device class, a browser family
 * and whether it looked like a crawler, and the referrer only to keep its registrable domain. The query string keeps
 * at most the five UTM labels, each bounded. Whatever a hostile client sends, this returns a value rather than
 * throwing: a link opening must never fail because a parameter was odd.
 */
public class ArrivalFacts {

    private final GeoCountryReader geo;

    public ArrivalFacts(GeoCountryReader geo) {
        this.geo = geo;
    }

    /**
     * @param source the link's own source, which a visit keeps even when the browser's labels say something else
     * @param request what the browser sent
     */
    public Arrival describe(String source, ArrivalRequest request) {
        UserAgentClassifier.Classification agent = UserAgentClassifier.classify(request.userAgent());
        return new Arrival(
                source,
                BoundedParams.utm(request.query()),
                ReferrerDomain.of(request.referrer()).orElse(null),
                agent.device().name(),
                agent.browserFamily(),
                country(request.clientAddress()),
                agent.traffic().name());
    }

    /** The country of an address, or null when the database or the address has none. */
    private String country(String clientAddress) {
        if (clientAddress == null || clientAddress.isBlank() || !geo.available()) {
            return null;
        }
        try {
            return geo.country(InetAddress.getByName(clientAddress))
                    .map(GeoCountryReader.Country::code)
                    .orElse(null);
        } catch (Exception unknownAddress) {
            // A malformed or unparsable address simply contributes no country.
            return null;
        }
    }

    /** Whether the GeoIP database is loaded, which a health detail reports. */
    public boolean geoAvailable() {
        return geo.available();
    }

    /** The database's build date, or null when none was loaded. */
    public String geoBuildDate() {
        return geo.buildDate().orElse(null);
    }
}
