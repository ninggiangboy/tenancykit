package dev.ngb.loyalty.rewards.application.voucher.port;

import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherId;
import dev.ngb.loyalty.rewards.domain.voucher.VoidReason;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The vouchers a redemption issues.
 *
 * <p>A voucher is addressed by its code, which is the token the Tenant's point of sale presents, and by the redemption
 * that issued it, which is what a reversal names. Every method runs inside the caller's transaction.
 */
public interface VoucherRepository {

    /**
     * Inserts the voucher.
     *
     * @return false when this tenant already holds a voucher with that code, which is the collision the caller retries
     */
    boolean add(Voucher voucher);

    /** Reads a voucher by code under a row lock, which is the only lock a use takes. */
    Optional<Voucher> lockByCode(String code);

    /** Reads the voucher a redemption issued under a row lock, which is what a reversal voids. */
    Optional<Voucher> lockByRedemption(UUID redemptionId);

    /** Reads the voucher a redemption issued, without a lock, for a replayed redemption. */
    Optional<Voucher> findByRedemption(UUID redemptionId);

    /** Marks an issued voucher used; the caller has already held its row and refused what it cannot use. */
    void markUsed(VoucherId id, String useRef, Instant usedAt);

    /** Marks an issued voucher voided for the reason the caller decided. */
    void voidIssued(VoucherId id, VoidReason reason);
}
