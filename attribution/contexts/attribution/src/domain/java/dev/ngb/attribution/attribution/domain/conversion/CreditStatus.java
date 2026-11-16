package dev.ngb.attribution.attribution.domain.conversion;

/**
 * Where a conversion stands: waiting for the settle delay, credited to a chain, or unattributed because no human click
 * was inside the window.
 */
public enum CreditStatus {
    PENDING,
    COMPUTED,
    UNATTRIBUTED
}
