package dev.ngb.attribution.attribution.domain.conversion;

/** How the conversion reached the app: the Tenant's backend (the postback) or the site snippet. */
public enum ConversionSource {
    POSTBACK,
    SNIPPET
}
