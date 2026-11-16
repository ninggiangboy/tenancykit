package dev.ngb.loyalty.program.domain.promotion;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A rule that multiplies what a matching line earns. Its window may be open at either end, and an archived promotion is
 * kept as history: it no longer applies and no longer counts against the tenant's cap.
 */
public final class Promotion {

    private final TenantId tenantId;
    private final PromotionId id;
    private String name;
    private int priority;
    private PromotionStatus status;
    private BigDecimal multiplier;
    private Instant startsAt;
    private Instant endsAt;
    private PromotionConditions conditions;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Promotion(
            TenantId tenantId,
            PromotionId id,
            String name,
            int priority,
            PromotionStatus status,
            BigDecimal multiplier,
            Instant startsAt,
            Instant endsAt,
            PromotionConditions conditions,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.priority = priority;
        this.status = Objects.requireNonNull(status, "status");
        this.multiplier = Objects.requireNonNull(multiplier, "multiplier");
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static Promotion create(
            TenantId tenantId,
            PromotionId id,
            String name,
            int priority,
            PromotionStatus status,
            BigDecimal multiplier,
            Instant startsAt,
            Instant endsAt,
            PromotionConditions conditions,
            Instant now) {
        return new Promotion(tenantId, id, name, priority, status, multiplier, startsAt, endsAt, conditions, now, now, 1);
    }

    public static Promotion restore(
            TenantId tenantId,
            PromotionId id,
            String name,
            int priority,
            PromotionStatus status,
            BigDecimal multiplier,
            Instant startsAt,
            Instant endsAt,
            PromotionConditions conditions,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Promotion(tenantId, id, name, priority, status, multiplier, startsAt, endsAt, conditions, createdAt,
                updatedAt, version);
    }

    /** A full replace of everything a caller may change; the caller has already validated the merged result. */
    public void reconfigure(
            String newName,
            int newPriority,
            PromotionStatus newStatus,
            BigDecimal newMultiplier,
            Instant newStartsAt,
            Instant newEndsAt,
            PromotionConditions newConditions,
            Instant now) {
        this.name = Objects.requireNonNull(newName, "name");
        this.priority = newPriority;
        this.status = Objects.requireNonNull(newStatus, "status");
        this.multiplier = Objects.requireNonNull(newMultiplier, "multiplier");
        this.startsAt = newStartsAt;
        this.endsAt = newEndsAt;
        this.conditions = Objects.requireNonNull(newConditions, "conditions");
        this.updatedAt = now;
        this.version++;
    }

    public boolean archived() {
        return status == PromotionStatus.ARCHIVED;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public PromotionId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int priority() {
        return priority;
    }

    public PromotionStatus status() {
        return status;
    }

    public BigDecimal multiplier() {
        return multiplier;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public PromotionConditions conditions() {
        return conditions;
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
