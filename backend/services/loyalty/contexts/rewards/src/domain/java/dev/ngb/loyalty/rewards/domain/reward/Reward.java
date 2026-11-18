package dev.ngb.loyalty.rewards.domain.reward;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * A catalog entry a member exchanges points for. Redeeming it spends the points and issues one voucher; loyalty never
 * fulfils the reward itself, because what a reward grants lives in the Tenant's own system.
 *
 * <p>Its validity window may be open at either end, its stock may be unlimited (null) and an archived reward is kept as
 * history: it can no longer be redeemed, but the vouchers it already issued stay readable. A change of cost or stock
 * never reaches a voucher already issued.
 */
public final class Reward {

    private final TenantId tenantId;
    private final RewardId id;
    private String name;
    private String description;
    private long pointsCost;
    private Long stock;
    private RewardStatus status;
    private Instant validFrom;
    private Instant validTo;
    private int voucherValidDays;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Reward(
            TenantId tenantId,
            RewardId id,
            String name,
            String description,
            long pointsCost,
            Long stock,
            RewardStatus status,
            Instant validFrom,
            Instant validTo,
            int voucherValidDays,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.pointsCost = pointsCost;
        this.stock = stock;
        this.status = Objects.requireNonNull(status, "status");
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.voucherValidDays = voucherValidDays;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static Reward create(
            TenantId tenantId,
            RewardId id,
            String name,
            String description,
            long pointsCost,
            Long stock,
            RewardStatus status,
            Instant validFrom,
            Instant validTo,
            int voucherValidDays,
            Instant now) {
        return new Reward(tenantId, id, name, description, pointsCost, stock, status, validFrom, validTo,
                voucherValidDays, now, now, 1);
    }

    public static Reward restore(
            TenantId tenantId,
            RewardId id,
            String name,
            String description,
            long pointsCost,
            Long stock,
            RewardStatus status,
            Instant validFrom,
            Instant validTo,
            int voucherValidDays,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Reward(tenantId, id, name, description, pointsCost, stock, status, validFrom, validTo,
                voucherValidDays, createdAt, updatedAt, version);
    }

    /** A full replace of everything a caller may change; the caller has already validated the merged result. */
    public void reconfigure(
            String newName,
            String newDescription,
            long newPointsCost,
            Long newStock,
            RewardStatus newStatus,
            Instant newValidFrom,
            Instant newValidTo,
            int newVoucherValidDays,
            Instant now) {
        this.name = Objects.requireNonNull(newName, "name");
        this.description = newDescription;
        this.pointsCost = newPointsCost;
        this.stock = newStock;
        this.status = Objects.requireNonNull(newStatus, "status");
        this.validFrom = newValidFrom;
        this.validTo = newValidTo;
        this.voucherValidDays = newVoucherValidDays;
        this.updatedAt = now;
        this.version++;
    }

    public boolean archived() {
        return status == RewardStatus.ARCHIVED;
    }

    /** Whether the reward is a catalog entry a member may redeem at {@code instant}: active and inside its window. */
    public boolean redeemableAt(Instant instant) {
        if (status != RewardStatus.ACTIVE) {
            return false;
        }
        if (validFrom != null && instant.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !instant.isAfter(validTo);
    }

    /** Whether the catalog still holds units of it; an unlimited stock always does. */
    public boolean inStock() {
        return stock == null || stock > 0;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public RewardId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public long pointsCost() {
        return pointsCost;
    }

    public Long stock() {
        return stock;
    }

    public RewardStatus status() {
        return status;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validTo() {
        return validTo;
    }

    public int voucherValidDays() {
        return voucherValidDays;
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
