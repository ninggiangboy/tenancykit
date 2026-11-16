package dev.ngb.attribution.attribution.domain.conversion;

import dev.ngb.attribution.attribution.domain.credit.CreditEngine;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.id.TenantId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One goal reached: a value, a currency, the click or visitor it is named for, and — once credit is computed — the
 * touch chain it was credited along and the campaign that owned it.
 *
 * <p>The chain is snapshotted here the first time credit is computed, so a recompute after the raw click partition is
 * dropped still reproduces the result. It is never re-derived after that, which is what makes credit reproducible: the
 * same snapshot and the same model always give the same credits.
 *
 * @param touchChain the chain, oldest first, as {@link CreditEngine.Touch} values once computed; empty while pending
 * @param owningCampaignId the campaign of the last click in the chain, which governs the model and window
 */
public record Conversion(
        UUID id,
        TenantId tenantId,
        UUID goalId,
        UUID clickId,
        String visitorId,
        BigDecimal value,
        String currency,
        Instant occurredAt,
        ConversionSource source,
        String idempotencyKey,
        CreditStatus creditStatus,
        UUID owningCampaignId,
        List<CreditEngine.Touch> touchChain,
        Instant computedAt,
        Instant createdAt) {

    public static final int MAX_CHAIN = 200;
    public static final BigDecimal MAX_VALUE = new BigDecimal("999999999999999.9999");

    public Conversion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(creditStatus, "creditStatus");
        Objects.requireNonNull(createdAt, "createdAt");
        if (value.signum() < 0) {
            throw new BadRequestException("A conversion value is not negative.");
        }
        if (value.compareTo(MAX_VALUE) > 0) {
            throw new BadRequestException("A conversion value is too large.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("A conversion needs an idempotency key.");
        }
        // A conversion with neither a click nor a visitor is legal: it is a postback whose token did not verify, which
        // the plan stores as UNATTRIBUTED rather than rejecting. What is illegal is an idempotency key with no goal.
        touchChain = touchChain == null ? List.of() : List.copyOf(touchChain);
        if (touchChain.size() > MAX_CHAIN) {
            throw new BadRequestException("A touch chain holds at most " + MAX_CHAIN + " clicks.");
        }
    }

    /**
     * A conversion as it arrives, before credit is computed.
     *
     * @param clickId the click the token proved, or null when the conversion names only a visitor
     */
    public static Conversion pending(
            UUID id,
            TenantId tenantId,
            UUID goalId,
            UUID clickId,
            String visitorId,
            BigDecimal value,
            String currency,
            Instant occurredAt,
            ConversionSource source,
            String idempotencyKey,
            Instant now) {
        return new Conversion(
                id, tenantId, goalId, clickId, blankToNull(visitorId), value, currency, occurredAt, source,
                idempotencyKey, CreditStatus.PENDING, null, List.of(), null, now);
    }

    /**
     * The same conversion with its chain snapshotted and its credit settled.
     *
     * @param result the engine's outcome; an empty credit list makes this {@code UNATTRIBUTED}
     */
    public Conversion settle(List<CreditEngine.Touch> chain, CreditEngine.Result result, Instant now) {
        return new Conversion(
                id, tenantId, goalId, clickId, visitorId, value, currency, occurredAt, source, idempotencyKey,
                result.attributed() ? CreditStatus.COMPUTED : CreditStatus.UNATTRIBUTED,
                campaignIdOrNull(result.owningCampaignId()), chain, now, createdAt);
    }

    /**
     * The engine names a campaign as the string its vector file uses; the row stores a uuid. A case in the contract is
     * free-form, so an id that is not a uuid is treated as no owning campaign rather than failing the conversion.
     */
    private static UUID campaignIdOrNull(String campaignId) {
        if (campaignId == null || campaignId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(campaignId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The same conversion with the person removed and its amount kept, which is what an erasure does. */
    public Conversion erasePerson(Instant now) {
        return new Conversion(
                id, tenantId, goalId, null, null, value, currency, occurredAt, source, idempotencyKey,
                creditStatus, owningCampaignId, touchChain, computedAt, createdAt);
    }

    /** The chain this conversion already snapshot, so a recompute reuses it rather than re-deriving from raw events. */
    public List<CreditEngine.Touch> snapshot() {
        return touchChain;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
