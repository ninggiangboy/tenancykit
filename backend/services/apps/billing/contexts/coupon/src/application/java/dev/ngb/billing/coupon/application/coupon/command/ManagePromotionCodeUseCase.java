package dev.ngb.billing.coupon.application.coupon.command;

import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.application.coupon.service.PromotionCodes;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;

/** Adds a promotion code to an existing coupon, or deactivates one. A code never outlives its coupon's terms. */
public class ManagePromotionCodeUseCase {

    public record AddCommand(CouponId couponId, String code, Long maxRedemptions, Instant redeemBy, Audit audit) {}

    public record DeactivateCommand(PromotionCodeId codeId, Audit audit) {}

    private final UnitOfWork unitOfWork;
    private final CouponRepository coupons;
    private final PromotionCodeRepository codes;
    private final AuditLog auditLog;
    private final IdGenerator ids;
    private final Clock clock;

    public ManagePromotionCodeUseCase(
            UnitOfWork unitOfWork, CouponRepository coupons, PromotionCodeRepository codes, AuditLog auditLog, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.coupons = coupons;
        this.codes = codes;
        this.auditLog = auditLog;
        this.ids = ids;
        this.clock = clock;
    }

    public PromotionCode add(AddCommand command) {
        return unitOfWork.transactional(() -> {
            Coupon coupon = coupons.findById(command.couponId()).orElseThrow(Messages::couponNotFound);
            Instant now = clock.now();
            String text = command.code() == null || command.code().isBlank() ? PromotionCodes.generate() : command.code();
            PromotionCode code = PromotionCode.create(new PromotionCodeId(ids.newId()), coupon.tenantId(), coupon.id(), text,
                    command.maxRedemptions(), command.redeemBy(), command.audit().actor(), now);
            codes.add(code);
            auditLog.record(AuditEntry.tenant(coupon.tenantId(), command.audit().actor(), "promotion-code.created", "promotion-code/" + code.id(),
                    command.audit().requestId(), now));
            return code;
        });
    }

    public PromotionCode deactivate(DeactivateCommand command) {
        return unitOfWork.transactional(() -> {
            PromotionCode code = codes.findById(command.codeId()).orElseThrow(Messages::promotionCodeNotFound);
            if (code.isActive()) {
                code.deactivate();
                codes.update(code);
                auditLog.record(AuditEntry.tenant(code.tenantId(), command.audit().actor(), "promotion-code.deactivated",
                        "promotion-code/" + code.id(), command.audit().requestId(), clock.now()));
            }
            return code;
        });
    }
}
