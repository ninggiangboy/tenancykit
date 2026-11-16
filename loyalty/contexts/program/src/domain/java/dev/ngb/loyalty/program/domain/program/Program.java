package dev.ngb.loyalty.program.domain.program;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A Tenant's one points program: the currency and what one point costs in it, when a lot expires, how far back the tier
 * evaluation looks, and the zone the promotion rules are evaluated in.
 *
 * <p>It is also the owner of the tier list's version, because a tier change must invalidate every member's tier: the
 * program carries {@code tiersDirty} and bumps its version in the same transaction as the change.
 */
public final class Program {

    private final TenantId tenantId;
    private String currency;
    private long unitAmount;
    private int expiryMonths;
    private int expiryNoticeDays;
    private int tierWindowMonths;
    private ZoneId timeZone;
    private boolean tiersDirty;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Program(
            TenantId tenantId,
            String currency,
            long unitAmount,
            int expiryMonths,
            int expiryNoticeDays,
            int tierWindowMonths,
            ZoneId timeZone,
            boolean tiersDirty,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.unitAmount = unitAmount;
        this.expiryMonths = expiryMonths;
        this.expiryNoticeDays = expiryNoticeDays;
        this.tierWindowMonths = tierWindowMonths;
        this.timeZone = Objects.requireNonNull(timeZone, "timeZone");
        this.tiersDirty = tiersDirty;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A new program. It starts with its tiers owed a re-evaluation, because it has none yet. */
    public static Program create(
            TenantId tenantId,
            String currency,
            long unitAmount,
            int expiryMonths,
            int expiryNoticeDays,
            int tierWindowMonths,
            ZoneId timeZone,
            Instant now) {
        return new Program(tenantId, currency, unitAmount, expiryMonths, expiryNoticeDays, tierWindowMonths, timeZone,
                true, now, now, 1);
    }

    /** The program a repository reads back from storage. */
    public static Program restore(
            TenantId tenantId,
            String currency,
            long unitAmount,
            int expiryMonths,
            int expiryNoticeDays,
            int tierWindowMonths,
            ZoneId timeZone,
            boolean tiersDirty,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Program(tenantId, currency, unitAmount, expiryMonths, expiryNoticeDays, tierWindowMonths, timeZone,
                tiersDirty, createdAt, updatedAt, version);
    }

    /** A full replace of everything a caller may set. The tier list is not part of it, so its flag is left alone. */
    public void change(
            String newCurrency,
            long newUnitAmount,
            int newExpiryMonths,
            int newExpiryNoticeDays,
            int newTierWindowMonths,
            ZoneId newTimeZone,
            Instant now) {
        this.currency = Objects.requireNonNull(newCurrency, "currency");
        this.unitAmount = newUnitAmount;
        this.expiryMonths = newExpiryMonths;
        this.expiryNoticeDays = newExpiryNoticeDays;
        this.tierWindowMonths = newTierWindowMonths;
        this.timeZone = Objects.requireNonNull(newTimeZone, "timeZone");
        this.updatedAt = now;
        this.version++;
    }

    /** Records that the tier list changed, so every member's tier has to be computed again by the evaluation run. */
    public void markTiersDirty(Instant now) {
        this.tiersDirty = true;
        this.updatedAt = now;
        this.version++;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String currency() {
        return currency;
    }

    public long unitAmount() {
        return unitAmount;
    }

    public int expiryMonths() {
        return expiryMonths;
    }

    public int expiryNoticeDays() {
        return expiryNoticeDays;
    }

    public int tierWindowMonths() {
        return tierWindowMonths;
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public boolean tiersDirty() {
        return tiersDirty;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
