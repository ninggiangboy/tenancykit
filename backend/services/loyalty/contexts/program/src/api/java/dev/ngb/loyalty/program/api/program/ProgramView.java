package dev.ngb.loyalty.program.api.program;

import java.time.ZoneId;
import java.util.Objects;

/**
 * The Tenant's one points program, as another context reads it: the currency and its point rate, when a lot expires,
 * how far back the tier evaluation looks and the zone the promotion rules are evaluated in.
 *
 * <p>Nothing earns until this exists. {@code unitAmount} is minor units of {@code currency} that earn one point, for
 * example 1000. {@code version} is the program's optimistic-lock version.
 */
public record ProgramView(
        String currency,
        long unitAmount,
        int expiryMonths,
        int expiryNoticeDays,
        int tierWindowMonths,
        ZoneId timeZone,
        long version) {

    public ProgramView {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(timeZone, "timeZone");
        if (unitAmount < 1) {
            throw new IllegalArgumentException("unitAmount must be at least 1");
        }
        if (expiryMonths < 1 || expiryMonths > 120) {
            throw new IllegalArgumentException("expiryMonths must be within 1-120");
        }
        if (expiryNoticeDays < 0 || expiryNoticeDays > 365) {
            throw new IllegalArgumentException("expiryNoticeDays must be within 0-365");
        }
        if (tierWindowMonths < 1 || tierWindowMonths > 60) {
            throw new IllegalArgumentException("tierWindowMonths must be within 1-60");
        }
    }

    /** Whether an expiring lot is announced ahead of its expiry at all: a notice window of 0 turns the notice off. */
    public boolean announcesExpiry() {
        return expiryNoticeDays > 0;
    }
}
