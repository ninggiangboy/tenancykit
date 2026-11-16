package dev.ngb.billing.coupon.application.coupon.query;

import dev.ngb.billing.coupon.application.redemption.query.RedemptionReadRepository;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionView;
import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.List;
import java.util.UUID;

/** Read models for the coupon API. Every read runs in the tenant's transaction, where row-level security applies. */
public class CouponQueries {

    private final UnitOfWork unitOfWork;
    private final CouponReadRepository coupons;
    private final PromotionCodeReadRepository codes;
    private final RedemptionReadRepository redemptions;

    public CouponQueries(
            UnitOfWork unitOfWork, CouponReadRepository coupons, PromotionCodeReadRepository codes, RedemptionReadRepository redemptions) {
        this.unitOfWork = unitOfWork;
        this.coupons = coupons;
        this.codes = codes;
        this.redemptions = redemptions;
    }

    public CouponView coupon(UUID id) {
        return unitOfWork.transactional(() -> coupons.findById(id).orElseThrow(Messages::couponNotFound));
    }

    public List<CouponView> coupons(String status, int limit, int offset) {
        return unitOfWork.transactional(() -> coupons.list(status, clamp(limit), Math.max(0, offset)));
    }

    public List<PromotionCodeView> codesOf(UUID couponId) {
        return unitOfWork.transactional(() -> codes.findByCoupon(couponId));
    }

    public PromotionCodeView code(UUID id) {
        return unitOfWork.transactional(() -> codes.findById(id).orElseThrow(Messages::promotionCodeNotFound));
    }

    public List<RedemptionView> redemptionsOfWallet(UUID walletId, int limit, int offset) {
        return unitOfWork.transactional(() -> redemptions.findByWallet(walletId, clamp(limit), Math.max(0, offset)));
    }

    public RedemptionView redemption(UUID id) {
        return unitOfWork.transactional(() -> redemptions.findById(id).orElseThrow(Messages::couponRedemptionNotFound));
    }

    private static int clamp(int limit) {
        return Math.min(200, Math.max(1, limit));
    }
}
