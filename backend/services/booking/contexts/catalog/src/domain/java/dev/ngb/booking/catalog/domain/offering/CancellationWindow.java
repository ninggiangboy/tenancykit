package dev.ngb.booking.catalog.domain.offering;

/**
 * One cancellation threshold of an offering: how long before the start the Tenant's code applies. booking stores only
 * the code; the amount behind it belongs to the Tenant's own rules and to Billing.
 */
public record CancellationWindow(int minutesBeforeStart, String code) {

    public CancellationWindow {
        if (minutesBeforeStart < 0) {
            throw new IllegalArgumentException("minutesBeforeStart must be at least 0");
        }
        String value = code == null ? null : code.strip();
        if (value == null || value.isEmpty() || value.length() > 60) {
            throw new IllegalArgumentException("code must be between 1 and 60 characters");
        }
        code = value;
    }
}
