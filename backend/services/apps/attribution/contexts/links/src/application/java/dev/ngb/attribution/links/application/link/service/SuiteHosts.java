package dev.ngb.attribution.links.application.link.service;

import java.util.List;

/**
 * The suite's own hosts and its public base URL, both built from configuration. A destination that points back at the
 * suite is refused, and a personal link's public URL is written against the base URL; neither is a domain fact.
 */
public class SuiteHosts {

    private final List<String> forbidden;
    private final String publicBaseUrl;

    public SuiteHosts(List<String> forbidden, String publicBaseUrl) {
        this.forbidden = forbidden == null ? List.of() : List.copyOf(forbidden);
        this.publicBaseUrl = publicBaseUrl == null ? "" : stripTrailingSlash(publicBaseUrl);
    }

    /** The suite's base domain and every Tenant host, each compared case-insensitively as a suffix. */
    public List<String> forbidden() {
        return forbidden;
    }

    /** Where {@code /go/<slug>} is served, without a trailing slash. */
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
