package dev.ngb.survey.collection.domain.visit;

import java.util.Map;

/**
 * What one request contributes to a visit, already reduced to bounded, non-identifying facts.
 *
 * <p>The reduction happens before this record exists: the client address is turned into a country, the user agent into
 * a device class, a browser family and a traffic class, the referrer into its registrable domain, and the query string
 * into at most five UTM labels. Nothing here can be used to identify the person, and nothing here is ever logged.
 *
 * @param country the ISO country the address resolved to, or null when the database is missing or has no answer
 * @param trafficClass {@code HUMAN} or {@code BOT}; a bot is excluded from the funnel's counts
 */
public record Arrival(
        String source,
        Map<String, String> utm,
        String referrerDomain,
        String deviceClass,
        String browserFamily,
        String country,
        String trafficClass) {

    public Arrival {
        utm = Map.copyOf(utm);
    }
}
