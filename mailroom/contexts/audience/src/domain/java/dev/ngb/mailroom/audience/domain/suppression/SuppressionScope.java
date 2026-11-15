package dev.ngb.mailroom.audience.domain.suppression;

/** How much a suppression blocks. A consent withdrawal is MARKETING; only an explicit block is ALL. */
public enum SuppressionScope {
    MARKETING,
    ALL
}
