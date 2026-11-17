package dev.ngb.attribution.spend.domain.entry;

/** Where a cost row came from. An import writes only its own source, so a manual entry is never overwritten. */
public enum SpendSource {
    MANUAL,
    GOOGLE_ADS,
    META_ADS
}
