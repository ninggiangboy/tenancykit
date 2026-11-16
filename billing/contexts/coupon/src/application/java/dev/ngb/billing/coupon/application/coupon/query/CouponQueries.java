package dev.ngb.billing.coupon.application.coupon.query;

import dev.ngb.billing.coupon.application.redemption.query.RedemptionReadRepository;
import dev.ngb.billing.coupon.application.redemption.query.RedemptionView;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/**
 * Read models for the coupon API. Every read runs in the tenant's transaction, where row-level security applies.
 * Coupons and codes belong to the Tenant, but a redemption belongs to a customer, so a Tenant System reads only the
 * redemptions of the customers it mapped; any other redemption answers as if it did not exist.
 */
public class CouponQueries {

    private final UnitOfWork unitOfWork;
    private final CouponReadRepository coupons;
    private final PromotionCodeReadRepository codes;
    private final RedemptionReadRepository redemptions;
    private final CustomerVisibility visibility;

    public CouponQueries(
            UnitOfWork unitOfWork, CouponReadRepository coupons, PromotionCodeReadRepository codes, RedemptionReadRepository redemptions,
            CustomerLookup customers) {
        this.unitOfWork = unitOfWork;
        this.coupons = coupons;
        this.codes = codes;
        this.redemptions = redemptions;
        this.visibility = new CustomerVisibility(customers);
    }

    public CouponView coupon(UUID id) {
        return unitOfWork.transactional(() -> coupons.findById(id).orElseThrow(Messages::couponNotFound));
    }

    public Page<CouponView> coupons(ListQuery query) {
        return unitOfWork.transactional(() -> coupons.list(query));
    }

    public Page<PromotionCodeView> codesOf(UUID couponId, ListQuery query) {
        return unitOfWork.transactional(() -> codes.list(query, couponId));
    }

    public PromotionCodeView code(UUID id) {
        return unitOfWork.transactional(() -> codes.findById(id).orElseThrow(Messages::promotionCodeNotFound));
    }

    /** A wallet has one customer, so one visibility check on its first redemption covers the page. */
    public Page<RedemptionView> redemptionsOfWallet(UUID walletId, UUID caller, ListQuery query) {
        return unitOfWork.transactional(() -> {
            Page<RedemptionView> found = redemptions.list(query, walletId);
            if (!found.data().isEmpty()) {
                requireVisible(found.data().get(0), caller);
            }
            return found;
        });
    }

    public RedemptionView redemption(UUID id, UUID caller) {
        return unitOfWork.transactional(() -> {
            RedemptionView found = redemptions.findById(id).orElseThrow(Messages::couponRedemptionNotFound);
            requireVisible(found, caller);
            return found;
        });
    }

    private void requireVisible(RedemptionView redemption, UUID caller) {
        visibility.requireVisible(new CustomerId(redemption.customerId()), caller, Messages::couponRedemptionNotFound);
    }
}
