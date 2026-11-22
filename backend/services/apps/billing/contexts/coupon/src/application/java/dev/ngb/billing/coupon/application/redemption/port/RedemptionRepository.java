package dev.ngb.billing.coupon.application.redemption.port;

import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.id.WalletId;
import java.util.List;
import java.util.Optional;

/**
 * Redemptions, read for a charge under lock and written by the two statements that change them: the one that records
 * an application and the one that ends a redemption.
 */
public interface RedemptionRepository {

    void add(Redemption redemption);

    Optional<Redemption> findById(RedemptionId id);

    /** The redemption read under its row lock, held until the transaction ends. */
    Optional<Redemption> lockById(RedemptionId id);

    Optional<Redemption> findByIdempotencyKey(String idempotencyKey);

    /** The wallet's ACTIVE redemptions, ascending by id, ascending lock order. Takes no lock. */
    List<Redemption> findActiveByWallet(WalletId walletId);

    /** The wallet's ACTIVE redemptions, ascending by id, locked until the transaction ends. */
    List<Redemption> lockActiveByWallet(WalletId walletId);

    /** The coupon's ACTIVE redemptions on this wallet, ascending by id, locked until the transaction ends. */
    List<Redemption> lockActiveByWalletAndCoupon(WalletId walletId, CouponId couponId);

    /** Every ACTIVE redemption of the coupon, locked until the transaction ends; used when the coupon is deactivated. */
    List<Redemption> lockActiveByCoupon(CouponId couponId);

    /**
     * How many non-revoked redemptions of the coupon this customer holds across all their wallets. It is a read, not
     * a lock: the redeem transaction's own wallet lock keeps this customer's redemptions from racing with each other.
     */
    long countForCustomer(CustomerId customerId, CouponId couponId);

    /** Writes the counters and the end state of a redemption whose coupon is untouched. */
    void update(Redemption redemption);

    /**
     * Records one application in one statement, guarded so two concurrent charges cannot both take the last one.
     *
     * @return whether a row changed, which is false when the redemption is no longer ACTIVE or is past its end
     */
    boolean recordApplication(RedemptionId id, long taken, java.time.Instant now);
}
