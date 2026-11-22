package dev.ngb.billing.coupon.application.coupon.command;

import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.application.redemption.service.EndingRedemptions;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.redemption.EndReason;
import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;

/**
 * Deactivates a coupon, its codes and optionally its running redemptions. Deactivation is final and stops new
 * redemptions; a running one keeps working unless the caller asks for revocation, which is how a bad coupon is
 * pulled without invalidating a charge that already used it.
 */
public class DeactivateCouponUseCase {

    /** @param revokeRedemptions end every ACTIVE redemption of this coupon, as COUPON_DEACTIVATED */
    public record Command(CouponId couponId, boolean revokeRedemptions, Audit audit) {}

    private final UnitOfWork unitOfWork;
    private final CouponRepository coupons;
    private final PromotionCodeRepository codes;
    private final EndingRedemptions endingRedemptions;
    private final AuditLog auditLog;
    private final Clock clock;

    public DeactivateCouponUseCase(
            UnitOfWork unitOfWork, CouponRepository coupons, PromotionCodeRepository codes, EndingRedemptions endingRedemptions,
            AuditLog auditLog, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.coupons = coupons;
        this.codes = codes;
        this.endingRedemptions = endingRedemptions;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public Coupon execute(Command command) {
        return unitOfWork.transactional(() -> {
            Coupon coupon = coupons.lockById(command.couponId()).orElseThrow(Messages::couponNotFound);
            Instant now = clock.now();
            boolean wasActive = coupon.isActive();
            coupon.deactivate(now);
            if (!wasActive) {
                return coupon;
            }
            coupons.update(coupon);
            for (PromotionCode code : codes.findByCoupon(coupon.id())) {
                if (code.isActive()) {
                    code.deactivate();
                    codes.update(code);
                }
            }
            if (command.revokeRedemptions()) {
                endingRedemptions.endAll(coupon.id(), EndReason.COUPON_DEACTIVATED, now);
            }
            auditLog.record(AuditEntry.tenant(coupon.tenantId(), command.audit().actor(), "coupon.deactivated", "coupon/" + coupon.id(),
                    command.audit().requestId(), now));
            return coupon;
        });
    }
}
