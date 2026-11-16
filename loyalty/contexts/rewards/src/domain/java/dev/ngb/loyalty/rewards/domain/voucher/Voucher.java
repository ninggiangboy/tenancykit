package dev.ngb.loyalty.rewards.domain.voucher;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single-use voucher, issued by a reward redemption. It is consumed at most once, by a caller that names its own
 * reference for the use, and it stops being usable when the redemption is reversed or when it expires.
 *
 * <p>Expiry is not a state: the voucher keeps the instant it expires at and a read derives from the current instant
 * whether it is still usable, so no job has to move a row along. {@code externalRef} is the member's reference copied at
 * issue time, because this context reads no member row.
 */
public final class Voucher {

    private final TenantId tenantId;
    private final VoucherId id;
    private final String code;
    private final UUID rewardId;
    private final UUID memberId;
    private final UUID redemptionId;
    private final String externalRef;
    private VoucherStatus status;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant usedAt;
    private String useRef;
    private VoidReason voidReason;

    private Voucher(
            TenantId tenantId,
            VoucherId id,
            String code,
            UUID rewardId,
            UUID memberId,
            UUID redemptionId,
            String externalRef,
            VoucherStatus status,
            Instant issuedAt,
            Instant expiresAt,
            Instant usedAt,
            String useRef,
            VoidReason voidReason) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.rewardId = Objects.requireNonNull(rewardId, "rewardId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.redemptionId = Objects.requireNonNull(redemptionId, "redemptionId");
        this.externalRef = externalRef;
        this.status = Objects.requireNonNull(status, "status");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.usedAt = usedAt;
        this.useRef = useRef;
        this.voidReason = voidReason;
    }

    /** A voucher issued just now, usable until {@code expiresAt}. */
    public static Voucher issued(
            TenantId tenantId,
            VoucherId id,
            String code,
            UUID rewardId,
            UUID memberId,
            UUID redemptionId,
            String externalRef,
            Instant issuedAt,
            Instant expiresAt) {
        return new Voucher(tenantId, id, code, rewardId, memberId, redemptionId, externalRef, VoucherStatus.ISSUED,
                issuedAt, expiresAt, null, null, null);
    }

    public static Voucher restore(
            TenantId tenantId,
            VoucherId id,
            String code,
            UUID rewardId,
            UUID memberId,
            UUID redemptionId,
            String externalRef,
            VoucherStatus status,
            Instant issuedAt,
            Instant expiresAt,
            Instant usedAt,
            String useRef,
            VoidReason voidReason) {
        return new Voucher(tenantId, id, code, rewardId, memberId, redemptionId, externalRef, status, issuedAt,
                expiresAt, usedAt, useRef, voidReason);
    }

    /** Records the use a caller asked for; the caller has already refused a voucher it cannot use. */
    public void use(String newUseRef, Instant now) {
        if (status != VoucherStatus.ISSUED) {
            throw new IllegalStateException("only an issued voucher can be used");
        }
        this.status = VoucherStatus.USED;
        this.usedAt = now;
        this.useRef = newUseRef;
    }

    /** Records why the voucher stopped being usable without being used. */
    public void voidWith(VoidReason reason) {
        if (status == VoucherStatus.USED) {
            throw new IllegalStateException("a used voucher cannot be voided");
        }
        this.status = VoucherStatus.VOIDED;
        this.voidReason = Objects.requireNonNull(reason, "reason");
    }

    /** Whether the voucher was usable at {@code instant}: still issued, and not yet expired. */
    public boolean usableAt(Instant instant) {
        return status == VoucherStatus.ISSUED && instant.isBefore(expiresAt);
    }

    /** Whether the voucher stopped being usable at {@code instant} while still issued, which is derived, not stored. */
    public boolean expiredAt(Instant instant) {
        return status == VoucherStatus.ISSUED && !instant.isBefore(expiresAt);
    }

    public boolean replayedBy(String candidateUseRef) {
        return status == VoucherStatus.USED && Objects.equals(this.useRef, candidateUseRef);
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public VoucherId id() {
        return id;
    }

    public String code() {
        return code;
    }

    public UUID rewardId() {
        return rewardId;
    }

    public UUID memberId() {
        return memberId;
    }

    public UUID redemptionId() {
        return redemptionId;
    }

    public String externalRef() {
        return externalRef;
    }

    public VoucherStatus status() {
        return status;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant usedAt() {
        return usedAt;
    }

    public String useRef() {
        return useRef;
    }

    public VoidReason voidReason() {
        return voidReason;
    }
}
