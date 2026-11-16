package dev.ngb.billing.coupon.application.redemption.service;

import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.event.CouponEvents;
import dev.ngb.billing.coupon.domain.redemption.EndReason;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.IdGenerator;
import java.time.Instant;
import java.util.List;

/**
 * Ends redemptions and announces each one in the caller's transaction. Both the revoke command and the coupon's
 * deactivation go through here, so an ended redemption always publishes exactly one event and the caller keeps its
 * own transaction.
 */
public class EndingRedemptions {

    private final RedemptionRepository redemptions;
    private final EventPublisher events;
    private final IdGenerator ids;

    public EndingRedemptions(RedemptionRepository redemptions, EventPublisher events, IdGenerator ids) {
        this.redemptions = redemptions;
        this.events = events;
        this.ids = ids;
    }

    /** Ends one redemption with {@code reason} and returns it; a finished redemption is left untouched. */
    public Redemption end(Redemption redemption, EndReason reason, Instant now) {
        if (redemption.status() != dev.ngb.billing.coupon.domain.redemption.RedemptionStatus.ACTIVE) {
            return redemption;
        }
        redemption.end(reason, now);
        redemptions.update(redemption);
        events.publish(new CouponEvents.CouponRedemptionEnded(ids.newId(), redemption, now));
        return redemption;
    }

    /** Ends every ACTIVE redemption of the coupon. */
    public List<Redemption> endAll(CouponId couponId, EndReason reason, Instant now) {
        List<Redemption> active = redemptions.lockActiveByCoupon(couponId);
        active.forEach(redemption -> end(redemption, reason, now));
        return active;
    }

    /** Ends every ACTIVE redemption of the coupon on one wallet. */
    public List<Redemption> endAllOnWallet(CouponId couponId, dev.ngb.billing.sharedkernel.id.WalletId walletId, EndReason reason, Instant now) {
        List<Redemption> active = redemptions.lockActiveByWalletAndCoupon(walletId, couponId);
        active.forEach(redemption -> end(redemption, reason, now));
        return active;
    }
}
